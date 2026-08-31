---
kb_id: kb-teller
doc_id: BAD-005
title: doc_level 为 6 的文档
doc_type: manual
doc_level: 6
org_codes:
  - "0100/0101"
---

# 一、正文
doc_level=6 越界，入库校验应抛 "doc_level 必须为 1-5, 实际: 6" 并拒入。
