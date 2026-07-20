---
name: file_operation
description: 本地文件系统读写操作。支持读取文本文件、写入内容到文件、列出目录文件、检查文件是否存在、删除文件。
---

# 文件操作 Skill

对本地文件系统执行读写操作。

## 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `operation` | string | 是 | 操作类型：`read` / `write` / `list` / `exists` / `delete` |
| `path` | string | 是 | 文件或目录路径 |
| `content` | string | 否 | 写入内容（write 操作时使用） |

## 操作说明

- **read**: 读取文本文件内容并返回
- **write**: 将 content 写入指定文件（覆盖模式）
- **list**: 列出目录下所有文件
- **exists**: 检查文件是否存在，返回 true/false
- **delete**: 删除指定文件

## 注意事项

- 路径受安全限制，只能操作允许范围内的文件
- write 操作会覆盖已有文件
- 大文件读取可能被截断
- 操作前建议先用 exists 确认文件存在
