# webui 右侧面板改造 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** webui 右侧命令面板默认隐藏（顶栏「⚙ 设置/调试」开合）+ 左边缘拖拽调宽（280px~70vw，宽度记忆，双击复位 420px）。

**Architecture:** 纯前端改动，3 个文件：`chat.html` 加按钮/把手/初始隐藏类；`style.css` 把面板宽度从 `width` 改为可被 JS 改写的 `flex-basis` + 把手/拖动态样式；`app.js` 加面板开关/宽度夹取/拖拽逻辑，并把面板接进现有 ESC 关闭链。零 Java、零后端接口改动。

**Tech Stack:** 原生 HTML/CSS/JS（该 webui 零依赖）。验证用 Python Playwright（已装，`python -c "from playwright.sync_api import sync_playwright"` 通过）驱动真实 Chromium，配 `python -m http.server` 静态服务——本改动全部是客户端逻辑，静态服务即可完整验证，不需要起后端。

**Spec:** `docs/superpowers/specs/2026-10-07-webui-right-panel-design.md`（设计已确认；本计划对 spec 的一处实现细化：拖拽用 `mousedown/mousemove/mouseup` 而非 pointer capture——鼠标事件的 `preventDefault` 不影响后续 `dblclick`，实现更直白且桌面 webui 无需触摸支持）

**验证脚本说明:** `smoke/` 是仓库的未跟踪草稿区（内含 freeze_watch.sh 等），本计划的验证脚本放 `smoke/webui_panel_check.py`，**不提交**（与目录约定一致）；被改动并提交的是 3 个前端源文件。

---

### Task 1: 验证脚本（TDD 的"失败测试"先行）

**Files:**
- Create: `smoke/webui_panel_check.py`（不提交）

- [ ] **Step 1: 写验证脚本**

创建 `smoke/webui_panel_check.py`，完整内容：

