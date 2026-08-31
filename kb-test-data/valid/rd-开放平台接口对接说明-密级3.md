---
kb_id: kb-rd
doc_id: RD-API-2025-031
title: 开放平台接口对接说明
doc_type: manual
doc_level: 3
org_codes:
  - "0201/0105"
  - "0100/0100"
owner: 周婷-分行研发中心
source: 开放平台研发组
effective_from: "2025-05-20"
effective_until: null
version: "1.8"
is_code: false
---

# 一、接口概述
本说明用于分行研发中心对接总行开放平台的账务查询接口，介绍鉴权方式、报文格式及错误码。

# 二、鉴权方式
对接方须使用平台签发的 clientId 与 clientSecret 获取 access_token，token 有效期为 2 小时。

# 三、账务查询接口
## 3.1 请求示例
```java
POST /api/v1/account/query HTTP/1.1
Host: openapi.bank.example.com
Authorization: Bearer {access_token}
Content-Type: application/json

{"accountNo":"6222020200001234","queryDate":"2025-05-20"}
```

## 3.2 响应字段
| 字段 | 类型 | 说明 |
|------|------|------|
| accountNo | String | 账号 |
| balance | BigDecimal | 可用余额 |
| status | String | 账户状态 NORMAL/FROZEN |

## 3.3 错误码
- 1001：token 无效或过期
- 1002：签名校验失败
- 2003：账号不存在

# 四、联调环境
联调环境地址为 https://openapi-test.bank.example.com，生产环境域名以运维中心公告为准。
