# mermaid-flowchart 安装说明

安装前请完整阅读本文。Skill 能否被 Agent 调用，取决于「文件落盘」与「热加载注册」两步都完成，缺一步都会表现为「装好了但用不了」。

## 一、安装前提与环境自检

先在命令行逐项执行，全部通过再安装：

```bash
python --version
node --version
npx --no-install mmdc --version
```

实测通过的版本参照：

| 组件 | 实测版本 |
|---|---|
| python | Python 3.12.3 |
| node | v24.14.1 |
| npx | 11.14.1 |
| mmdc（@mermaid-js/mermaid-cli） | 12.2.0 |

若 `npx --no-install mmdc --version` 报错，在项目根目录执行：

```bash
npm install @mermaid-js/mermaid-cli
```

安装完成后该目录下应存在 `node_modules`，且 `node_modules/.bin/mmdc.cmd`（Windows）可用。

另外请确认系统中文字体 Microsoft YaHei 或 SimHei 可用，否则图中中文会显示为方块。

## 二、方式一：使用 install_skill 工具安装（推荐）

由 Agent 调用内置的 `install_skill` 技能完成建目录、拷脚本、生成 SKILL.md 与注册。只需给出以下参数：

```json
{
  "script_path": "<解压目录>/mermaid-flowchart/scripts/mermaid_render.py",
  "skill_name": "mermaid-flowchart",
  "skill_description": "根据内容或 Markdown 文件生成流程图，支持 mmd/md/纯文本三种输入，输出 PNG/SVG/PDF，中文正常显示。",
  "interpreter": "python",
  "max_execution_time": 180
}
```

参数说明：

- `script_path`：指向本包内的 `scripts/mermaid_render.py`，需为解压后的绝对路径。
- `skill_name`：固定为 `mermaid-flowchart`，只允许小写字母、数字、连字符。
- `interpreter`：`python`。渲染外部命令走 node，但入口脚本是 Python。
- `max_execution_time`：`180` 秒。首次运行可能需下载 Chromium 内核，过短会超时。

安装器会生成如下结构：

```
mermaid-flowchart/
  SKILL.md
  scripts/mermaid_render.py
```

## 三、方式二：手动安装

### 落盘目录规则

```
<configDir>/skills/<skillName>/
```

其中 `<configDir>` 是该 Agent 工程的配置目录，`<skillName>` 为 skill 名称。

demo 的运行期路径示例：

```
<project>/demo/tlobject/target/classes/conf/demo/aiagent/skills/mermaid-flowchart
```

### 手动操作步骤

1. 在 `<configDir>/skills/` 下创建目录 `mermaid-flowchart`。
2. 在该目录下创建子目录 `scripts`。
3. 把本包的 `scripts/mermaid_render.py` 复制到 `scripts/` 内。
4. 把本包的 `SKILL.md` 复制到 `mermaid-flowchart/` 目录根部，与 `scripts` 同级。

最终结构：

```
<configDir>/skills/mermaid-flowchart/
  SKILL.md
  scripts/
    mermaid_render.py
```

`README.md`、`INSTALL.md`、`CHANGELOG.md`、`examples/`、`test/` 属于说明与自测资料，不参与运行，可选择性保留。

## 四、关键注意：必须热加载才能被调用

文件落盘后，Agent 尚不知道这个 Skill 存在，**必须执行热加载**：

```
/install -s mermaid-flowchart
```

未执行该命令前，`mermaid-flowchart` 找不到 `mermaid_render.py`，调用会失败。

### 出现「注册到 Agent 失败 / Agent 无响应」时

若 `install_skill` 返回类似：

```
⚠ 脚本文件已就绪，但注册到 Agent 失败: Agent 无响应
请手动在控制台执行: /install -s mermaid-flowchart
```

说明文件**已经成功落盘**，只是自动注册这一步没成功。安装器在注册失败时不会删除已创建的文件，因此目录与脚本都在。

处理办法：手动在控制台执行 `/install -s mermaid-flowchart` 完成热加载即可，无需重装。

## 五、重要警告：target/classes 会被清空

本机实际落盘路径位于 `target/classes` 下，这是 Maven 的**编译输出目录**，不是源码目录。

执行 `mvn clean` 或重新构建工程时，该目录会被整体清空，Skill 随之丢失，表现为「昨天还能用，今天找不到了」。

长期保存请同步一份到源码目录：

```
<project>/demo/tlobject/src/main/resources/conf/demo/aiagent/skills/mermaid-flowchart/
```

这样每次构建，文件会被自动拷贝回 `target/classes`，不会因清理而丢失。同步后仍需确认热加载状态。

