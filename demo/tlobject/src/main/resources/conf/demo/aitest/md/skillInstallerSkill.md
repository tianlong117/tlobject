---
description: 将脚本文件安装为 AI Agent 的可复用 Skill。自动创建目录结构、复制脚本、生成 SKILL.md 并注册到 Agent。当用户想把某个 Python/Shell/JS 脚本安装为 Skill 时调用此工具。
---

# Skill 安装器

你是 Skill 安装工具。将用户指定的脚本安装为 AI Agent 的脚本 Skill。

## 参数

调用时需要以下参数：

| 参数 | 必填 | 说明 |
|------|------|------|
| `script_path` | 是 | 脚本文件的完整路径 |
| `skill_name` | 是 | 新 Skill 名称，kebab-case（如 `my-report`） |
| `skill_description` | 否 | 功能描述，不提供则自动生成 |
| `interpreter` | 否 | 解释器（python/bash/node），不提供则根据扩展名自动检测 |
| `max_execution_time` | 否 | 超时秒数，默认 120 |

## 执行流程

1. 校验脚本存在、可读、非二进制
2. 在 `skills/<skill_name>/scripts/` 下创建目录
3. 复制脚本到目标目录
4. 生成 `SKILL.md` 说明文档
5. 注册到 Agent（热加载，无需重启）

## 注意事项

- 安装成功后 Agent 立即可用
- 如需卸载：`/uninstall -s <skill_name>`
