"""
Browser Agent — Playwright-powered browser automation engine.
Invoked by TLBrowserSkill via subprocess.

Two invocation modes:
  One-shot (default): python browser_agent.py --action <action> [args...]
      Each invocation starts a fresh browser, performs the action, exits.
  Serve (--serve):    python browser_agent.py --serve --port <port>
      Long-running HTTP server keeping ONE browser alive across requests
      (page state persists). Endpoints:
        POST /action   body: {"action": "navigate", "url": "..."} → JSON result
        GET  /health   → {"ok": true, "url": "...", "mode": "persistent"}
        POST /shutdown → graceful exit (closes browser)

Browser forms (CLI): --headed (headed), --user-data-dir <dir> (persistent context,
login state survives restarts), --cdp <endpoint> [--cdp-auto-launch] (attach to a
real Chrome/Edge). No flags = ephemeral (fresh browser per process).
Returns: JSON with ok=true/false + action-specific fields
"""
import sys, json, argparse, base64, re, os, threading, time, subprocess
from urllib.parse import urlparse

def _output(obj):
    """安全输出 JSON 到 stdout，绕过 Windows GBK 编码问题（子进程管道兼容）。"""
    sys.stdout.buffer.write(json.dumps(obj, ensure_ascii=False).encode('utf-8') + b'\n')
    sys.stdout.buffer.flush()

try:
    from playwright.sync_api import sync_playwright, TimeoutError as PwTimeout
except ImportError:
    _output({"ok": False, "error": "Playwright not installed. Run: pip install playwright && playwright install chromium"})
    sys.exit(1)

# ---- 浏览器形态（CLI 注入，_configure 决定）----
_mode = "ephemeral"          # ephemeral | persistent | cdp
_headed = False
_user_data_dir = ""
_cdp_endpoint = ""
_cdp_auto_launch = False
_cdp_profile_dir = ""
_browser_exe = ""
_max_text_chars = 5000       # 页面正文(text)返回最大字符数（Java --max-text-chars 注入）
_state_file = ""             # <cdp_profile_dir>/.tlobject_agent_tab.json

_pw = None
_browser = None
_ctx = None                  # persistent context / cdp 的默认 context
_page = None
# serve 模式：所有动作经此锁串行（sync API 非线程安全）
_action_lock = threading.Lock()

def _configure(args):
    """按 CLI 参数决定形态：cdp > persistent > ephemeral。"""
    global _mode, _headed, _user_data_dir, _cdp_endpoint, _cdp_auto_launch
    global _cdp_profile_dir, _browser_exe, _state_file, _max_text_chars
    _headed = bool(args.headed)
    _max_text_chars = args.max_text_chars
    _user_data_dir = args.user_data_dir or ""
    _cdp_endpoint = args.cdp or ""
    _cdp_auto_launch = bool(args.cdp_auto_launch)
    _cdp_profile_dir = args.cdp_profile_dir or ""
    _browser_exe = args.browser_exe or ""
    if _cdp_endpoint:
        _mode = "cdp"
        if not urlparse(_cdp_endpoint).port:
            _output({"ok": False, "error": "cdp 端点需形如 http://host:port: " + _cdp_endpoint})
            sys.exit(1)
        if not _cdp_profile_dir:
            _output({"ok": False, "error": "cdp 模式必须提供 --cdp-profile-dir（标签页状态文件宿主目录）"})
            sys.exit(1)
        os.makedirs(_cdp_profile_dir, exist_ok=True)
        _state_file = os.path.join(_cdp_profile_dir, ".tlobject_agent_tab.json")
        # Playwright 的 Node 驱动同样读 HTTP_PROXY：本地 CDP 端点必须列入 NO_PROXY，
        # 否则 connect_over_cdp 取 ws 端点也会走代理（实测 ECONNREFUSED 到代理端口）。
        # 只加端点主机，其余流量照常走代理。
        host = urlparse(_cdp_endpoint).hostname
        if host:
            for var in ("NO_PROXY", "no_proxy"):
                parts = [p.strip() for p in os.environ.get(var, "").split(",") if p.strip()]
                if host not in parts:
                    os.environ[var] = ",".join(parts + [host])
    elif _user_data_dir:
        _mode = "persistent"
        os.makedirs(_user_data_dir, exist_ok=True)
    else:
        _mode = "ephemeral"

