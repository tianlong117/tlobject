"""
Browser Agent — Playwright-powered browser automation engine.
Invoked by TLScriptExecutionSkill via subprocess.

Usage: python browser_agent.py --action <action> [args...]
Returns: JSON with ok=true/false + action-specific fields
"""
import sys, json, argparse, base64, re, os

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

def _screenshot_base64():
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
            "screenshot_base64": _screenshot_base64()}

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

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--action", required=True, choices=list(ACTIONS.keys()))
    parser.add_argument("--url", default="")
    parser.add_argument("--selector", default="")
    parser.add_argument("--text", default="")
    parser.add_argument("--what", default="all")
    parser.add_argument("--direction", default="down")
    parser.add_argument("--amount", type=int, default=500)
    args = parser.parse_args()

    try:
        action_args = {k: v for k, v in vars(args).items() if k != "action" and v}
        result = ACTIONS[args.action](**action_args)
        _output(result)
    except Exception as e:
        _output({"ok": False, "error": str(e)})
    finally:
        _cleanup()
