---
kb_id: kb-rd
doc_id: RD-TOOL-2024-120
title: 内部命令行工具使用说明
doc_type: script
doc_level: 3
org_codes:
  - "0201/0105"
owner: 周婷-分行研发中心
source: 开放平台研发组
effective_from: "2024-12-01"
effective_until: null
version: "1.0"
is_code: true
---

# 一、工具简介
内部提供 bank-util 命令行工具，用于批量查询流水、导出对账单。

# 二、常用命令
查询单日流水：`bank-util query --date 2024-12-01 --acct 6222020200001234`
导出月度对账单：`bank-util export --month 202411 --acct 6222020200001234`

# 三、注意事项
1. 工具需在分行内网环境运行。
2. 输出文件包含敏感交易明细，使用后须及时删除。
3. 工具凭据由研发组统一管理，到期及时轮换。
