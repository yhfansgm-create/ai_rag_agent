---
kb_id: kb-teller
doc_id: BAD-004
title: doc_level 为 0 的文档
doc_type: manual
doc_level: 0
org_codes:
  - "0100/0101"
---

# 一、正文
doc_level=0 越界，入库校验应抛 "doc_level 必须为 1-5, 实际: 0" 并拒入。
