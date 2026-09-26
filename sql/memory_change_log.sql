-- =============================================================================
-- 银行内部 RAG 智能助手 · 记忆变更审计 DDL（memory_change_log）
-- 数据库 : MySQL 8.0+ / InnoDB / utf8mb4
-- 依据   : 《银行RAG-07-记忆分层与断点续传设计》§四 / 附录 A.3
--          RAG-04 待做 #13「审计表 + 哈希链」（同一机制, 本表是它的首个实现）
--
-- 一句话：记忆的每一次变更（写 / 取代 / 拒写）都留痕, 且上一条的 hash 进下一条, 形成链。
--
-- 本表相对 §A.3 的**两处偏离**（都是必要的, 不是扩展）：
--   ① `memory_id BIGINT` → `fact_uid CHAR(36) DEFAULT NULL`
--      理由一: 物理主键不进 record（本项目约定）, 记忆行插入前拿不到生成键, 无法填进来。
--      理由二: 原列是 NOT NULL, 但 `GUARD_REJECT`（拒写）**没有对应的记忆行** ——
--              在 NOT NULL 约束下只能编一个假 id, 那比留空更糟。故改为可空。
--   ② 新增 `entity_type`（FACT/SUMMARY）
--      会话摘要（RAG-07 §5.5）的变更同样要审计, 但它是另一个实体。
--      **一张审计表带判别列, 好过两张几乎相同的表** —— 这也让 #13 将来能直接复用本表。
--
-- ⚠️ 哈希链的诚实边界（必须讲清楚, 否则是过度承诺）：
--   · 链只在**同一个数据库内**串行化（靠追加时的 SELECT ... FOR UPDATE）
--   · 有 DB 写权限的攻击者可以整条重写 ⇒ 它只能 **tamper-evident（可发现篡改）**,
--     不是 **tamper-proof（防篡改）**
--   · 日志与业务行在**同一个事务**里 ⇒ 应用层可以"不记"。真正的生产解法是
--     日志表单独授权（只授 INSERT）或触发器 / WORM 存储, 本阶段不做（记为缺口）
--   · 低成本补偿：每次追加后把 head hash 打一条 INFO 日志, 等于一个外部锚点
--
-- ⚠️ 执行方式：必须显式 `mysql -u root --default-character-set=utf8mb4 < 本文件`
--    —— 理由见 sql/user_memory.sql 头部（Windows CLI 默认 gbk, 会把中文注释存成乱码）。
-- =============================================================================

USE `fan_ai_agent`;

