---
name: code_execution
description: 在沙箱环境中执行代码片段。支持 Python（通过 python3 命令）和 JavaScript（通过内置 ScriptEngine）。用于计算、数据处理等需要编程的场景。
---

# 代码执行 Skill

执行用户提供的代码片段并返回执行结果。

## 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `language` | string | 是 | 编程语言：`python` 或 `javascript` |
| `code` | string | 是 | 要执行的代码内容 |
| `timeout` | string | 否 | 超时时间（秒），默认 30 |

## 使用场景

- 数学计算和数据处理
- 文本解析和格式转换
- 生成报表数据
- 快速原型验证

## 注意事项

- Python 代码通过 python3 子进程执行，需系统安装 Python
- JavaScript 通过 JVM ScriptEngine 执行
- 代码在受限环境中运行，不能访问网络
- 超时后进程会被强制终止
- 输出大小有上限，超出的部分会被截断
- 仅用于简单计算，不适合执行大型脚本（大型脚本请用 script_execution）
