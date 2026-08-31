---
---

# 空 front-matter 的文档

front-matter 内没有任何字段。DocMetadataParser 会解析出空 Map → 所有必填字段缺失，
入库校验应抛 "缺少必需元数据 kb_id" 并拒入。
