---
kb_id: kb-teller
doc_id: BAD-007
title: front-matter 未闭合的文档
doc_type: manual
doc_level: 2
org_codes:
  - "0100/0101"
# 缺少结尾的 --- 分隔符

# 一、正文
front-matter 未闭合，DocMetadataParser 应抛 "front-matter 未闭合" 并拒入。