def _ensure_browser():
    global _pw, _browser, _ctx, _page
    if _page is not None and not _page.is_closed():
        # cdp 模式下 is_closed()/is_connected() 都是本地标志，连接断了要等下一次协议
        # 往返才更新（实测浏览器被杀后第一次动作仍是原始 Playwright 错误）——所以再探
        # 一次端点（本地 HTTP、带超时、绕代理），探不通就当句柄已失效走重建。
        if _mode != "cdp" or (_browser is not None and _browser.is_connected()
                              and _probe(_cdp_endpoint)):
            return
    if _pw is not None:      # 上次句柄已失效（页面被关/浏览器死了/连接断了）→ 先清干净防泄漏
        _cleanup()
    _pw = sync_playwright().start()
    if _mode == "cdp":
        _cdp_connect()
    elif _mode == "persistent":
        _ctx = _pw.chromium.launch_persistent_context(_user_data_dir, headless=not _headed)
        _page = _ctx.pages[0] if _ctx.pages else _ctx.new_page()
    else:
        _browser = _pw.chromium.launch(headless=not _headed)
        _page = _browser.new_page()
    if _page is not None:
        _page.set_default_timeout(15000)

def _cleanup():
    global _pw, _browser, _ctx, _page
    try:
        if _mode == "cdp":
            # 只断开连接：不动用户浏览器的标签页与进程（实测 close() 对 connect_over_cdp 仅断连）
            if _pw: _pw.stop()
        elif _mode == "persistent":
            if _ctx: _ctx.close()
            if _pw: _pw.stop()
        else:
            if _page: _page.close()
            if _browser: _browser.close()
            if _pw: _pw.stop()
    except Exception as e:
        print("cleanup error: " + str(e), flush=True)
    _page = _browser = _ctx = _pw = None

_probe_opener = None      # 缓存：build_opener 每次构造约 30-50ms，而 _probe 在 cdp 模式每个动作都会走

def _probe(endpoint, timeout=2):
    """探测 CDP 端点是否可达（/json/version 返回 200 且是真浏览器）。
    必须绕开系统代理：urlopen 默认读 HTTP_PROXY，代理存在时会把本地端点误判为不通。"""
    import urllib.request
    global _probe_opener
    try:
        if _probe_opener is None:
            _probe_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with _probe_opener.open(endpoint.rstrip("/") + "/json/version", timeout=timeout) as r:
            if r.status != 200:
                return False
            info = json.loads(r.read().decode("utf-8"))
            return bool(info.get("webSocketDebuggerUrl"))
    except Exception:
        return False

