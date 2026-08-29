"""
Browser Agent — Playwright-powered browser automation engine.
Invoked by TLBrowserSkill via subprocess.

Two modes:
  One-shot (default): python browser_agent.py --action <action> [args...]
      Each invocation starts a fresh browser, performs the action, exits.
  Serve (--serve):    python browser_agent.py --serve --port <port>
      Long-running HTTP server keeping ONE browser alive across requests
      (page state persists). Endpoints:
        POST /action   body: {"action": "navigate", "url": "..."} → JSON result
        GET  /health   → {"ok": true, "url": "..."}
        POST /shutdown → graceful exit (closes browser)
Returns: JSON with ok=true/false + action-specific fields
"""
import sys, json, argparse, base64, re, os, threading

def _output(obj):
    """安全输出 JSON 到 stdout，绕过 Windows GBK 编码问题（子进程管道兼容）。"""
    sys.stdout.buffer.write(json.dumps(obj, ensure_ascii=False).encode('utf-8') + b'\n')
    sys.stdout.buffer.flush()

try:
    from playwright.sync_api import sync_playwright, TimeoutError as PwTimeout
except ImportError:
    _output({"ok": False, "error": "Playwright not installed. Run: pip install playwright && playwright install chromium"})
    sys.exit(1)

# ---- browser lifecycle ----
_pw = None
_browser = None
_page = None
# serve 模式：所有动作经此锁串行（sync API 非线程安全）
_action_lock = threading.Lock()

def _ensure_browser():
    global _pw, _browser, _page
    if _page is None or _page.is_closed():
        _pw = sync_playwright().start()
        _browser = _pw.chromium.launch(headless=True)
        _page = _browser.new_page()
        _page.set_default_timeout(15000)

def _cleanup():
    global _pw, _browser, _page
    try:
        if _page: _page.close()
        if _browser: _browser.close()
        if _pw: _pw.stop()
    except Exception:
        pass
    _page = _browser = _pw = None

def _get_text(max_len=5000):
    try:
        text = _page.inner_text("body")
        return text[:max_len] if text else ""
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

def extract(what="text", **kwargs):
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

    def handle_action(body):
        action = body.get("action", "")
        if action not in ACTIONS:
            return 400, {"ok": False, "error": "unknown action: " + action}
        kwargs = {k: v for k, v in body.items() if k != "action" and v not in (None, "")}
        try:
            with _action_lock:
                result = ACTIONS[action](**kwargs)
            return 200, result
        except Exception as e:
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
                    self._respond(200, {"ok": True, "url": url})
                except Exception as e:
                    self._respond(200, {"ok": True, "url": "none", "error": str(e)})
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
                threading.Thread(target=_serve_shutdown_later, daemon=True).start()
            else:
                self._respond(404, {"ok": False, "error": "not found: " + self.path})

        def log_message(self, *args):
            pass  # 静默访问日志，避免刷父进程输出

    _ensure_browser()  # 预热：启动即拉起浏览器
    server = HTTPServer(("127.0.0.1", port), Handler)
    server.serve_forever()

def _serve_shutdown_later():
    import time
    time.sleep(0.5)  # 先让响应发出去
    _cleanup()
    os._exit(0)

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
    parser.add_argument("--serve", action="store_true", help="keep-alive HTTP server mode")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    if args.serve:
        _run_serve(args.port)
        sys.exit(0)

    if args.action is None:
        parser.error("the following arguments are required: --action")
    try:
        action_args = {k: v for k, v in vars(args).items() if k not in ("action", "serve", "port") and v}
        result = ACTIONS[args.action](**action_args)
        _output(result)
    except Exception as e:
        _output({"ok": False, "error": str(e)})
    finally:
        _cleanup()
