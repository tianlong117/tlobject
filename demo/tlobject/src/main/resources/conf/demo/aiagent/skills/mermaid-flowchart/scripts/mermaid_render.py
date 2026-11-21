#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""mermaid_render.py —— 把 Mermaid 代码 / Markdown / 纯文本渲染成流程图图片。

支持三种输入来源：

  模式一  .mmd 文件      -> 直接读取 Mermaid 代码渲染
  模式二  .md  文件      -> 提取其中全部 ```mermaid 代码块渲染（多块输出加序号）
                            若 md 中不含 mermaid 代码块，则自动降级为模式三，
                            从 md 的标题 / 列表正文结构生成 Mermaid
  模式三  .txt 文件或 --text -> 纯文本按规则自动生成 Mermaid 流程图

自动生成 Mermaid 完全基于规则，不联网、不调用 LLM：
  * 识别标题行（'#'、'一、二、'、'1.'）、列表项（'-' '*' '•'、'1)' 'a)'）、普通句子
  * 关键字映射节点形状：
      判定/是否/判断/校验/检查/条件      -> 菱形  { }
      输入/开始/接收                     -> 起点  ([ ])
      输出/结束/返回/完成                -> 终点  ([ ])
      其他                               -> 矩形  [ ]
  * 文本转义：括号、引号、冒号、分号等 mermaid 特殊字符替换为空格；
    超长文本自动按 <br/> 折行
  * 节点串成 flowchart TD 线性链：A --> B --> C
  * 出现 失败/异常/错误/超时/降级 等分支词时，额外生成一个分支节点，
    边标签使用该关键词

渲染优先走 mmdc（@mermaid-js/mermaid-cli），在项目根目录下执行
``npx --no-install mmdc``，通过临时 puppeteer 配置指定中文字体，保证中文正常显示。

用法示例
--------
  # 帮助
  python mermaid_render.py --help

  # 模式一：直接渲染 .mmd
  python mermaid_render.py --input Agent框架流程图.mmd --output out/图1.png

  # 模式二：从 Markdown 提取 mermaid 代码块（多块会输出 out_1.png / out_2.png）
  python mermaid_render.py --input Agent框架方案.md --output out/方案.png --verbose

  # 模式三：直接给一段文字
  python mermaid_render.py --text "用户输入请求
  - 校验参数是否合法
  - 调用模型推理
  - 返回结果" --output out/流程.png

  # 指定格式与清晰度、主题
  python mermaid_render.py --input a.md --format svg --scale 3 --theme forest

生成的 .mmd 会与输出文件同目录同名保存，便于查看与二次编辑。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

# ---------------------------------------------------------------------------
# 常量定义
# ---------------------------------------------------------------------------

#: 脚本所在目录，默认为项目根目录（含 node_modules）
PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))

#: 中文字体栈，用于 puppeteer 配置与主题变量
CN_FONT = "Microsoft YaHei, SimHei, sans-serif"

#: 判定类关键字 -> 菱形节点
DECISION_WORDS = ("判定", "是否", "判断", "校验", "检查", "条件")

#: 起点类关键字 -> 圆角节点
START_WORDS = ("输入", "开始", "接收")

#: 终点类关键字 -> 圆角节点
END_WORDS = ("输出", "结束", "返回", "完成")

#: 分支类关键字 -> 额外生成分支节点与边标签
BRANCH_WORDS = ("失败", "异常", "错误", "超时", "降级")

#: 支持的输出格式
FORMATS = ("png", "svg", "pdf")

#: 支持的 mermaid 主题
THEMES = ("default", "dark", "forest", "neutral")

#: 自动生成时单行最大字符数，超过则折行
MAX_LABEL_LEN = 20

#: 标题行匹配：'#' 开头 / '一、二、' / '1.' 数字序号
RE_HEADING_HASH = re.compile(r"^\s{0,3}#{1,6}\s+(.*\S)\s*$")
RE_HEADING_CN = re.compile(r"^\s*([一二三四五六七八九十百零〇]+)\s*[、.．]\s*(.+?)\s*$")
RE_HEADING_NUM = re.compile(r"^\s*(\d{1,2})\s*[.、．]\s+(.+?)\s*$")

#: 列表项匹配：'-' '*' '•' / '1)' 'a)' / '1.' 后无空格也兼容
RE_LIST_BULLET = re.compile(r"^\s*[-*•·+]\s+(.+?)\s*$")
RE_LIST_PAREN = re.compile(r"^\s*(?:\d{1,2}|[a-zA-Z])\s*[)\uff09]\s*(.+?)\s*$")

#: mermaid 代码块围栏
RE_MD_FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})\s*([^\s`]*)\s*(.*)$")