def _detect_browser():
    """系统浏览器探测：显式 --browser-exe > Chrome > Edge（含 %LOCALAPPDATA% 个人安装）。"""
    if _browser_exe:
        return _browser_exe if os.path.exists(_browser_exe) else None
    paths = [r"C:\Program Files\Google\Chrome\Application\chrome.exe",
             r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"]
    local = os.environ.get("LOCALAPPDATA")
    if local:
        paths.append(os.path.join(local, r"Google\Chrome\Application\chrome.exe"))
    paths += [r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
              r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"]
    if local:
        paths.append(os.path.join(local, r"Microsoft\Edge\Application\msedge.exe"))
    for p in paths:
        if os.path.exists(p):
            return p
    return None

# 中间进程代码：把浏览器拉起来后自己立刻退出。浏览器必须挂在"已死的中间进程"名下，
# 不能挂在 serve 进程名下——Java killProcess 兜底会 taskkill /F /T <python pid> 连根杀
# 进程树（按 parent pid 找后代；实测 DETACHED_PROCESS|CREATE_NEW_PROCESS_GROUP 完全不挡，
# 浏览器照杀）→ 若浏览器是 serve 的直接子进程，就会被连坐杀掉，违背"永不关用户浏览器"。
# 中间进程退出后浏览器 ppid 指向死进程，不再出现在 serve 的树里（实测 taskkill /T 后
# 调试端口仍可达）。
# 0x8|0x200 = DETACHED_PROCESS|CREATE_NEW_PROCESS_GROUP（写死是因为这是段 -c 字符串，
# 非 Windows 下 creationflags 被忽略，不影响 Linux 侧）。
_LAUNCH_HELPER_CODE = (
    "import subprocess,sys;"
    "subprocess.Popen(sys.argv[1:], creationflags=0x00000008|0x00000200,"
    " stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)")

def _launch_system_browser():
    """自动拉起系统浏览器（专用 profile + 调试端口）。只用于专用 profile。"""
    if _browser_exe and not os.path.exists(_browser_exe):
        raise RuntimeError("指定的 browserExe 不存在: " + _browser_exe)
    exe = _detect_browser()
    if not exe:
        raise RuntimeError("未找到系统 Chrome/Edge，请用 browserExe 配置指定路径")
    port = urlparse(_cdp_endpoint).port
    flags = 0
    if os.name == "nt":
        flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    args = [exe,
            "--remote-debugging-port=%d" % port,
            "--user-data-dir=" + _cdp_profile_dir,
            "--no-first-run", "--no-default-browser-check"]
    # 标准流全给 DEVNULL：不让浏览器握住 serve 的 stdout 管道（Java 侧 drain 读 tail）。
    # 但 helper 自身的 stderr 必须保留（PIPE）：helper 只是拉起浏览器，自身不写流，
    # 其子进程的流已全 DEVNULL，所以 stderr 管道不会被浏览器握住不放；非零退出时
    # 这里的 stderr 是"拉起失败"的唯一真因（如 WinError 193）。
    helper = subprocess.Popen([sys.executable, "-c", _LAUNCH_HELPER_CODE] + args,
                              creationflags=flags,
                              stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                              stderr=subprocess.PIPE)
    helper_err = b""
    try:
        _, helper_err = helper.communicate(timeout=10)  # 等中间进程退出：此后浏览器的 ppid 已是死 pid
    except subprocess.TimeoutExpired:
        helper.kill()
        print("launch helper timeout, killing it (browser may not be detached)", flush=True)
    if helper.returncode:
        raise RuntimeError("拉起浏览器失败（%s）: %s" % (
            exe, helper_err.decode("utf-8", "replace").strip()))
    return exe

def _cdp_connect():
    global _browser, _ctx, _page
    if not _probe(_cdp_endpoint):
        if not _cdp_auto_launch:
            raise RuntimeError("无法连接调试浏览器 " + _cdp_endpoint
                               + "（请先用 agentbrowser.bat 启动，或配置 cdpAutoLaunch=true）")
        host = urlparse(_cdp_endpoint).hostname
        if host not in ("127.0.0.1", "localhost", "::1"):
            raise RuntimeError("cdpAutoLaunch 只支持本机端点（127.0.0.1/localhost）: " + _cdp_endpoint)
        exe = _launch_system_browser()
        deadline = time.time() + 20
        ready = _probe(_cdp_endpoint)
        while not ready and time.time() < deadline:
            time.sleep(0.5)
            ready = _probe(_cdp_endpoint)
        if not ready:
            raise RuntimeError("已拉起浏览器（%s）但 20s 内调试端口未就绪: %s" % (exe, _cdp_endpoint))
    # 显式握手超时：默认 30s 与 Java 侧 45s 就绪预算留的余量太小（/json/version 已应答
    # 但 WebSocket 握手卡住时会吃满 30s）
    _browser = _pw.chromium.connect_over_cdp(_cdp_endpoint, timeout=10000)
    _ctx = _browser.contexts[0] if _browser.contexts else _browser.new_context()
    _page = _adopt_or_create_page()

def _adopt_or_create_page():
    """认领上次的标签页（targetId 比对）；认不到就新开一个并记录。
    绝不采用、绝不关闭用户已有的其它标签页。"""
    target_id = None
    try:
        with open(_state_file, encoding="utf-8") as f:
            target_id = json.load(f).get("targetId")
    except Exception:
        pass
    if target_id:
        for page in _ctx.pages:
            session = None
            try:
                session = _ctx.new_cdp_session(page)
                info = session.send("Target.getTargetInfo")
                if (info.get("targetInfo") or {}).get("targetId") == target_id:
                    print("cdp: adopted tab " + target_id, flush=True)
                    return page
            except Exception:
                continue
            finally:
                if session:
                    try: session.detach()
                    except Exception: pass
    page = _ctx.new_page()
    new_target_id = None
    try:
        session = None
        try:
            session = _ctx.new_cdp_session(page)
            info = session.send("Target.getTargetInfo")
            new_target_id = info["targetInfo"]["targetId"]
        finally:
            if session:
                try: session.detach()
                except Exception: pass
    except Exception as e:
        print("cdp: cannot identify new tab: " + str(e), flush=True)
        # 拿不到 id 就不写状态文件（下次只是认不回这个标签页，不影响动作）
    if new_target_id:
        print("cdp: new tab " + new_target_id, flush=True)
        try:
            with open(_state_file, "w", encoding="utf-8") as f:
                json.dump({"targetId": new_target_id}, f)
        except Exception as e:
            print("cdp state file write failed: " + str(e), flush=True)
    return page

def _get_text(max_len=None):
    if max_len is None:
        max_len = _max_text_chars
    try:
        text = _page.inner_text("body")
        # max_len<=0 表示不限（与 Java maxTextChars 语义一致），仅正数才截断
        if max_len and max_len > 0:
            if text and len(text) > max_len:
                return text[:max_len] + "\n…[页面正文超过 " + str(max_len) + " 字符已截断；如需完整内容请分段提取]"
            return text or ""
        return text or ""
    except Exception:
        return ""

# 整页截图高度上限：超限的无限滚动页面截视口，避免输出巨图（MB 级 base64 拖垮传输/渲染）
FULLPAGE_MAX_HEIGHT = 12000

def _screenshot_base64(full_page=False):
    """full_page=True 截整页（限高）。navigate/click/type 的附带预览保持视口（轻量）。"""
    if full_page:
        try:
            h = _page.evaluate("document.documentElement.scrollHeight || document.body.scrollHeight || 0")
            if h <= FULLPAGE_MAX_HEIGHT:
                return base64.b64encode(_page.screenshot(full_page=True)).decode()
        except Exception:
            pass  # 高度探测失败 → 回落视口截图
    return base64.b64encode(_page.screenshot(full_page=False)).decode()

# ---- actions ----
def navigate(url, **kwargs):
    _ensure_browser()
    _page.goto(url, wait_until="domcontentloaded")
    return {"ok": True, "url": _page.url, "title": _page.title(),
            "text": _get_text(), "screenshot_base64": _screenshot_base64()}

def click(selector, **kwargs):
    _ensure_browser()
    # Try CSS selector first, then text match
    try:
        _page.click(selector, timeout=5000)
    except PwTimeout:
        _page.click(f"text={selector}", timeout=5000)
    _page.wait_for_timeout(500)  # wait for any UI reaction
    return {"ok": True, "url": _page.url, "title": _page.title(),
            "text": _get_text(), "screenshot_base64": _screenshot_base64()}

def type_text(selector, text, **kwargs):
    _ensure_browser()
    try:
        _page.fill(selector, text, timeout=5000)
    except PwTimeout:
        _page.click(f"text={selector}", timeout=5000)
        _page.keyboard.type(text)
    return {"ok": True, "text": _get_text(), "screenshot_base64": _screenshot_base64()}

def screenshot(**kwargs):
    _ensure_browser()
    return {"ok": True, "url": _page.url, "title": _page.title(),
            "screenshot_base64": _screenshot_base64(full_page=True)}

def extract(what="all", **kwargs):
    _ensure_browser()
    result = {}
    if what in ("text", "all"):
        result["text"] = _get_text()
    if what in ("links", "all"):
        links = _page.eval_on_selector_all("a[href]", "els => els.map(e => ({text: e.textContent?.trim(), href: e.href})).filter(l=>l.text)")
        result["links"] = links[:50]
    if what in ("tables", "all"):
        tables = _page.eval_on_selector_all("table", "els => els.map((t,i) => ({index: i, rows: t.rows.length, text: t.innerText?.substring(0,2000)}))")
        result["tables"] = tables[:10]
    return {"ok": True, "url": _page.url, **result}

def scroll(direction="down", amount=500, **kwargs):
    _ensure_browser()
    delta = amount if direction == "down" else -amount
    _page.evaluate(f"window.scrollBy(0, {delta})")
    _page.wait_for_timeout(300)
    return {"ok": True, "screenshot_base64": _screenshot_base64()}

ACTIONS = {
    "navigate": navigate, "click": click, "type": type_text,
    "screenshot": screenshot, "extract": extract, "scroll": scroll
}

# ---- serve 模式：HTTP 常驻服务 ----
def _run_serve(port):
    """长驻进程：单个浏览器跨请求存活，页面状态跨动作保留。
    必须用单线程 HTTPServer——Playwright sync API 绑定启动它的线程
    (greenlet 校验)，多线程服务器会把动作派到工作线程触发
    "Cannot switch to a different thread"。"""
    from http.server import BaseHTTPRequestHandler, HTTPServer

    def shutdown_soon():
        """让 serve_forever 返回——清理必须由 serve 线程做（sync API 线程亲和）。"""
        time.sleep(0.5)  # 先让 /shutdown 响应发出去
        server.shutdown()

    def handle_action(body):
        global _page
        action = body.get("action", "")
        if action not in ACTIONS:
            return 400, {"ok": False, "error": "unknown action: " + action}
        kwargs = {k: v for k, v in body.items() if k != "action" and v not in (None, "")}
        try:
            with _action_lock:
                result = ACTIONS[action](**kwargs)
            return 200, result
        except Exception as e:
            # 人在浏览器里关掉了 agent 的标签页 → 丢弃句柄重来一次（重连+重开/认领），
            # 否则要白失败一轮才自愈（③ 是"人机同窗"形态，这是常规事件）
            if _mode == "cdp" and "has been closed" in str(e):
                _page = None
                try:
                    with _action_lock:
                        result = ACTIONS[action](**kwargs)
                    return 200, result
                except Exception as e2:
                    return 500, {"ok": False, "error": str(e2)}
            return 500, {"ok": False, "error": str(e)}

    class Handler(BaseHTTPRequestHandler):
        def _respond(self, code, obj):
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/health":
                try:
                    url = _page.url if (_page and not _page.is_closed()) else "none"
                    self._respond(200, {"ok": True, "url": url, "mode": _mode})
                except Exception as e:
                    self._respond(200, {"ok": True, "url": "none", "error": str(e), "mode": _mode})
            else:
                self._respond(404, {"ok": False, "error": "not found: " + self.path})

        def do_POST(self):
            if self.path == "/action":
                try:
                    length = int(self.headers.get("Content-Length", 0))
                    body = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
                except Exception:
                    body = {}
                code, result = handle_action(body)
                self._respond(code, result)
            elif self.path == "/shutdown":
                self._respond(200, {"ok": True})
                threading.Thread(target=shutdown_soon, daemon=True).start()
            else:
                self._respond(404, {"ok": False, "error": "not found: " + self.path})

        def log_message(self, *args):
            pass  # 静默访问日志，避免刷父进程输出

    try:
        _ensure_browser()  # 预热；失败原因打到 stdout，Java 侧 drain-tail 会带进错误信息
    except Exception as e:
        _output({"ok": False, "error": str(e)})
        sys.exit(1)
    server = HTTPServer(("127.0.0.1", port), Handler)
    exit_code = 0
    try:
        server.serve_forever()
    except BaseException:
        import traceback
        traceback.print_exc()
        exit_code = 1
    _cleanup()  # 必须在 serve 线程执行（sync API 线程亲和）
    os._exit(exit_code)

# ---- CLI 入口 ----
if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--action", required=False, choices=list(ACTIONS.keys()))
    parser.add_argument("--url", default="")
    parser.add_argument("--selector", default="")
    parser.add_argument("--text", default="")
    parser.add_argument("--what", default="all")
    parser.add_argument("--direction", default="down")
    parser.add_argument("--amount", type=int, default=500)
    parser.add_argument("--headed", action="store_true", help="有头模式（仅 ephemeral/persistent）")
    parser.add_argument("--user-data-dir", default="", help="持久化 profile 目录（空 = ephemeral）")
    parser.add_argument("--cdp", default="", help="接管真实浏览器：CDP 端点，如 http://127.0.0.1:9222")
    parser.add_argument("--cdp-auto-launch", action="store_true", help="端点不通时自动拉起系统浏览器")
    parser.add_argument("--cdp-profile-dir", default="", help="自动拉起的专用 profile 目录（兼状态文件宿主）")
    parser.add_argument("--browser-exe", default="", help="系统浏览器路径（空 = 自动探测 Chrome/Edge）")
    parser.add_argument("--max-text-chars", type=int, default=5000, help="页面正文(text)返回最大字符数（Java maxTextChars 注入，默认 5000）")
    parser.add_argument("--serve", action="store_true", help="keep-alive HTTP server mode")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    _configure(args)

    if args.serve:
        _run_serve(args.port)
        sys.exit(0)

    if args.action is None:
        parser.error("the following arguments are required: --action")
    try:
        _META = ("action", "serve", "port", "headed", "user_data_dir", "cdp",
                 "cdp_auto_launch", "cdp_profile_dir", "browser_exe", "max_text_chars")
        action_args = {k: v for k, v in vars(args).items() if k not in _META and v}
        result = ACTIONS[args.action](**action_args)
        _output(result)
    except Exception as e:
        _output({"ok": False, "error": str(e)})
    finally:
        _cleanup()
