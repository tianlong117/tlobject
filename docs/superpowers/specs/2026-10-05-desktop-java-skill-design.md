# desktop-java 桌面自动化技能（Java 版）设计

日期：2026-10-05
状态：已实施并真机验证

## 1. 目标

把 desktop 技能做成 **Java 版独立模块**（对称 browser-java 的双版结构）：

- **零外部依赖**：Python 版需 `pip install pyautogui mss`；browser-java 需 Playwright jar + 首次
  500MB 浏览器下载；desktop-java **只用 JDK 自带的 `java.awt.Robot`**——零下载
- **无子进程**：Python 版是 `scriptExecutionSkill` 包装脚本子进程（JSON 管道往返 + 进程开销 +
  Windows GBK 编码坑）；Java 版进程内直调
- **动作补足**：在 Python 版 6 个动作基础上新增 6 个（含"中文输入的正解"）

## 2. 模块结构（对称 browser-java）

```
aiagent/desktop-java/
  pom.xml                       （只依赖 tlobject-aiagent-common）
  .../aiagent/desktop/
    JavaDesktopEngine.java      （Robot 封装：12 action + DPI 双信号检测）
    TLDesktopJavaSkill.java     （技能壳：schema/输入解析/JSON 输出/LinkageError 防护）
```

接线（4 处）：
| 位置 | 改动 |
|---|---|
| `aiagent/pom.xml` | `<module>desktop-java</module>` |
| `tlobject-all/pom.xml` | 加 `tlobject-aiagent-desktop-java` 依赖 |
| `cn.tianlong.tlobject.aiagent_config.xml`（框架注册表） | `<module name="desktopJavaSkill" classfile="...TLDesktopJavaSkill" singleton="true"/>` |
| `aiagent_master_config.xml` 的 `<skills>` | `sameClassAs="scriptExecutionSkill"` → `"desktopJavaSkill"`（**一行切换**）；回退=改回并恢复 `interpreter`/`allowedScriptDir` |
| 5 个 `aistart*.bat` | classpath 加 `aiagent/desktop-java/target/classes` |

## 3. 动作集（12 个）

| 对齐 Python 版（6） | 参数 | Java 版补足（6） | 参数 |
|---|---|---|---|
| `screenshot` | 可选区域 x/y/width/height | `double_click` | x,y |
| `click` | x,y[,button:left/right/middle] | `drag` | x1,y1,x2,y2[,duration] |
| `type` | text（**仅 ASCII**） | `key` | key（组合键 `ctrl+c`/`alt+tab`…） |
| `move` | x,y | `wait` | ms |
| `scroll` | amount（正=上滚） | `get_mouse_position` | — |
| `get_screen_size` | — | `paste` | text（**剪贴板，中文/Unicode 输入的正解**） |

**type vs paste**：Robot 的键码映射对非 ASCII 无效，`type` 仅可靠支持 ASCII；含中文一律
`paste`（写系统剪贴板 → Ctrl+V）。`type` 对无键位字符**如实返回 `dropped_chars`**（不声称全打了）。

## 4. DPI 处理（策略：明确报错不误导）

**问题**：JVM 非 DPI 感知 + 系统缩放 >100% 时，Windows 坐标虚拟化使 AWT 逻辑坐标与物理像素
不一致——LLM 从截图上读的坐标与 Robot 点按位置错位，会**真的误操作桌面**。

**只依赖 JDK 的双信号检测**（不引入 JNA 平台库以保持零下载）：

1. **AWT 变换缩放**：`GraphicsConfiguration.getDefaultTransform().getScaleX() > 1.0`
   （JDK 官方 API；非 DPI 感知 JVM 在缩放系统上会报缩放系数）
2. **截图尺寸交叉验证**：请求 `screenW` 宽截图，若返回像素数明显更大（DPI 虚拟化把 AWT
   坐标空间截成物理像素）→ 命中

**行为**：命中后点按/输入类动作（click/double_click/drag/move/scroll/type/key/paste）**直接拒绝**，
返回可读错误（含逻辑/物理尺寸与修复方法"把系统显示缩放调为 100%"）；`screenshot`/
`get_screen_size`/`get_mouse_position` 仍可用（附 `dpi_notice`）。

**实测**（本机 100% 缩放）：`dpiScale=1.0 dpiSource=consistent`。

## 5. 其他工程细节（沿 browser-java 验证过的做法）

- `ensureEngine` 在 try 内 + catch `LinkageError`：classpath 手接线缺类时工具结果不凭空消失
- gson `disableHtmlEscaping`：base64 的 `=` 不被 HTML 转义（否则前端截图正则失配）
- `maxShotHeight`（默认 1080）：超高截图按比例缩小，base64 体量可控（1600x900 实测 288-946KB）
- 区域截图**越界钳制 + notice**：不钳会给屏外涂黑的图，LLM 无法区分"黑屏"与"越界"
- 快捷键**先全量校验再按下**：未知键名直接报错，不会按下一半键
- `scroll` 参数存在但非法（如 "abc"）→ 显式报错，不静默滚默认量

## 6. 验证记录

- **动作级探针**（38/38 PASS）：截图（全屏/区域/尺寸）/尺寸/鼠标位置/wait 计时/剪贴板 Unicode
  往返/全部错误路径（缺参/越界/中文 type 拒绝/未知 action/未知键名）；**遵安全纪律不执行
  真实点按/移动/按键**
- **修复复验**（5/5 PASS）：越界截图钳制（尺寸与 notice 正确）/正常区域无 notice/scroll 非法参数
  报错/type 丢字符如实报告
- **真实应用 E2E**：LLM 经 tool call 调用 desktop → 截图 → 报告屏幕尺寸/鼠标位置，全链通；
  日志 `Desktop(java) engine started: screen=1600x900 dpiScale=1.0 (source=consistent)`

## 7. 已知边界

- **仅 ASCII 键位输入**：中文必须 `paste`（两版共同的限制，非 Java 版引入）
- **单显示器坐标**：`screenshot`/坐标以主屏可见区域为准（与 AWT 一致）；多显示器未特化
- **DPI >100% 未实机验证**：本机 100%，双信号逻辑经代码审查 + 探针验证（scale=1 路径）；
  高缩放机器上的拒绝行为待有环境时实测
- **macOS/Linux**：`java.awt.Robot` 跨平台可用，但 `key` 的 keyCode 映射与剪贴板行为未在该平台验证