```python
# -*- coding: utf-8 -*-
"""webui 右侧面板改造 验证脚本
用法（Git Bash）: PYTHONUTF8=1 python smoke/webui_panel_check.py
原理：python -m http.server 静态服务 resources 目录（/webui/* 可解析），
Playwright 驱动 Chromium 逐项断言。本改动全为客户端逻辑，无需后端、无需登录
（未登录时 #chatView 带 hidden——测试前用 JS 强制显示即可）。
"""
import subprocess, sys, time
from playwright.sync_api import sync_playwright

ROOT = r"D:\tlobjectapp\tlobject"
SERVE = ROOT + r"\aiagent\webui\src\main\resources"   # 让 /webui/style.css 等绝对路径可解析
PORT = 18321
VW, VH = 1600, 900                                    # 视口宽（70vw = 1120）
SHOT = ROOT + r"\smoke"                               # 截图落脚

def main():
    srv = subprocess.Popen([sys.executable, "-m", "http.server", str(PORT), "--directory", SERVE],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    results = []
    def check(name, cond, extra=""):
        results.append(bool(cond))
        print(("PASS " if cond else "FAIL ") + name + ((" | " + extra) if extra else ""))

    try:
        time.sleep(1.2)
        with sync_playwright() as p:
            b = p.chromium.launch()
            pg = b.new_page(viewport={'width': VW, 'height': VH})
            pg.goto(f"http://localhost:{PORT}/webui/chat.html")
            pg.wait_for_timeout(400)

            def ensure_chat():
                """未登录时 #chatView 隐藏：强制显示（顺带收起登录框）。
                每次交互前都调一次——initSession() 的失败回调可能再次切回登录视图。"""
                pg.evaluate("document.querySelector('#loginView').classList.add('hidden');"
                            "document.querySelector('#chatView').classList.remove('hidden')")
            ensure_chat()

            def panel_w():
                return pg.evaluate("document.querySelector('#panelPane').getBoundingClientRect().width")
            def chat_w():
                return pg.evaluate("document.querySelector('#chatPane').getBoundingClientRect().width")
            def is_hidden():
                return pg.evaluate("document.querySelector('#panelPane').classList.contains('hidden')")

            # ---- 1 默认隐藏 + 聊天满宽 ----
            check("1a 默认隐藏", is_hidden())
            check("1b 默认聊天满宽(1600-240)", abs(chat_w() - (VW - 240)) < 3, f"chat={chat_w()}")

            # ---- 2 点 ⚙ 打开 ----
            ensure_chat()   # 兜 initSession 失败回调再次切回登录视图
            pg.click('#panelBtn'); pg.wait_for_timeout(120)
            check("2a 点⚙后面板可见", not is_hidden())
            check("2b 打开宽度=420", abs(panel_w() - 420) < 2, f"w={panel_w()}")
            check("2c 按钮高亮(.on)", pg.evaluate("document.querySelector('#panelBtn').classList.contains('on')"))
            check("2d 聊天被推挤(1600-240-420)", abs(chat_w() - (VW - 240 - 420)) < 3, f"chat={chat_w()}")
            check("2e 把手光标 col-resize",
                  pg.evaluate("getComputedStyle(document.querySelector('#panelResizer')).cursor") == "col-resize")

            # ---- 3 拖把手加宽 150 ----
            box = pg.locator('#panelResizer').bounding_box()
            y = box['y'] + 200
            pg.mouse.move(box['x'] + box['width'] / 2, y)
            pg.mouse.down(); pg.mouse.move(box['x'] + box['width'] / 2 - 150, y, steps=8); pg.mouse.up()
            pg.wait_for_timeout(120)
            check("3a 拖动加宽≈150(w≈570)", abs(panel_w() - 570) < 8, f"w={panel_w()}")
            check("3b 宽度已写 localStorage",
                  abs(float(pg.evaluate("localStorage.getItem('tlweb_panelw')")) - panel_w()) < 2)

            # ---- 4 上限夹取（往左狂拖 → 夹到 70vw=1120）----
            box = pg.locator('#panelResizer').bounding_box()
            pg.mouse.move(box['x'] + box['width'] / 2, y)
            pg.mouse.down(); pg.mouse.move(2, y, steps=10); pg.mouse.up(); pg.wait_for_timeout(120)
            check("4 上限夹取=1120(70vw)", abs(panel_w() - VW * 0.7) < 3, f"w={panel_w()}")

            # ---- 5 下限夹取（往右狂拖 → 夹到 280）----
            box = pg.locator('#panelResizer').bounding_box()
            pg.mouse.move(box['x'] + box['width'] / 2, y)
            pg.mouse.down(); pg.mouse.move(VW - 3, y, steps=10); pg.mouse.up(); pg.wait_for_timeout(120)
            check("5 下限夹取=280", abs(panel_w() - 280) < 3, f"w={panel_w()}")
            check("5b 拖动后 body 无 dragging 残留",
                  not pg.evaluate("document.body.classList.contains('dragging')"))

            # ---- 6 双击复位 ----
            pg.dblclick('#panelResizer'); pg.wait_for_timeout(120)
            check("6 双击复位=420", abs(panel_w() - 420) < 2, f"w={panel_w()}")
            pg.screenshot(path=SHOT + r"\panel_open.png")

            # ---- 7 ESC 关闭（先把可能弹出的信息类浮层清掉，保证 ESC 命中的是面板）----
            pg.evaluate("document.querySelectorAll('.modal').forEach(m => m.classList.add('hidden'))")
            pg.keyboard.press('Escape'); pg.wait_for_timeout(120)
            check("7a ESC 关闭面板", is_hidden())
            check("7b 关后聊天满宽", abs(chat_w() - (VW - 240)) < 3, f"chat={chat_w()}")
            check("7c 关闭后按钮不亮", not pg.evaluate("document.querySelector('#panelBtn').classList.contains('on')"))
            pg.screenshot(path=SHOT + r"\panel_closed.png")

            # ---- 8 刷新：面板默认关、宽度保留 ----
            pg.evaluate("setPanelWidth(500)")
            pg.reload(); pg.wait_for_timeout(500)
            ensure_chat()
            check("8a 刷新后默认关闭", is_hidden())
            pg.click('#panelBtn'); pg.wait_for_timeout(120)
            check("8b 刷新后宽度保留=500", abs(panel_w() - 500) < 2, f"w={panel_w()}")

            # ---- 9 非法 localStorage 兜底 ----
            pg.evaluate("localStorage.setItem('tlweb_panelw', 'abc')")
            pg.reload(); pg.wait_for_timeout(500)
            ensure_chat()
            pg.click('#panelBtn'); pg.wait_for_timeout(120)
            check("9 非法值回退=420", abs(panel_w() - 420) < 2, f"w={panel_w()}")

            # ---- 10 面板内功能回归（四个 Tab 切换仍正常）----
            pg.click('#tabBar button[data-tab="tab-trace"]'); pg.wait_for_timeout(80)
            check("10 Tab 切换正常",
                  pg.evaluate("document.querySelector('#tab-trace').classList.contains('active')"))
            b.close()
    finally:
        srv.terminate()
        subprocess.run(["taskkill", "/F", "/PID", str(srv.pid), "/T"], capture_output=True)  # 防 Windows 孤儿

    fails = results.count(False)
    print(f"\n== {len(results) - fails}/{len(results)} 通过 ==")
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 跑脚本，确认"红"**

Run: `cd /d/tlobjectapp/tlobject && PYTHONUTF8=1 python smoke/webui_panel_check.py`
Expected: **大量 FAIL**，至少 1a（面板默认可见→隐藏断言失败）、2a（`#panelBtn` 不存在，`pg.click` 抛错或超时）——当前代码根本没有这些元素。
若 Chromium 未装：先 `python -m playwright install chromium`。