#: mermaid 特殊字符（在标签中不安全），统一替换为空格
RE_MERMAID_SPECIAL = re.compile(r"[\[\]{}()<>\"'`:;|\\#%&*=+~^$@!?,，。：；（）【】“”‘’、\n\r\t]")


# ---------------------------------------------------------------------------
# 日志辅助
# ---------------------------------------------------------------------------

class Logger:
    """极简日志器，--verbose 控制详细输出。"""

    def __init__(self, verbose: bool = False) -> None:
        self.verbose = verbose

    def info(self, msg: str) -> None:
        """常规信息，始终输出。"""
        print(f"[INFO] {msg}", file=sys.stderr)

    def debug(self, msg: str) -> None:
        """调试信息，仅 --verbose 时输出。"""
        if self.verbose:
            print(f"[DEBUG] {msg}", file=sys.stderr)

    def warn(self, msg: str) -> None:
        """警告信息，始终输出。"""
        print(f"[WARN] {msg}", file=sys.stderr)


class RenderError(Exception):
    """脚本统一的业务异常，便于统一捕获并给出友好提示。"""


# ---------------------------------------------------------------------------
# 文本 -> Mermaid 自动生成
# ---------------------------------------------------------------------------

def escape_label(text: str) -> str:
    """清洗节点文本，去掉 mermaid 特殊字符并做折行。

    Args:
        text: 原始文本。

    Returns:
        安全的节点标签字符串，超长自动插入 ``<br/>``。
    """
    if not text:
        return "步骤"
    cleaned = RE_MERMAID_SPECIAL.sub(" ", text)
    # 合并连续空白
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    if not cleaned:
        return "步骤"

    # 兼容中英文长度：中文按 1 个宽度计，英文按 0.5 计
    words = cleaned.split(" ")
    lines, cur = [], ""
    for w in words:
        candidate = f"{cur} {w}".strip() if cur else w
        if _display_len(candidate) <= MAX_LABEL_LEN:
            cur = candidate
        else:
            if cur:
                lines.append(cur)
            # 单个词本身超长，硬切
            while _display_len(w) > MAX_LABEL_LEN:
                lines.append(w[:MAX_LABEL_LEN])
                w = w[MAX_LABEL_LEN:]
            cur = w
    if cur:
        lines.append(cur)
    if not lines:
        lines = [cleaned[:MAX_LABEL_LEN]]
    return "<br/>".join(lines)


def _display_len(text: str) -> int:
    """按显示宽度估算字符串长度（全角 1，半角 0.5，四舍五入）。"""
    width = 0.0
    for ch in text:
        width += 1.0 if ord(ch) > 0x2E80 else 0.5
    return int(round(width))


def node_shape(label_raw: str) -> tuple:
    """根据关键字决定节点包裹符号。

    Args:
        label_raw: 未转义的原始行文本。

    Returns:
        (左符号, 右符号) 二元组，例如 ``('{', '}')``。
    """
    # 判定优先，避免"校验输入"被判成起点
    if any(w in label_raw for w in DECISION_WORDS):
        return ("{", "}")
    if any(w in label_raw for w in START_WORDS):
        return ("([", "])")
    if any(w in label_raw for w in END_WORDS):
        return ("([", "])")
    return ("[", "]")


def classify_line(raw: str) -> tuple:
    """识别一行文本的结构类型。

    Args:
        raw: 原始行。

    Returns:
        (kind, content)；kind 取值 heading / list / sentence，无法识别返回 ('', '')。
    """
    line = raw.strip()
    if not line:
        return ("", "")
    # 表格行、分隔线、代码围栏等跳过
    if line.startswith("|") or line.startswith("---") or line.startswith("```"):
        return ("", "")

    m = RE_HEADING_HASH.match(raw)
    if m:
        return ("heading", m.group(1))
    m = RE_HEADING_CN.match(raw)
    if m:
        return ("heading", m.group(2))
    m = RE_HEADING_NUM.match(raw)
    if m:
        return ("heading", m.group(2))

    m = RE_LIST_BULLET.match(raw)
    if m:
        return ("list", m.group(1))
    m = RE_LIST_PAREN.match(raw)
    if m:
        return ("list", m.group(1))

    # 纯句子：至少含 2 个字符
    if len(line) >= 2:
        return ("sentence", line)
    return ("", "")


