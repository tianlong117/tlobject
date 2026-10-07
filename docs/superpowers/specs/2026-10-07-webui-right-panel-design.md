# webui 右侧面板改造：默认隐藏 + 可拖拽调宽 设计

日期：2026-10-07
状态：已确认，待实施

## 1. 目标与背景

现状：webui 主界面三栏常驻——左侧会话栏固定 240px、右侧命令面板固定 420px、聊天区被夹在中间
（`style.css:45/67/132`）。右栏四个 Tab（Agent/Skill、MCP、评测/测试、追踪）全部是设置/调试用途，
日常聊天用不到，却常驻占宽 420px（**比左栏还宽**），左右不等宽在视觉上也显得别扭
（用户原话："左侧和右侧的栏宽度不同，页面看上就比较别扭"）。

目标：**右栏默认隐藏，需要时点按钮弹出；面板宽度可拖动调整**（窄了盯状态、宽了看表格/长 JSON），
彻底消除"常驻不对称"。

已确认的需求决策：

| 决策点 | 结论 |
|---|---|
| 打开形态 | **B 推挤式**：打开时 flex 自然推挤聊天（三栏并排）；关闭即恢复聊天满宽。无遮罩、无覆盖 |
| 触发方式 | 顶栏新增「⚙ 设置/调试」ghost 按钮（与 📋🚀🔔 同排，位于 🔔 消息箱与 退出 之间），点击开合；打开时按钮高亮 |
| 默认状态 | 每次加载**默认关闭**；开/关状态不记忆 |
| 拖拽调宽 | 面板左边缘 5px 把手，mousedown 拖动（document 级 mousemove/mouseup；实现细化：不用 pointer capture——mousedown 的 `preventDefault` 不影响 dblclick，更直白）；范围 **[280px, 70vw]**；双击把手复位 420px |
| 宽度记忆 | `localStorage['tlweb_panelw']`，读取时夹取范围 |
| 左栏 | 固定 240px 不动（用户确认：只右栏可拖） |
| 开合动画 | **不加**（即时显示，与站内其它浮层一致；也避免拖动时与动画打架） |
| ESC | 追加到现有 ESC 链**末尾**（浮层们 → 任务详情抽屉 → 面板） |
| 面板内容 | 四个 Tab 及面板内功能原样不动 |

## 2. 现状核实（对 HEAD `201d03a`）

- 布局：`main` flex 三栏（`chat.html:36-150`）：
  `#sessionPane` 240px（`style.css:45`）、`#chatPane` flex:1（`style.css:67`）、`#panelPane` 420px（`style.css:132`）
- `#panelPane` 在 JS 中**无引用**（唯一相关是 `app.js:939-944` 的 tabBar 切 Tab 事件），默认隐藏无副作用
- 面板内功能（Agents/Skills/MCP/评测/追踪）全部由**面板自身按钮**触发
  （`app.js:1559/1573/1813/1852/1904/1985`），无外部入口——不存在"面板关着时被别处调用、结果看不见"的路径
- 静态资源加载路径：`aistart.bat` / `aistart-web.bat` 的 classpath 均指向
  `D:\tlobjectapp\tlobject\aiagent\webui\target\classes`（已核实），改完源文件需 mvn 同步（process-resources）后重启生效
- ESC 保命键链：`app.js:960-967`（bgTasksModal → tasksModal → inboxModal → checkpointModal → taskDrawer）
- 现有浮层先例可循：`#tasksModal`（`chat.html:170`）等「style.css .modal」+ 显隐切换

## 3. 改动设计

改动范围：**仅 3 个前端文件**（`chat.html` / `style.css` / `app.js`），零 Java 改动、零后端接口改动。

### 3.1 结构（chat.html）

