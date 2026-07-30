"""
Desktop Agent — screenshot and input automation via pyautogui + mss.
Invoked by TLScriptExecutionSkill via subprocess.

Usage: python desktop_agent.py --action <action> [args...]
Returns: JSON with ok=true/false + action-specific fields
"""
import sys, json, argparse, base64, io

try:
    import pyautogui
    import mss
except ImportError:
    print(json.dumps({"ok": False, "error": "pyautogui or mss not installed. Run: pip install pyautogui mss"}))
    sys.exit(1)

# safety: fail-safe enabled by default
pyautogui.FAILSAFE = True
pyautogui.PAUSE = 0.1

def _screenshot(region=None):
    """Capture screen and return base64 PNG."""
    with mss.mss() as sct:
        if region:
            monitor = {"top": region[1], "left": region[0], "width": region[2], "height": region[3]}
        else:
            monitor = sct.monitors[0]  # full primary monitor
        img = sct.grab(monitor)
        png = mss.tools.to_png(img.rgb, img.size)
        return base64.b64encode(png).decode()

# ---- actions ----
def screenshot(region=None, **kwargs):
    b64 = _screenshot(region)
    w, h = pyautogui.size()
    return {"ok": True, "screenshot_base64": b64, "width": w, "height": h}

def click(x, y, button="left", **kwargs):
    pyautogui.click(int(x), int(y), button=button)
    b64 = _screenshot()
    return {"ok": True, "screenshot_base64": b64, "clicked": [int(x), int(y)]}

def type_text(text, interval=0.05, **kwargs):
    pyautogui.typewrite(text, interval=float(interval))
    return {"ok": True, "typed": text}

def move(x, y, duration=0.2, **kwargs):
    pyautogui.moveTo(int(x), int(y), duration=float(duration))
    return {"ok": True, "moved_to": [int(x), int(y)]}

def scroll(amount, **kwargs):
    pyautogui.scroll(int(amount))
    return {"ok": True, "scrolled": int(amount)}

def get_screen_size(**kwargs):
    w, h = pyautogui.size()
    return {"ok": True, "width": w, "height": h}

ACTIONS = {
    "screenshot": screenshot,
    "click": click,
    "type": type_text,
    "move": move,
    "scroll": scroll,
    "get_screen_size": get_screen_size,
}

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--action", required=True, choices=list(ACTIONS.keys()))
    parser.add_argument("--x", type=int, default=0)
    parser.add_argument("--y", type=int, default=0)
    parser.add_argument("--text", default="")
    parser.add_argument("--amount", type=int, default=100)
    parser.add_argument("--button", default="left")
    parser.add_argument("--duration", type=float, default=0.2)
    parser.add_argument("--interval", type=float, default=0.05)
    args = parser.parse_args()

    try:
        action_args = {k: v for k, v in vars(args).items() if k != "action" and v != 0}
        result = ACTIONS[args.action](**action_args)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        print(json.dumps({"ok": False, "error": str(e)}, ensure_ascii=False))