- [ ] **Step 3: 记录基线**

把输出的通过/失败数贴到本任务的实施记录里（后面每个 Task 跑完对比）。不提交（smoke/ 是未跟踪草稿区）。

---

### Task 2: chat.html + style.css（结构/样式就位）

**Files:**
- Modify: `aiagent/webui/src/main/resources/webui/chat.html:33-34,69-70`
- Modify: `aiagent/webui/src/main/resources/webui/style.css:132`

- [ ] **Step 1: chat.html — 顶栏加「⚙ 设置/调试」按钮**

在第 33 行 `#inboxBtn` 之后、`#logoutBtn` 之前插入一行：

```html
    <button id="panelBtn" class="ghost" title="设置/调试面板（Agent/Skill、MCP、评测、追踪）">⚙ 设置/调试</button>
```

- [ ] **Step 2: chat.html — 面板默认隐藏 + 拖拽把手**

把第 69-70 行：

```html
    <!-- 命令面板（调试/测试用；会话列表已移到左侧常驻栏） -->
    <aside id="panelPane">
```

改为：

```html
    <!-- 命令面板（调试/测试用，默认隐藏：顶栏「⚙ 设置/调试」开关；左边缘拖宽，双击复位 420） -->
    <aside id="panelPane" class="hidden">
      <div id="panelResizer" title="拖动调整宽度（双击复位）"></div>
```

（`</aside>` 及四个 tab-panel 的内容全部不动。）

- [ ] **Step 3: style.css — 面板宽度改 flex-basis + 把手/拖动态/按钮高亮样式**

把第 132 行：

```css
#panelPane { width: 420px; display: flex; flex-direction: column; min-height: 0; }
```

替换为：

```css
/* 命令面板：默认隐藏（顶栏 ⚙ 开关）；宽度走 flex-basis（JS 拖动改写），flex 不涨不缩防被压 */
#panelPane { flex: 0 0 420px; position: relative; display: flex; flex-direction: column; min-height: 0; }
/* 拖拽把手：贴左边缘（±居中在边界上），col-resize；悬停/拖动中高亮 */
#panelResizer { position: absolute; left: -2px; top: 0; bottom: 0; width: 5px; cursor: col-resize; z-index: 5; touch-action: none; }
#panelResizer:hover, body.dragging #panelResizer { background: #3b82f6; }
/* 拖动中：禁选中文本 + 全局 col-resize 光标 */
body.dragging { user-select: none; cursor: col-resize; }
/* 顶栏「设置/调试」按钮：面板打开时高亮 */
#panelBtn.on { color: #93c5fd; border-color: #3b82f6; }
```

- [ ] **Step 4: 跑脚本，确认"默认隐藏"转绿**

Run: `cd /d/tlobjectapp/tlobject && PYTHONUTF8=1 python smoke/webui_panel_check.py`
Expected: **1a/1b PASS**（面板默认隐藏、聊天满宽）；2a 起仍 FAIL（`togglePanel` 未接线，点按钮无反应）。

- [ ] **Step 5: Commit**

```bash
cd /d/tlobjectapp/tlobject
git add aiagent/webui/src/main/resources/webui/chat.html aiagent/webui/src/main/resources/webui/style.css
git commit -m "webui 右栏默认隐藏：顶栏「⚙ 设置/调试」按钮 + 面板加 hidden/拖拽把手 + 宽度改 flex-basis（可被 JS 改写）"
```

---

### Task 3: app.js — 面板开关 + 宽度记忆 + ESC 接入

**Files:**
- Modify: `aiagent/webui/src/main/resources/webui/app.js`（三处：新增函数段、bindEvents 接线、ESC 链）

- [ ] **Step 1: 新增面板函数段（插在 `restoreTaskCards()` 结束的 `}` 之后、"事件绑定"分隔注释之前，即当前 916 行处）**