## 六、覆盖安装

若安装时提示：

```
Skill 目录已存在: ...。如果要覆盖，请先手动删除或使用 /uninstall -s mermaid-flowchart
```

先卸载再重装：

```
/uninstall -s mermaid-flowchart
```

或手动删除 `skills/mermaid-flowchart` 整个目录。注意安装器为保证安全，不会自动覆盖已存在的目录。

## 七、验证安装成功的三个检查点

**检查点一：目录结构存在**

```bash
ls <configDir>/skills/mermaid-flowchart
```

应能看到 `SKILL.md` 与 `scripts`。

**检查点二：脚本 MD5 与源一致**

```bash
python -c "import hashlib;print(hashlib.md5(open(r'<configDir>/skills/mermaid-flowchart/scripts/mermaid_render.py','rb').read()).hexdigest())"
```

应输出：

```
3209ea5ae3628620a6af62600720ad07
```

不一致说明拷贝中断或被改动，请重新复制。

**检查点三：mermaid-flowchart 能调用到脚本**

让 Agent 调用 `mermaid-flowchart`，参数形如：

```json
{
  "script_path": "mermaid_render.py",
  "arguments": "--text "开始\n校验数据\n返回结果" -o demo.png"
}
```

能正常产出图片即安装成功。若提示找不到脚本，回到第四节检查热加载。

也可直接运行包内自检脚本，一次性核对环境与端到端渲染：

```bash
python test/run_selftest.py
```

## 八、卸载

在控制台执行：

```
/uninstall -s mermaid-flowchart
```

该命令会移除注册信息并删除对应目录。若目录残留（例如清理不彻底），手动删除：

```
<configDir>/skills/mermaid-flowchart/
```

卸载后无需额外操作，下次启动 Agent 不会再加载该 Skill。

## 九、跨机器移植注意

本包是**纯代码包**，以下内容不随包分发，换机器必须重新准备：

1. **node_modules 不在包内**。目标机需在项目根目录执行：

   ```bash
   npm install @mermaid-js/mermaid-cli
   ```

   否则 `npx --no-install mmdc` 会因找不到本地包而失败。

2. **Chromium / puppeteer 内核不在包内**。首次渲染时可能触发下载，网络受限时请预先配置代理或手动放置内核。

3. **字体不随包分发**。目标机若无 Microsoft YaHei / SimHei，中文会渲染成方块。Linux 需安装中文字体（如 `fonts-wqy-zenhei`），并可能需刷新字体缓存。

4. **Python 依赖**：主脚本仅用标准库，无需 pip 安装；但渲染链路的 node 版本需满足 node 18+。

移植完成后，请按第七节三个检查点重新验证一遍。

## 十、实测要点：node_modules 必须在脚本所在目录的父级附近

`mermaid_render.py` 内部把 `npx` 的工作目录固定为**脚本自身所在目录**
（源码中的 `PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))`），
并以 `npx --no-install mmdc` 调用。

由此带来两个实际结论：

1. 仅把本包解压到任意临时目录、不做 `npm install`，渲染一定失败，报
   `[ERROR] 未检测到可用的 mmdc（@mermaid-js/mermaid-cli）`。
   这是预期行为，不是包损坏。

2. 按本文方式安装到 `skills/mermaid-flowchart/scripts/mermaid_render.py` 后，
   脚本所在目录为 `skills/mermaid-flowchart/scripts`，则 node_modules 需要能让
   npx 在向上查找时找到，最省事的做法是直接软链：

   ```bat
   mklink /J "<configDir>/skills/mermaid-flowchart/scripts/node_modules" "<project_root>/node_modules"
   ```

   Windows 上用 junction 链接，不占额外磁盘，且随源目录更新。

实测对照（同一台机器、同一份脚本）：

| 场景 | mmdc 探测 | 三份示例渲染 |
|---|---|---|
| 解压目录无 node_modules | 失败 | 全部失败 |
| 解压目录补上 node_modules | 通过（12.2.0） | 全部通过（62KB 至 147KB） |

实测产物大小参考：`example_flow.mmd` 约 147KB，`example_flow.md` 约 63KB，
`example_text.txt` 约 137KB，均远高于自检阈值 10KB。

## 十一、关于「同时输出 .mmd 源码」

脚本仅在**自动生成**场景（`--text` 或 `.txt` 输入，以及无代码块的 `.md`）额外保存同名 `.mmd`。

若输入本身就是 `.mmd` 文件，源码已存在，不会再生成副本；
若 `.md` 中已含 ```mermaid 代码块，同样不重复输出。这属于设计行为。
