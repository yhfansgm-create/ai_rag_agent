---
kb_id: kb-teller
doc_id: BAD-006
title: org_codes 为空的文档
doc_type: manual
doc_level: 2
org_codes: []
---

# 一、正文
org_codes 为空列表，入库校验应抛 "缺少必需元数据 org_codes(授权组织清单不能为空)" 并拒入。