CREATE TABLE IF NOT EXISTS `memory_change_log` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键。⚠️ 不参与哈希计算(插入前拿不到), 链的行序由 prev_hash 显式给出; 全序即本列的 ASC',
    -- 被变更的实体：事实 or 会话摘要
    `entity_type`   VARCHAR(16)     NOT NULL DEFAULT 'FACT' COMMENT 'FACT(user_memory) / SUMMARY(session_summary)',
    -- ⚠️ 可空是**必需的**: GUARD_REJECT(拒写)的候选从未成为一条记忆, 没有键可指。
    --    在原来的 NOT NULL 约束下只能编一个假 id —— 那比留空更糟。
    `fact_uid`      CHAR(36)        DEFAULT NULL COMMENT '被变更的记忆/摘要业务键; GUARD_REJECT 时为 NULL',

    `action`        VARCHAR(24)     NOT NULL COMMENT 'WRITE/SUPERSEDE/GUARD_REJECT（本阶段用到这三个; EXPIRE/STALE/USER_DELETE 属阶段 4）',
    -- before/after 的内容约定(§四)：
    --   WRITE        : before=NULL, after=新行关键字段
    --   SUPERSEDE    : before=旧行变更前, after=旧行变更后(含 valid_to/superseded_by)
    --   GUARD_REJECT : before=NULL, after=**只放 {factKey, pattern, valueLength}, 绝不回写被拒的值**
    --                  ⚠️ 否则等于把凭证又抄了一遍 —— 审计本身成了泄露面
    `before_json`   JSON            DEFAULT NULL COMMENT '变更前快照。⚠️ 哈希前先规范化(MySQL 的 JSON 列会重排键、抹空白)',
    `after_json`    JSON            DEFAULT NULL COMMENT '变更后快照。同上, 校验时用同一个规范化函数, 保证幂等',

    -- 谁改的 ----------------------------------------------------------------
    -- 必须区分 —— "模型自己改的"在审计里是一回事, 人改的是另一回事（合规要点）。
    `actor_type`    VARCHAR(16)     NOT NULL COMMENT 'USER(用户)/AUTO(模型自动)/SWEEP(巡检任务)/ADMIN(管理员)',
    `actor_id`      VARCHAR(32)     DEFAULT NULL COMMENT '操作人工号(actor_type=USER/ADMIN 时)',
    `reason`        VARCHAR(255)    DEFAULT NULL COMMENT '变更原因(如 REFRESH_SAME_VALUE / 具名拒绝理由)',

    -- 哈希链 ----------------------------------------------------------------
    -- hash = SHA256hex( join(U+001F, [prevHash, entityType, factUid, action, actorType,
    --                            actorId, reason, canonicalJson(beforeJson),
    --                            canonicalJson(afterJson), createdAt] )
    -- ⚠️ `entityType` 是对 §A.3 的一处**补漏**：A.3 给的输入列表里没有它, 而本表
    --    在本阶段把它加成了判别列。不算进去的话, "把一条摘要的审计记录改成事实的"
    --    这种改动不会破坏链 —— 一处很小但确实存在的完整性漏洞。
    -- 实现在 org.agent.memory.dao.MemoryChangeLogRow#payload()（顺序的唯一出口）,
    -- 哈希与校验在 org.agent.audit.AuditHashChain。
    -- 四个必须讲清的点（实现见 org.agent.audit.AuditHashChain）：
    --   ① U+001F 分隔符不是装饰：无分隔拼接会让 ("ab","c") 与 ("a","bc") 哈希相同
    --      —— 哈希链的经典缺陷, 有一条单测锁死
    --   ② 不含 id：插入前拿不到生成键（同 fact_uid 的理由）
    --   ③ createdAt **由 Java 提供**（不是靠本列的 DEFAULT）：否则时间戳未受保护,
    --      攻击者可随意改"何时记录的"。列上仍留 DEFAULT, 手工插入照常可用
    --   ④ JSON 必须先规范化再哈希：MySQL 的 JSON 列会重排对象键、抹掉空白,
    --      按"写入时那个字符串"算哈希, 事后 SELECT 回来重新校验会**假失败**
    --      —— 这条最容易踩, 有专门一条测试（MemoryChangeLogDaoContextTest）
    --      ⚠️ 而且它的排键规则**不是字典序**, 实测是"键短的在前, 同长再按字典序"：
    --         {"alpha":1,"zeta":2} 读回来是 {"zeta": 2, "alpha": 1}
    --      所以"用 TreeMap 排出字典序就当它规范了"这个最自然的假设**恰好是错的**。
    --      正确做法是两边都过同一个 canonicalJson, 由我们自己排键, 不依赖 DB 的顺序
    `prev_hash`     CHAR(64)        DEFAULT NULL COMMENT '上一条的 hash(NULL = 链首)',
    `hash`          CHAR(64)        NOT NULL COMMENT '本条内容的 SHA-256(含 prev_hash)',

    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间。⚠️ 哈希用的是 Java 传入的值, 不是这个默认值',

    PRIMARY KEY (`id`),
    KEY `idx_memory` (`fact_uid`, `created_at`),
    KEY `idx_entity_action` (`entity_type`, `action`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-记忆变更审计(append-only + 哈希链)';

-- -----------------------------------------------------------------------------
-- 升级：若从 RAG-07 §A.3 的草案版建过表（执行前请先备份）
-- -----------------------------------------------------------------------------
-- ALTER TABLE `memory_change_log`
--   ADD COLUMN `entity_type` VARCHAR(16) NOT NULL DEFAULT 'FACT'
--     COMMENT 'FACT(user_memory) / SUMMARY(session_summary)' AFTER `id`,
--   MODIFY COLUMN `memory_id` CHAR(36) DEFAULT NULL COMMENT '被变更的记忆/摘要业务键; GUARD_REJECT 时为 NULL';
-- -- 建议同时把列名改成语义一致的 `fact_uid`：
-- -- ALTER TABLE `memory_change_log` CHANGE COLUMN `memory_id` `fact_uid` CHAR(36) DEFAULT NULL;
--
-- 生产加固（本阶段不做, 记为缺口）：
--   给应用账号只授 INSERT / SELECT, 或改成触发器写入 —— 那才是真正的防篡改。
--   当前应用可以"不记", 链只能发现篡改, 不能阻止。
-- -----------------------------------------------------------------------------