def text_to_mermaid(text: str, title: str = "自动生成流程图", logger: Logger | None = None) -> str:
    """把纯文本按规则转换成 Mermaid flowchart 代码。

    Args:
        text: 输入的多行文本。
        title: 流程图标题（作为注释写入 mmd）。
        logger: 日志器。

    Returns:
        完整的 mermaid 代码字符串。
    """
    log = logger or Logger()
    if not text or not text.strip():
        raise RenderError("输入文本为空，无法生成流程图。")

    items = []  # [(kind, content)]

    # 规范化：部分调用渠道（命令行转义、跨平台剪贴板）会把换行传成字面量
    # "\n" / "\r\n"，这里统一还原为真实换行，避免整段文本被当成单行。
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    normalized = re.sub(r"\\r\\n|\\n|\\r", "\n", normalized)

    raw_lines = normalized.splitlines()
    # 兜底：若全文只有一行却很长，按中文分号/句号再切分
    if len([ln for ln in raw_lines if ln.strip()]) == 1:
        only = raw_lines[0] if raw_lines else ""
        if _display_len(only) > MAX_LABEL_LEN:
            parts = re.split(r"[；;。]", only)
            raw_lines = [p for p in (x.strip() for x in parts) if p]
            log.debug(f"单行文本过长，按分号/句号切分为 {len(raw_lines)} 段")

    for raw in raw_lines:
        kind, content = classify_line(raw)
        if not kind:
            continue
        items.append((kind, content))

    if not items:
        raise RenderError("未能从文本中识别出任何有效结构行，请检查输入内容。")

    log.debug(f"识别到 {len(items)} 行有内容结构")

    nodes = []      # [(node_id, kind, label, raw)]
    branches = []   # [(from_id, label, branch_id)]
    for idx, (kind, content) in enumerate(items):
        node_id = f"N{idx}"
        label = escape_label(content)
        nodes.append((node_id, kind, label, content))

    # 生成线性主干
    lines = ["flowchart TD"]
    lines.append(f"    %% {title}")
    for i, (node_id, kind, label, raw) in enumerate(nodes):
        left, right = node_shape(raw)
        lines.append(f"    {node_id}{left}{label}{right}")
        if i > 0:
            prev_id = nodes[i - 1][0]
            lines.append(f"    {prev_id} --> {node_id}")

    # 分支词处理：为命中分支词的节点补一个分支节点
    for i, (node_id, kind, label, raw) in enumerate(nodes):
        hit = next((w for w in BRANCH_WORDS if w in raw), None)
        if not hit:
            continue
        branch_id = f"{node_id}_B"
        branch_label = escape_label(f"{hit}处理")
        # 分支终点通常也是出口，用圆角终点样式
        lines.append(f"    {branch_id}([{branch_label}])")
        lines.append(f"    {node_id} -->|{hit}| {branch_id}")
        branches.append((node_id, hit, branch_id))
        log.debug(f"节点 {node_id} 命中分支词「{hit}」，已生成分支节点 {branch_id}")

    mermaid_code = "\n".join(lines) + "\n"
    log.debug("自动生成的 Mermaid 代码：\n" + mermaid_code)
    return mermaid_code


# ---------------------------------------------------------------------------
# 输入解析
# ---------------------------------------------------------------------------

def extract_mermaid_blocks(md_text: str) -> list:
    """从 Markdown 文本中提取所有 ```mermaid 代码块。

    Args:
        md_text: Markdown 全文。

    Returns:
        mermaid 代码字符串列表，按出现顺序排列；无匹配返回空列表。
    """
    blocks = []
    lines = md_text.splitlines()
    i = 0
    while i < len(lines):
        m = RE_MD_FENCE.match(lines[i])
        if not m:
            i += 1
            continue
        fence = m.group(1)
        lang = (m.group(2) or "").strip().lower()
        # 语言标记后可能跟额外信息，取第一个 token
        lang = lang.split(",")[0]
        marker = fence[0]  # ` 或 ~
        # 收集块内容
        body = []
        j = i + 1
        closed = False
        while j < len(lines):
            close_m = RE_MD_FENCE.match(lines[j])
            if close_m and close_m.group(1)[0] == marker and len(close_m.group(1)) >= len(fence) \
                    and not (close_m.group(2) or "").strip():
                closed = True
                break
            body.append(lines[j])
            j += 1
        if closed and lang in ("mermaid", "mmd"):
            blocks.append("\n".join(body).strip() + "\n")
        i = j + 1
    return blocks


