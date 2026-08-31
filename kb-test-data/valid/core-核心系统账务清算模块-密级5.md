---
kb_id: kb-core
doc_id: CORE-DEV-2025-002
title: 核心系统账务清算模块设计说明
doc_type: code
doc_level: 5
org_codes:
  - "0100/0100"
owner: 刘洋-总行科技部
source: 核心系统开发文档
effective_from: "2025-01-15"
effective_until: null
version: "4.2"
is_code: true
---

# 一、模块概述
本模块负责核心账务系统的日终清算，包括总账汇总、分户结转、余额核对三部分。

# 二、清算主流程
系统每日 23:30 自动触发日终批处理，按机构号、币种、科目号逐级汇总当日交易流水。

```java
public class DailySettlement {
    public void settle(String batchDate) {
        List<Transaction> txs = txRepo.findByBatchDate(batchDate);
        Map<String, BigDecimal> total = new HashMap<>();
        for (Transaction tx : txs) {
            total.merge(tx.getSubjectCode(), tx.getAmount(), BigDecimal::add);
        }
        ledgerService.postDailySummary(total);
    }
}
```

# 三、余额核对规则
分户账余额 = 上日余额 + 当日借方差额 - 当日贷方差额。核对不一致时触发账务异常告警，暂停批处理并人工介入。

# 四、异常回滚
清算失败时，系统自动回滚当日已入账的汇总记录，恢复批处理前状态，并记录完整回滚日志供排查。
