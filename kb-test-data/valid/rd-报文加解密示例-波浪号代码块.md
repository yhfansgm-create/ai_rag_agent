---
kb_id: kb-rd
doc_id: RD-SAMPLE-2024-077
title: 接口报文加解密示例
doc_type: manual
doc_level: 3
org_codes:
  - "0201/0105"
owner: 周婷-分行研发中心
source: 开放平台研发组
effective_from: "2024-10-01"
effective_until: null
version: "1.1"
is_code: false
---

# 一、说明
本文给出接口报文 SM4 加解密示例代码，供分行研发参考。

# 二、加密示例（波浪号围栏）
~~~python
from gmssl import sm4
import base64

key = b"0123456789abcdef"
data = b"hello-bank"
cipher = sm4.CryptSM4()
cipher.set_key(key, sm4.SM4_ENCRYPT)
enc = cipher.crypt_ecb(data)
print(base64.b64encode(enc))
~~~

# 三、解密示例
~~~python
cipher.set_key(key, sm4.SM4_DECRYPT)
dec = cipher.crypt_ecb(base64.b64decode(cipher_text))
print(dec)
~~~

# 四、注意事项
密钥仅用于联调环境，生产环境密钥由密钥管理系统下发。