def strip_markdown_noise(md_text: str) -> str:
    """去除 Markdown 中的非正文噪声，供降级生成使用。

    移除代码块、表格行、图片/链接语法，保留标题与列表结构。

    Args:
        md_text: Markdown 全文。

    Returns:
        清洗后的文本。
    """
    out = []
    in_fence = False
    fence_marker = ""
    for raw in md_text.splitlines():
        m = RE_MD_FENCE.match(raw)
        if m and not in_fence:
            in_fence = True
            fence_marker = m.group(1)[0]
            continue
        if in_fence:
            if m and m.group(1)[0] == fence_marker:
                in_fence = False
            continue
        line = raw.rstrip()
        if line.strip().startswith("|"):
            continue
        # 去掉图片与链接的包裹，保留文字
        line = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", line)
        # 去掉粗体/斜体标记
        line = re.sub(r"(\*\*|__|\*|_)(?=\S)", "", line)
        out.append(line)
    return "\n".join(out)


def build_default_output(input_path: str | None, fmt: str) -> str:
    """按输入路径推导默认输出路径。

    Args:
        input_path: 输入文件路径，可为 None（--text 模式）。
        fmt: 输出格式。

    Returns:
        输出文件绝对路径。
    """
    if not input_path:
        base_dir = os.getcwd()
        stem = "mermaid_output"
    else:
        base_dir = os.path.dirname(os.path.abspath(input_path)) or os.getcwd()
        stem = os.path.splitext(os.path.basename(input_path))[0]
    return os.path.join(base_dir, f"{stem}.{fmt}")


# ---------------------------------------------------------------------------
# 渲染
# ---------------------------------------------------------------------------

def find_project_root(start: str) -> str:
    """向上查找包含 node_modules/@mermaid-js/mermaid-cli 的目录。

    Args:
        start: 起始目录。

    Returns:
        项目根目录；找不到则返回脚本所在目录。
    """
    cur = os.path.abspath(start)
    while True:
        candidate = os.path.join(cur, "node_modules", "@mermaid-js", "mermaid-cli")
        if os.path.isdir(candidate):
            return cur
        parent = os.path.dirname(cur)
        if parent == cur:
            break
        cur = parent
    return PROJECT_ROOT