```js
// ======================== 右侧设置/调试面板（默认隐藏 + 可拖宽） ========================
const PANEL_W_DEFAULT = 420;                                            // 默认宽度（双击把手复位到此值）
const PANEL_W_MIN = 280;                                                // 最小宽度（四个 Tab 标签排得下）
const PANEL_W_KEY = 'tlweb_panelw';                                     // localStorage 键
function panelWMax() { return Math.round(window.innerWidth * 0.7); }    // 上限动态算：保证聊天区不被压没

/** 宽度统一夹取：非法值（NaN/0/负）回退默认，越界夹到 [MIN, 70vw] */
function clampPanelWidth(w) {
  w = Number(w);
  if (!Number.isFinite(w) || w <= 0) w = PANEL_W_DEFAULT;
  return Math.max(PANEL_W_MIN, Math.min(w, panelWMax()));
}
/** 应用面板宽度：写 flex-basis + 记忆到 localStorage */
function setPanelWidth(w) {
  w = clampPanelWidth(w);
  $('#panelPane').style.flexBasis = w + 'px';
  localStorage.setItem(PANEL_W_KEY, String(w));
}
/** 顶栏 ⚙ 开关：切换 hidden + 按钮高亮；打开时应用记忆宽度（此刻夹取，兜住"窗口变小了"） */
function togglePanel() {
  const pane = $('#panelPane');
  const willOpen = pane.classList.contains('hidden');
  pane.classList.toggle('hidden', !willOpen);
  $('#panelBtn').classList.toggle('on', willOpen);
  if (willOpen) setPanelWidth(localStorage.getItem(PANEL_W_KEY));
}
```

（`setPanelWidth(null)` 场景：localStorage 无值时 `Number(null)=0` → 回退默认 420，已在 `clampPanelWidth` 内兜住。）

- [ ] **Step 2: bindEvents 内接线（插在 `#tabBar` 事件块结束后、`$('#apApproveBtn')` 之前，即当前 945 行处）**

```js
  // 右侧设置/调试面板：顶栏开关（拖拽调宽在 Task 4 接入）
  $('#panelBtn').onclick = togglePanel;
```

- [ ] **Step 3: ESC 链接入（当前 966-967 行）**

把这行：

```js
    if (state.drawer) { state.drawer = null; $('#taskDrawer').classList.add('hidden'); }
```

改为（补 `return` 保"一次 ESC 只关一个"，再追加面板）：

```js
    if (state.drawer) { state.drawer = null; $('#taskDrawer').classList.add('hidden'); return; }
    if (!$('#panelPane').classList.contains('hidden')) { togglePanel(); }
```

- [ ] **Step 4: 跑脚本**

Run: `cd /d/tlobjectapp/tlobject && PYTHONUTF8=1 python smoke/webui_panel_check.py`
Expected: **2a~2d、7a~7c、8a~8b、9、10 转绿**（开关/宽度记忆/ESC/非法值兜底/Tab 切换）；3a/3b/4/5/6 仍 FAIL（拖拽未接线）。

- [ ] **Step 5: Commit**

```bash
cd /d/tlobjectapp/tlobject
git add aiagent/webui/src/main/resources/webui/app.js
git commit -m "webui 右栏开关接线：⚙ 开合 + 宽度夹取/记忆（localStorage，非法值回退）+ ESC 链末尾收面板"
```

---

### Task 4: app.js — 拖拽调宽 + 双击复位

**Files:**
- Modify: `aiagent/webui/src/main/resources/webui/app.js`（Task 3 的接线处继续补）

- [ ] **Step 1: 接上拖拽与双击（把 Task 3 Step 2 的两行扩展为）**

```js
  // 右侧设置/调试面板：顶栏开关 + 左边缘拖拽调宽 + 双击复位
  $('#panelBtn').onclick = togglePanel;
  const resizer = $('#panelResizer');
  resizer.addEventListener('mousedown', e => {
    e.preventDefault();   // 阻止选中文本等默认行为；mousedown 的 preventDefault 不影响 dblclick
    document.body.classList.add('dragging');
    // 面板贴视口右缘：宽 = 视口宽 - 鼠标 x
    const move = ev => setPanelWidth(window.innerWidth - ev.clientX);
    const up = () => {
      document.body.classList.remove('dragging');
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
      window.removeEventListener('blur', up);
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
    window.addEventListener('blur', up);   // 鼠标在窗口外松开时的兜底
  });
  resizer.addEventListener('dblclick', () => setPanelWidth(PANEL_W_DEFAULT));
```

