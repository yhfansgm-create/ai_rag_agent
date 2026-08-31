---
kb_id: kb-teller
doc_id: BAD-002
title: 缺少 doc_level 的文档
doc_type: manual
org_codes:
  - "0100/0101"
---

# 一、正文
本文档故意缺少 doc_level 字段，缺省解析为 0，入库校验应抛 "doc_level 必须为 1-5" 并拒入。