def _write_cfg(tmp_dir: str, theme: str) -> str:
    """生成临时 puppeteer/mermaid 配置文件。

    Args:
        tmp_dir: 临时目录。
        theme: mermaid 主题名。

    Returns:
        配置文件路径。
    """
    cfg = {"theme": theme, "themeVariables": {"fontFamily": CN_FONT, "fontSize": "18px"}}
    cfg_path = os.path.join(tmp_dir, "puppeteer_cfg.json")
    with open(cfg_path, "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2)
    return cfg_path


def resolve_npx() -> str | None:
    """定位可执行的 npx。

    Windows 下 ``shutil.which("npx")`` 返回的是 ``npx.CMD``，而 ``subprocess``
    以列表形式（shell=False）调用时无法直接执行 .CMD/.BAT，会抛
    FileNotFoundError。因此这里优先返回带扩展名的真实可执行文件路径。

    Returns:
        npx 可执行文件路径；找不到返回 None。
    """
    found = shutil.which("npx")
    if not found:
        # 兜底：手工拼常见位置
        for cand in ("npx.cmd", "npx.exe", "npx"):
            p = shutil.which(cand)
            if p:
                found = p
                break
    if not found:
        return None
    # 若 which 返回了无扩展名的路径但在 Windows 上，尝试补全 .cmd
    if os.name == "nt" and not os.path.exists(found):
        for ext in (".cmd", ".exe", ".bat"):
            if os.path.exists(found + ext):
                return found + ext
    return found


def _run_npx(npx_args: list, timeout: int, logger: Logger | None = None) -> subprocess.CompletedProcess:
    """跨平台执行 npx 命令。

    Args:
        npx_args: 传给 npx 的参数列表，如 ``["--no-install", "mmdc", ...]``。
        timeout: 超时秒数。
        logger: 可选日志器。

    Returns:
        CompletedProcess 实例。

    Raises:
        RenderError: npx 不可用或进程无法启动。
    """
    npx = resolve_npx()
    if not npx:
        raise RenderError("系统 PATH 中找不到 npx，请先安装 Node.js。")
    cmd = [npx] + npx_args
    if logger:
        logger.debug("执行命令：" + " ".join(cmd))
    try:
        return subprocess.run(
            cmd, cwd=PROJECT_ROOT, capture_output=True, timeout=timeout,
            shell=False, text=True, encoding="utf-8", errors="replace",
        )
    except FileNotFoundError:
        # 极端情况：带扩展名仍无法直接执行，回退到 shell 方式
        quoted = subprocess.list2cmdline(cmd)
        if logger:
            logger.debug("直接执行失败，回退 shell 方式：" + quoted)
        return subprocess.run(
            quoted, cwd=PROJECT_ROOT, capture_output=True, timeout=timeout,
            shell=True, text=True, encoding="utf-8", errors="replace",
        )


def _check_mmdc(logger: Logger | None = None) -> bool:
    """检查 mmdc 是否可用。

    Args:
        logger: 可选日志器，用于输出实际探测到的版本。

    Returns:
        True 表示可调用。
    """
    try:
        proc = _run_npx(["--no-install", "mmdc", "--version"], timeout=120, logger=logger)
    except (RenderError, OSError, subprocess.SubprocessError):
        return False
    if proc.returncode == 0 and logger:
        logger.debug(f"探测到 mmdc 版本：{(proc.stdout or '').strip()}")
    return proc.returncode == 0


def render_with_mmdc(mmd_path: str, out_path: str, fmt: str, scale: int,
                     theme: str, logger: Logger) -> None:
    """调用 mmdc 把 .mmd 渲染成图片。

    Args:
        mmd_path: 输入的 .mmd 文件。
        out_path: 输出文件路径。
        fmt: 输出格式，png/svg/pdf。
        scale: 缩放因子，传给 -s。
        theme: mermaid 主题。
        logger: 日志器。

    Raises:
        RenderError: mmdc 不可用、执行失败或未产出文件。
    """
    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)

    tmp_dir = tempfile.mkdtemp(prefix="mmdc_cfg_")
    try:
        cfg_path = _write_cfg(tmp_dir, theme)
        npx_args = [
            "--no-install", "mmdc",
            "-i", os.path.abspath(mmd_path),
            "-o", os.path.abspath(out_path),
            "-b", "white",
            "-s", str(scale),
            "-c", cfg_path,
        ]
        try:
            proc = _run_npx(npx_args, timeout=600, logger=logger)
        except subprocess.TimeoutExpired:
            raise RenderError("mmdc 渲染超时（>600 秒），请检查输入是否过大。")
        except OSError as exc:
            raise RenderError(f"无法启动 mmdc 进程：{exc}")

        if logger.verbose and proc.stderr:
            logger.debug("mmdc stderr：" + proc.stderr.strip()[:2000])

        if proc.returncode != 0:
            tail = (proc.stderr or proc.stdout or "").strip()
            raise RenderError(f"mmdc 渲染失败（退出码 {proc.returncode}）：{tail[-1200:]}")

        if not os.path.exists(out_path) or os.path.getsize(out_path) == 0:
            raise RenderError(f"mmdc 执行完成但未生成有效文件：{out_path}")
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def read_text_file(path: str) -> str:
    """读取文本文件，自动尝试多种编码。

    Args:
        path: 文件路径。

    Returns:
        文件内容。

    Raises:
        RenderError: 文件不存在或无法读取。
    """
    if not os.path.exists(path):
        raise RenderError(f"输入文件不存在：{path}")
    if not os.path.isfile(path):
        raise RenderError(f"输入路径不是文件：{path}")
    for enc in ("utf-8-sig", "utf-8", "gbk", "gb18030", "latin-1"):
        try:
            with open(path, "r", encoding=enc) as fh:
                return fh.read()
        except UnicodeDecodeError:
            continue
        except OSError as exc:
            raise RenderError(f"读取文件失败：{path}，原因：{exc}")
    raise RenderError(f"无法识别文件编码（尝试 utf-8/gbk/gb18030 均失败）：{path}")