```html
<!-- 顶栏：在 #inboxBtn 与 #logoutBtn 之间插入 -->
<button id="panelBtn" class="ghost" title="设置/调试面板（Agent/Skill、MCP、评测、追踪）">⚙ 设置/调试</button>

<!-- #panelPane 加初始 hidden；内部首行加拖拽把手 -->
<aside id="panelPane" class="hidden">
  <div id="panelResizer" title="拖动调整宽度（双击复位）"></div>
  <nav id="tabBar">…（原样）</nav>
  …（四个 tab-panel 原样）
</aside>
```

### 3.2 样式（style.css）

- `#panelPane`：宽度改由 **flex-basis** 控制（`flex: 0 0 420px`，替代 `width: 420px`，防止 flex 收缩压缩）；
  `position: relative`（给把手定位）
- `#panelResizer`：绝对定位贴左边缘
  `position:absolute; left:-2px; top:0; bottom:0; width:5px; cursor:col-resize; z-index:5`；
  悬停/拖动中高亮（`background:#3b82f6`）
- 拖动中：`body.dragging { user-select: none; cursor: col-resize; }`
- 按钮激活态：`#panelBtn.on { color:#93c5fd; border-color:#3b82f6; }`

### 3.3 行为（app.js）

- 常量：`PANEL_W_DEFAULT = 420`、`PANEL_W_MIN = 280`；上限 = `window.innerWidth * 0.7`（每次使用动态计算）
- `readPanelWidth()`：读 `localStorage['tlweb_panelw']`，非法值（NaN/越界）→ 回退默认并夹取
- `setPanelWidth(w)`：统一夹取 [PANEL_W_MIN, 70vw] → 写 `#panelPane.style.flexBasis` → 存 localStorage
- `togglePanel()`：切 `#panelPane` 的 `hidden` 类 + `#panelBtn` 的 `on` 类；**打开时**应用记忆宽度
  （每次打开都夹取，兜住"窗口变小了"的场景）
- 拖拽：`#panelResizer` pointerdown → `setPointerCapture` → pointermove 计算
  `window.innerWidth - e.clientX` → `setPanelWidth` → pointerup 释放 + 移除 `body.dragging`
- 双击把手 → `setPanelWidth(PANEL_W_DEFAULT)`
- ESC 链末尾追加：面板开着 → 关面板（`app.js:960-967` 内加一行）

### 3.4 边界与错误处理

- localStorage 值非法 / 越界 → 回退默认 420 并夹取（不抛错）
- 窗口 resize 变小后若面板宽 > 70vw → 打开时夹取（宽度只在"打开"和"拖动"两个时刻应用）
- 拖动越界 → 夹取在 `setPanelWidth` 内统一处理，调用方不管

## 4. 不做的事（YAGNI）

- ✗ 开合动画
- ✗ 记忆开/关状态（每次加载默认关闭）
- ✗ 左栏拖拽
- ✗ 改动面板内任何功能与加载时机
- ✗ 覆盖式抽屉 / 居中弹窗形态（B 已定）

## 5. 验证方式

`mvn` 同步 webui 资源 → 起服务（aistart.bat）→ 浏览器逐项核对：

1. 加载默认：右栏隐藏，页面 = 左栏 240px + 聊天满宽
2. 点「⚙ 设置/调试」：面板出现（默认 420px）、按钮高亮；聊天被推挤变窄
3. 拖把手：宽度实时变化；拖到两端被夹在 [280px, 70vw]
4. 双击把手：回到 420px
5. 刷新页面：面板默认关闭；再打开时宽度保留上次值
6. ESC：面板关闭
7. 面板内功能回归：四个 Tab 切换、Agents/Skills 加载、MCP 搜索、评测/测试、追踪各按钮正常
8. 关面板后聊天恢复满宽，输入/发送/流式正常

## 6. 风险

- 改动集中在纯前端显隐与布局，**回滚 = 还原 3 个文件**；
- 唯一回归面：`#panelPane` 由常驻变隐藏，若未来有代码从外部依赖其可见性——本次已核实当前无此路径
  （第 2 节），后续新增功能需注意"结果画进面板时面板可能是关着的"。
