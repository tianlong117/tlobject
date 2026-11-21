# -*- coding: utf-8 -*-
"""mermaid-flowchart Skill 自检脚本。

依次检查运行时环境，再用 examples 下的三份示例端到端渲染，
逐项打印通过/失败，最后给出汇总。全通过返回 0，否则返回非 0。

用法：
    python test/run_selftest.py
"""
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SKILL_DIR = os.path.dirname(HERE)
SCRIPT = os.path.join(SKILL_DIR, "scripts", "mermaid_render.py")
EXAMPLES = os.path.join(SKILL_DIR, "examples")

MIN_PNG_BYTES = 10 * 1024
TIMEOUT = 300

results = []


def record(name, ok, detail=""):
    """记录一项检查结果并打印。"""
    results.append((name, ok, detail))
    tag = "通过" if ok else "失败"
    line = "[%s] %s" % (tag, name)
    if detail:
        line += "  -> " + detail
    print(line)
    return ok


def decode(data):
    """跨平台解码子进程输出，避免 GBK/UTF-8 混乱。"""
    for enc in ("utf-8", "gbk", "latin-1"):
        try:
            return data.decode(enc).strip()
        except UnicodeDecodeError:
            continue
    return repr(data)


def run_cmd(args, cwd=None):
    """执行命令并返回 (returncode, stdout+stderr)。Windows 下 shell=True 走 .cmd。"""
    env = dict(os.environ)
    env.setdefault("PYTHONIOENCODING", "utf-8")
    try:
        proc = subprocess.run(
            args, cwd=cwd, env=env, timeout=TIMEOUT,
            shell=True, capture_output=True,
        )
        return proc.returncode, decode(proc.stdout + proc.stderr)
    except subprocess.TimeoutExpired:
        return -1, "超时(%ds)" % TIMEOUT
    except FileNotFoundError as exc:
        return -2, "命令不存在: %s" % exc


def first_line(text):
    """取输出首行用于展示，避免刷屏。"""
    text = (text or "").strip()
    return text.splitlines()[0] if text else ""


def main():
    print("=" * 62)
    print("mermaid-flowchart Skill 自检")
    print("skill 目录: %s" % SKILL_DIR)
    print("=" * 62)

    # 1. python 版本
    py_ok = sys.version_info >= (3, 8)
    record("python 版本 >= 3.8", py_ok, "当前 Python %s" % sys.version.split()[0])

    # 2. 入口脚本存在
    record("scripts/mermaid_render.py 存在", os.path.isfile(SCRIPT), SCRIPT)

    # 3. node 可用
    code, out = run_cmd("node --version")
    record("node 可用", code == 0, first_line(out))

    # 4. npx 可用
    code, out = run_cmd("npx --version")
    record("npx 可用", code == 0, first_line(out))

    # 5. mmdc 可用（本地已安装，不联网）
    # 注意：mermaid_render.py 内部把 npx 的工作目录固定为【脚本自身所在目录】，
    # 因此 mmdc 必须在 scripts/ 的上级找到 node_modules，或在项目根目录已安装。
    code, out = run_cmd("npx --no-install mmdc --version", cwd=SKILL_DIR)
    mmdc_ok = code == 0
    detail = first_line(out) or "未找到本地 mmdc"
    if not mmdc_ok:
        detail = "脚本运行于 %s，该处无 node_modules；请在项目根目录执行 npm install @mermaid-js/mermaid-cli" % SKILL_DIR
    record("mmdc 可用 (npx --no-install mmdc --version @ scripts 上级)", mmdc_ok, detail)

    # 6-9. 三份示例端到端渲染
    cases = [
        ("example_flow.mmd (.mmd 源码模式)", "example_flow.mmd"),
        ("example_flow.md (Markdown 模式)", "example_flow.md"),
        ("example_text.txt (纯文本模式)", "example_text.txt"),
    ]
    tmpdir = tempfile.mkdtemp(prefix="mmd_selftest_")
    try:
        for title, fname in cases:
            src = os.path.join(EXAMPLES, fname)
            if not os.path.isfile(src):
                record(title, False, "示例不存在: %s" % src)
                continue
            out_png = os.path.join(tmpdir, os.path.splitext(fname)[0] + ".png")
            cmd = '"%s" "%s" --input "%s" --output "%s"' % (
                sys.executable, SCRIPT, src, out_png)
            code, out = run_cmd(cmd)
            if code != 0 or not os.path.isfile(out_png):
                record(title, False, "渲染失败: %s" % (first_line(out) or "无产物"))
                continue
            size = os.path.getsize(out_png)
            record(title, size > MIN_PNG_BYTES,
                   "产物 %d B (阈值 %d B)" % (size, MIN_PNG_BYTES))

            # 同名 .mmd 源码只在进行自动生成时才产出：
            # .mmd 输入本身即源码、.md 输入已含代码块，二者均不重复生成。
            out_mmd = os.path.splitext(out_png)[0] + ".mmd"
            if "--text" in cmd or fname.lower().endswith(".txt"):
                record("  " + fname + " 附带 .mmd 源码", os.path.isfile(out_mmd),
                       "%d B" % os.path.getsize(out_mmd) if os.path.isfile(out_mmd) else "缺失")
            else:
                print("[信息] %s 为源码/代码块输入，按设计不额外生成 .mmd" % fname)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    # 10. 脚本完整性提示（可选校验）
    with open(SCRIPT, "rb") as fh:
        digest = hashlib.md5(fh.read()).hexdigest()
    print("[信息] scripts/mermaid_render.py MD5 = %s" % digest)

    total = len(results)
    passed = sum(1 for _, ok, _ in results if ok)
    failed = total - passed

    print("=" * 62)
    print("汇总: 共 %d 项，通过 %d，失败 %d" % (total, passed, failed))
    if failed:
        print("失败明细:")
        for name, ok, detail in results:
            if not ok:
                print("  - %s  %s" % (name, detail))
    print("结果: %s" % ("全部通过" if failed == 0 else "存在失败项"))
    if failed:
        print("")
        print("排错提示：")
        print("  1) 渲染类失败绝大多数是 mmdc 找不到。本脚本把 npx 的工作目录")
        print("     固定为脚本自身所在目录（%s），" % os.path.dirname(SCRIPT))
        print("     因此需要在该目录的上一级存在 node_modules。")
        print("  2) 安装 Skill 后可把 node_modules 建在 <configDir>/skills 下，")
        print("     或直接把 demo 工程根目录的 node_modules 软链过来：")
        print("       mklink /J \"<skill_dir>\\node_modules\" \"<project_root>\\node_modules\"")
        print("  3) npm install @mermaid-js/mermaid-cli  安装渲染引擎后重跑本脚本。")
        print("  4) 中文显示为方块时，请安装 Microsoft YaHei / SimHei 字体。")
    print("=" * 62)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