- [ ] **Step 2: 跑脚本，确认全绿**

Run: `cd /d/tlobjectapp/tlobject && PYTHONUTF8=1 python smoke/webui_panel_check.py`
Expected: **全部 PASS**（含 3a 拖动加宽、4 上限 1120、5 下限 280、6 双击复位，以及 `smoke/panel_open.png` / `smoke/panel_closed.png` 两张截图生成）。
若 6（双击复位）意外失败：说明 `mousedown.preventDefault` 在该浏览器版本连 dblclick 一起吞了——把 `dblclick` 复位改为 pointerdown 里手写"300ms 内第二次按 = 复位"的判定，其余不动。

- [ ] **Step 3: Commit**

```bash
cd /d/tlobjectapp/tlobject
git add aiagent/webui/src/main/resources/webui/app.js
git commit -m "webui 右栏拖拽调宽：把手 mousedown 拖动（mousemove 实时改 flex-basis/夹取）+ 双击复位 420"
```

---

### Task 5: 真机落地 + 交接

**Files:**
- Modify: `docs/superpowers/specs/2026-10-07-webui-right-panel-design.md`（一行实现细化）

- [ ] **Step 1: 同步资源到运行目录**

Run:
```bash
cd /d/tlobjectapp/tlobject && mvn -q -f pom.xml -pl aiagent/webui process-resources
```
Expected: 无报错；`aiagent/webui/target/classes/webui/` 下 chat.html/style.css/app.js 时间戳更新（aistart.bat / aistart-web.bat 的 classpath 就指这里）。
校验：`grep -c panelResizer aiagent/webui/target/classes/webui/chat.html` → 输出 `1`。

- [ ] **Step 2: 给用户两张截图 + 交接真机验证**

把 `smoke/panel_open.png`、`smoke/panel_closed.png` 给用户看；请用户重启 webui（aistart.bat）+ 浏览器 **Ctrl+F5 强刷**，逐项确认：
1. 默认右栏不见、聊天满宽
2. 点「⚙ 设置/调试」出现/再点收起，打开时按钮高亮
3. 拖把手宽窄变化、双击复位；刷新后宽度保留但面板默认关
4. 面板内四个 Tab 功能正常

- [ ] **Step 3: 更新 spec 一行（设计与实现一致）**

把 spec 表格里 `| 拖拽调宽 | 面板左边缘 5px 把手，pointer capture 拖动；范围 **[280px, 70vw]**；双击把手复位 420px |` 中的 "pointer capture 拖动" 改为 "mousedown 拖动（document 级 mousemove/mouseup）"（`preventDefault` 不影响 dblclick，实现更直白）。

```bash
cd /d/tlobjectapp/tlobject
git add docs/superpowers/specs/2026-10-07-webui-right-panel-design.md
git commit -m "webui 右栏设计文档：拖拽实现细化（pointer capture → mousedown 监听，dblclick 兼容）"
```

- [ ] **Step 4: 收尾核对**

- `git log --oneline -5` 应看到本功能 4 笔提交（Task 2/3/4 + 本 Task）
- `git status` 中 webui 三文件应为已提交（clean），smoke/、_tmp_ 等草稿照旧未跟踪
- 若用户真机发现视觉细节问题（间距/高亮不够明显），回到 Task 4 微调 CSS 后重跑脚本

---

## 附：需求覆盖核对（写计划时自审）

| spec 要求 | 落点 |
|---|---|
| 面板默认隐藏 | Task 2 Step 2（HTML hidden）+ 脚本 1a |
| 顶栏 ⚙ 按钮开关 + 高亮 | Task 2 Step 1/3 + Task 3 Step 2 + 脚本 2a~2c |
| 推挤式（聊天变窄/恢复满宽） | flex 天然行为 + 脚本 1b/2d/7b |
| 拖拽调宽 280~70vw | Task 4 + 脚本 3/4/5 |
| 双击复位 420 | Task 4 + 脚本 6 |
| 宽度记忆 localStorage + 非法兜底 | Task 3 Step 1 + 脚本 3b/8b/9 |
| 开合状态不记忆 | 脚本 8a（刷新后默认关） |
| ESC 收面板（链末尾、一次关一个） | Task 3 Step 3 + 脚本 7a |
| 左栏不动 / 四 Tab 不动 | 无对应改动 + 脚本 10 |
| 不加动画 / 不做覆盖式 | 无对应改动（负需求） |
