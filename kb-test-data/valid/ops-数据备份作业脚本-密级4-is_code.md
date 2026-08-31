---
kb_id: kb-ops
doc_id: OPS-SCRIPT-2025-012
title: 生产数据备份作业脚本
doc_type: script
doc_level: 4
org_codes:
  - "0100/0100"
owner: 孙伟-总行运维中心
source: 运维中心脚本库
effective_from: "2025-02-01"
effective_until: null
version: "1.0"
is_code: true
---

# 一、脚本说明
本脚本用于生产数据库每日全量备份，由 cron 在每日 01:00 触发执行。

# 二、脚本内容
```bash
#!/bin/bash
# 每日 01:00 执行全量备份
set -e
BACKUP_DIR=/data/backup/db
BK_NAME=core_$(date +%Y%m%d_%H%M%S).dump
pg_dump -h ${PG_HOST} -U ${PG_USER} -Fc ${PG_DB} > ${BACKUP_DIR}/${BK_NAME}
# 备份保留 30 天
find ${BACKUP_DIR} -name "*.dump" -mtime +30 -delete
# 备份完成通知
echo "[OK] backup done: ${BK_NAME}" >> /var/log/backup.log
```

# 三、执行注意
1. 执行期间数据库锁表时间较长，须在业务低峰期运行。
2. 备份文件落地后须抽检 md5 校验值，防止备份文件损坏。
3. 本脚本涉及生产数据库凭据，严禁向无关人员泄露。