def save_mmd(mermaid_code: str, out_path: str, logger: Logger) -> str:
    """把 mermaid 代码保存为与输出同名的 .mmd 文件。

    Args:
        mermaid_code: mermaid 代码。
        out_path: 输出图片路径（用于推导同名的 mmd 路径）。
        logger: 日志器。

    Returns:
        保存的 mmd 路径。
    """
    stem = os.path.splitext(os.path.abspath(out_path))[0]
    mmd_path = stem + ".mmd"
    os.makedirs(os.path.dirname(mmd_path) or ".", exist_ok=True)
    try:
        with open(mmd_path, "w", encoding="utf-8") as fh:
            fh.write(mermaid_code)
    except OSError as exc:
        raise RenderError(f"写入 .mmd 文件失败：{mmd_path}，原因：{exc}")
    logger.info(f"已生成 Mermaid 源码：{mmd_path}")
    return mmd_path


def render_one(mermaid_code: str, out_path: str, args, logger: Logger,
               save_source: bool = False) -> list:
    """渲染单个 mermaid 代码并返回产出文件列表。

    Args:
        mermaid_code: mermaid 代码。
        out_path: 输出图片路径。
        args: argparse 命名空间。
        logger: 日志器。
        save_source: 是否额外保存 .mmd 源文件。

    Returns:
        实际产出的文件路径列表。
    """
    produced = []
    tmp_mmd = None
    try:
        if save_source:
            tmp_mmd = save_mmd(mermaid_code, out_path, logger)
            produced.append(tmp_mmd)
        else:
            fd, tmp_mmd = tempfile.mkstemp(suffix=".mmd", prefix="mmdc_in_")
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write(mermaid_code)
        render_with_mmdc(tmp_mmd, out_path, args.format, args.scale, args.theme, logger)
        produced.append(out_path)
    finally:
        if not save_source and tmp_mmd and os.path.exists(tmp_mmd):
            try:
                os.remove(tmp_mmd)
            except OSError:
                pass
    return produced


def run(args, logger: Logger) -> int:
    """执行主流程。

    Args:
        args: argparse 命名空间。
        logger: 日志器。

    Returns:
        进程退出码。
    """
    if not _check_mmdc(logger):
        raise RenderError(
            "未检测到可用的 mmdc（@mermaid-js/mermaid-cli）。\n"
            f"请在项目根目录 {PROJECT_ROOT} 下执行：npm install @mermaid-js/mermaid-cli"
        )

    src_path = os.path.abspath(args.input) if args.input else None
    out_path = os.path.abspath(args.output) if args.output else build_default_output(src_path, args.format)

    all_produced = []

    # ---- --text 模式 ----
    if args.text is not None:
        logger.info("模式三：从 --text 文本自动生成 Mermaid")
        code = text_to_mermaid(args.text, title="由 --text 文本自动生成", logger=logger)
        print("\n===== 自动生成的 Mermaid 代码 =====\n" + code, file=sys.stdout)
        all_produced += render_one(code, out_path, args, logger, save_source=True)
        _report(all_produced, logger)
        return 0

    if not src_path:
        raise RenderError("必须指定 --input 或 --text 之一。使用 --help 查看用法。")

    ext = os.path.splitext(src_path)[1].lower()

    # ---- 模式一：.mmd ----
    if ext in (".mmd", ".mermaid"):
        logger.info(f"模式一：直接渲染 Mermaid 文件 {src_path}")
        code = read_text_file(src_path)
        if not code.strip():
            raise RenderError(f"Mermaid 文件内容为空：{src_path}")
        all_produced += render_one(code, out_path, args, logger, save_source=False)
        _report(all_produced, logger)
        return 0

    # ---- 模式二：.md ----
    if ext in (".md", ".markdown"):
        text = read_text_file(src_path)
        blocks = extract_mermaid_blocks(text)
        logger.info(f"模式二：从 Markdown 提取到 {len(blocks)} 个 mermaid 代码块")
        if blocks:
            for idx, code in enumerate(blocks, start=1):
                if len(blocks) == 1:
                    per_out = out_path
                else:
                    stem, fext = os.path.splitext(out_path)
                    per_out = f"{stem}_{idx}{fext}"
                logger.info(f"渲染第 {idx}/{len(blocks)} 个代码块 -> {per_out}")
                all_produced += render_one(code, per_out, args, logger, save_source=False)
            _report(all_produced, logger)
            return 0
        # 降级
        logger.warn("Markdown 中未找到 mermaid 代码块，降级为按正文结构自动生成")
        cleaned = strip_markdown_noise(text)
        code = text_to_mermaid(cleaned, title="由 Markdown 正文结构自动生成", logger=logger)
        print("\n===== 自动生成的 Mermaid 代码 =====\n" + code, file=sys.stdout)
        all_produced += render_one(code, out_path, args, logger, save_source=True)
        _report(all_produced, logger)
        return 0

    # ---- 模式三：.txt 或其他文本 ----
    if ext in (".txt", ".text", "", ".texts"):
        logger.info(f"模式三：从纯文本 {src_path} 自动生成 Mermaid")
        text = read_text_file(src_path)
        code = text_to_mermaid(text, title=f"由文本 {os.path.basename(src_path)} 自动生成", logger=logger)
        print("\n===== 自动生成的 Mermaid 代码 =====\n" + code, file=sys.stdout)
        all_produced += render_one(code, out_path, args, logger, save_source=True)
        _report(all_produced, logger)
        return 0

    raise RenderError(f"不支持的文件类型：{ext}（仅支持 .mmd / .md / .txt，或使用 --text）")


