---
kb_id: kb-ops
doc_id: OPS-MANUAL-2024-007
title: 生产系统变更操作手册
doc_type: manual
doc_level: 3
org_codes:
  - "0100/0100"
owner: 孙伟-总行运维中心
source: 运维中心制度库
effective_from: "2024-03-01"
effective_until: null
version: "1.2"
is_code: false
---

# 一、适用范围
本手册适用于总行运维中心对生产系统的变更操作，包括升级、配置调整、参数修改等。

# 二、变更申请
## 2.1 申请要素
变更申请须填写变更编号、影响范围、回退方案、操作人、审批人。

## 2.2 审批要求
一般变更由运维值班长审批，重大变更须运维中心负责人及业务部门双审批。

# 三、变更执行
执行变更前须完成备份并校验备份可用性。以下为常规操作脚本示例：

```bash
#!/bin/bash
# 生产环境参数备份
cp -r /etc/app/config /backup/config_$(date +%Y%m%d)
# 应用变更后重启服务
systemctl restart bank-app
# 校验服务健康
curl -s http://localhost:8080/health | grep -q "UP" && echo "OK"
```

# 四、变更后验证
变更完成后须执行冒烟验证，确认核心交易链路正常后方可宣布变更成功。

# 五、回退机制
变更失败时按回退方案执行，回退后须保留变更前后现场，供复盘分析。
