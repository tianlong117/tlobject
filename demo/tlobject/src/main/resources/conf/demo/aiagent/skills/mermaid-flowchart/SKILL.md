---
name: mermaid-flowchart
description: >-
  根据内容或 Markdown 文件生成流程图。支持三种输入：1) .mmd 文件直接渲染；2) .md 文件自动提取其中的 mermaid 代码块渲染，若无代码块则从标题与列表结构自动生成流程图；3) 纯文本（--text 或 .txt）自动转换为 Mermaid 流程图。输出 PNG/SVG/PDF，中文正常显示。当用户要求"画流程图""把这段流程生成图""根据 md 出图""流程图转图片"时使用。依赖 Node 环境下的 @mermaid-js/mermaid-cli（mmdc 命令），运行时通过 npx --no-install mmdc 调用。
  通过 mermaid-flowchart 工具调用，script_path 为 'mermaid_render.py'。
---

# mermaid-flowchart

执行 `mermaid_render.py` 脚本。

## 功能描述

根据内容或 Markdown 文件生成流程图。支持三种输入：1) .mmd 文件直接渲染；2) .md 文件自动提取其中的 mermaid 代码块渲染，若无代码块则从标题与列表结构自动生成流程图；3) 纯文本（--text 或 .txt）自动转换为 Mermaid 流程图。输出 PNG/SVG/PDF，中文正常显示。当用户要求"画流程图""把这段流程生成图""根据 md 出图""流程图转图片"时使用。依赖 Node 环境下的 @mermaid-js/mermaid-cli（mmdc 命令），运行时通过 npx --no-install mmdc 调用。

## 执行方式

```bash
python <skill_dir>/scripts/mermaid_render.py [选项]
```

- 解释器: `python`
- 超时: 180 秒

## 依赖

本 Skill 依赖 Python + Node 双运行时，缺一不可，安装前请逐项确认：

- **python 3.8+**（实测 3.12.3）：主程序，负责输入解析、Mermaid 代码生成与 mmdc 调度。
- **node 18+**（实测 v24.14.1）与 **npx**：mmdc 是 Node 工具，需由 node 执行。
- **@mermaid-js/mermaid-cli**，即 `mmdc` 命令（实测 12.2.0）：实际渲染引擎。
  项目根目录需已执行 `npm install @mermaid-js/mermaid-cli`，产生 `node_modules`；
  脚本以 `npx --no-install mmdc` 方式调用，即只认本地已安装版本、不联网下载。
- **Chromium / puppeteer**：mmdc 自带依赖，首次运行可能需下载浏览器内核，需保持网络可用。
- **中文字体 Microsoft YaHei / SimHei**：缺少中文字体会导致图中中文显示为方块或乱码。

自检命令：

```bash
python --version
node --version
npx --no-install mmdc --version
```

完整的安装步骤、热加载、卸载与迁移说明见同目录 `INSTALL.md`。