def _report(files: list, logger: Logger) -> None:
    """打印产出结果概要。"""
    logger.info("渲染完成，产出文件：")
    for f in files:
        try:
            size = os.path.getsize(f)
            print(f"  - {f}  ({size} bytes, {size / 1024:.1f} KB)", file=sys.stderr)
        except OSError:
            print(f"  - {f}  (读取大小失败)", file=sys.stderr)


def build_parser() -> argparse.ArgumentParser:
    """构建命令行参数解析器。

    Returns:
        ArgumentParser 实例。
    """
    parser = argparse.ArgumentParser(
        prog="mermaid_render.py",
        description="把 Mermaid 代码 / Markdown / 纯文本渲染成流程图图片（png/svg/pdf）。",
        epilog=(
            "示例：\n"
            "  python mermaid_render.py --input a.mmd -o a.png\n"
            "  python mermaid_render.py --input doc.md -o doc.png --verbose\n"
            "  python mermaid_render.py --text \"用户输入\\n- 校验是否合法\\n- 返回结果\" -o t.png\n"
            "  python mermaid_render.py --input doc.md --format svg --scale 3 --theme forest"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--input", metavar="PATH",
                        help="输入文件路径（.mmd / .md / .txt），与 --text 二选一")
    parser.add_argument("--text", metavar="STR", default=None,
                        help="直接传入一段文本，自动生成流程图并渲染")
    parser.add_argument("--output", metavar="PATH", default=None,
                        help="输出文件路径，默认与输入同目录同名 .png")
    parser.add_argument("--format", choices=FORMATS, default="png",
                        help="输出格式，默认 png")
    parser.add_argument("--scale", type=int, default=2,
                        help="渲染清晰度（传给 mmdc -s），默认 2")
    parser.add_argument("--theme", choices=THEMES, default="default",
                        help="Mermaid 主题，默认 default")
    parser.add_argument("--verbose", action="store_true",
                        help="打印详细过程")
    return parser


def main(argv: list | None = None) -> int:
    """脚本入口。

    Args:
        argv: 参数列表，默认取 sys.argv[1:]。

    Returns:
        退出码，0 表示成功。
    """
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.input and args.text is not None:
        parser.error("--input 与 --text 只能二选一，请勿同时指定。")
    if not args.input and args.text is None:
        parser.error("必须指定 --input 或 --text 之一。使用 --help 查看用法。")
    if args.scale < 1 or args.scale > 10:
        parser.error("--scale 取值范围为 1-10。")

    # 若 --output 扩展名与 --format 冲突，以 --output 扩展名为准
    if args.output:
        out_ext = os.path.splitext(args.output)[1].lower().lstrip(".")
        if out_ext in FORMATS and out_ext != args.format:
            print(f"[INFO] --output 扩展名为 .{out_ext}，已自动切换输出格式为 {out_ext}",
                  file=sys.stderr)
            args.format = out_ext

    logger = Logger(verbose=args.verbose)
    try:
        return run(args, logger)
    except RenderError as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("[ERROR] 已被用户中断。", file=sys.stderr)
        return 130
    except Exception as exc:  # noqa: BLE001 - 兜底，避免裸 traceback
        print(f"[ERROR] 未预期的错误：{type(exc).__name__}: {exc}", file=sys.stderr)
        if args.verbose:
            import traceback
            traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
