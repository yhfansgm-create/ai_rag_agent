---
doc_id: BAD-001
title: 缺少 kb_id 的文档
doc_type: manual
doc_level: 2
org_codes:
  - "0100/0101"
---

# 一、正文
本文档故意缺少 kb_id 字段，入库校验应抛 "缺少必需元数据 kb_id" 并拒入。
