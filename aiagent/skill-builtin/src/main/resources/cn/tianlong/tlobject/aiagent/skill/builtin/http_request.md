---
name: http_request
description: 发送 HTTP 请求获取网页内容或调用 API。支持 GET 和 POST 方法，可自定义请求头和请求体。
---

# HTTP 请求 Skill

向指定 URL 发送 HTTP 请求并返回响应内容。

## 参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `url` | string | 是 | 请求 URL（含协议） |
| `method` | string | 否 | 请求方法，GET 或 POST，默认 GET |
| `headers` | string | 否 | 自定义请求头，JSON 格式，如 `{"Authorization":"Bearer xxx"}` |
| `body` | string | 否 | POST 请求体 |

## 使用场景

- 调用 REST API 获取数据
- 抓取网页内容
- 与外部服务交互

## 注意事项

- URL 必须包含协议（http:// 或 https://）
- POST 请求的 body 为字符串格式
- 请求超时默认 30 秒
- 返回结果包含 HTTP 状态码和响应体
