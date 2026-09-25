-- =============================================================================
-- 银行内部 RAG 智能助手 · L1 情景记忆 DDL（chat_message）
-- 数据库 : MySQL 8.0+ / InnoDB / utf8mb4
-- 依据   : 《银行RAG-07-记忆分层与断点续传设计》§二 L1 / 附录 A.1
--
-- 一句话：存"结论 + 指针", 绝不存内容。
--   存  : 用户原话 / 最终答案全文 / 引文指针(doc_id + doc_version + chunk_id)
--   不存: RetrievalAdvisor.augment() 渲染出的那段带检索原文的 prompt
--         理由有四(§二 L1):
--           ① 回看时能用**当前**权限按指针重取原文, 取不到就如实说"你已无权查看";
--              存了渲染后的 prompt 就做不了这个判断(原文已跟着历史走)
--           ② 存档体积: 一条 10KB 答案可能对应 50KB 原文, 且每轮重复
--           ③ 堵死密级混洗: 低密级问题无法从历史里"继承"高密级原文
--           ④ 保住引用归因链: 数字必须能回原文核对, 摘要承载不了事实
--
-- 为什么不存 is_deleted：§四 的删除策略是 supersede + TTL 到期归档, 不做逻辑删除。
--
-- 本表相对 §A.1 的三处调整（都是收敛, 不是扩展）：
--   ① 删掉 idx_session_created —— 与 uk_session_turn 的前缀重复
--      (turn_no 单调递增 ≈ created_at 单调递增), 留着只是多一份写放大
--   ② answer_decision 只放 PROCEED/REFUSED 两个值, 精确原因归 refusal_reason
--      (一个字段一个职责, 避免两个真相源漂移)
--   ③ answer_decision 加 NOT NULL DEFAULT 'PROCEED'
--
-- ⚠️ clearance_level 的 DEFAULT 取 5(最高密级) 而非 1 —— 这是刻意的, 不是笔误。
--    理由见列注释与文末"升级"段: 一个默认值同时管住"迁移回填"与"漏写 fail-closed"。
-- =============================================================================

USE `fan_ai_agent`;

CREATE TABLE IF NOT EXISTS `chat_message` (
    -- 基础标识 ---------------------------------------------------------------
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键(不进 record, 与会话表一致)',
    `session_id`        CHAR(36)        NOT NULL COMMENT '所属会话(读取窗口的第一道条件)',
    `user_id`           VARCHAR(32)     NOT NULL COMMENT '归属人工号(冗余存储避免 join; 跨用户泄露的兜底校验)',
    `turn_no`           INT UNSIGNED    NOT NULL COMMENT '会话内第几轮(从1递增; 拒答轮不跳号; 回看/将来续传的锚点)',
    `role`              VARCHAR(16)     NOT NULL COMMENT 'user / assistant; 一轮 = 两行, 共享同一个 turn_no',

    -- 本轮内容：用户原话与最终答案 ---------------------------------------------
    `user_message`      TEXT            DEFAULT NULL COMMENT '用户原话(role=user; 必须是未被 augment 增强的那一份)',
    `answer_text`       TEXT            DEFAULT NULL COMMENT '最终答案全文(role=assistant; 拒答时存拒答文案)',

    -- 引文指针：存指针不存内容, 回看时按当前权限重新取原文 ------------------------
    -- doc_version 是必需的快照: 原文可能已出新版, 展示要能标注"该轮引用 v3, 当前已有 v5"
    `cited_chunk_refs`  JSON            DEFAULT NULL COMMENT '[{"doc_id","doc_version","chunk_id"}] 引文指针, 非原文',

    -- 轮级字段：同一轮两行值相同, 这样"整轮"在 SQL 里是一个可判定的单位 ----------
    `answer_decision`   VARCHAR(24)     NOT NULL DEFAULT 'PROCEED' COMMENT '本轮结论: PROCEED / REFUSED(细化原因见 refusal_reason)',
    `refusal_reason`    VARCHAR(255)    DEFAULT NULL COMMENT '拒答原因(NO_HIT/PERMISSION_FILTERED/TEMPORAL_FILTERED/KB_UNAVAILABLE/UNVERIFIED_NUMBER)',
    `biz_date`          DATE            DEFAULT NULL COMMENT '本轮业务日期(时效判定的上下文; 回看要能回答"这轮按哪天判的")',

    -- 权限双列：一个管"能不能读", 一个只留痕 -------------------------------------
    -- clearance_level  : 该轮内容可能反映的最高密级。**读取判定用**
    -- perm_auth_version: 该轮写入时的权限版本。**仅审计**, 绝不参与读取判定
    --                    (记忆可以过期, 权限不能过期 —— 见 §二 L5 / §三 D3.3)
    --
    -- clearance_level 的取值口径(阶段 1)：
    --   有检索结果 → max(该轮引用资料的 doc_level)   —— 答案可能反映其中任意一篇, 含最高那篇
    --   无检索结果 → 回退会话密级                    —— 无从判定来源, 按用户能看到的天花板封顶
    -- 读取侧按**本轮实况信封**比对(clearance_level <= envelope.dataClearanceLevel()),
    -- 与 RetrievalAdvisor.passesGate 同一把尺子, 所以权限降级后读不到旧轮 —— 这就是
    -- RAG-07 §一 表 #2「降级后仍可读」的堵法。
    --
    -- ⚠️ DEFAULT 5(最高) 是刻意的, 一个机制同时管两件事：
    --   ① 迁移回填：ALTER 时既有行一律变 5 → 旧历史谁都读不到。这是对的 ——
    --      那些行的真实密级无法回溯判定(阶段 0 没存), 宁可读不到, 也不能默认成 1 放行
    --   ② fail-closed：将来若有别的写入路径漏了这列, 漏掉的行是"谁都读不到",
    --      而不是"谁都能读到"。该行为由 DAO 测试锁住
    `clearance_level`   TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '该轮内容可能反映的最高密级(读取判定用; 无引用资料时=会话密级)',
    `perm_auth_version` BIGINT          NOT NULL DEFAULT 0 COMMENT '该轮权限版本(审计用, 不参与读取过滤)',

    -- 生命周期 ---------------------------------------------------------------
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `expires_at`        DATETIME        DEFAULT NULL COMMENT '留存到期(created_at + rag.memory.retention-days; 到期归档/销毁)',

    PRIMARY KEY (`id`),
    -- 兼作"下一轮 turn_no"与"最近窗口"两个查询的索引(前缀 = session_id, 次列即排序列);
    -- 同时是并发重号的防线: 同一 session 内 (turn_no, role) 不可能重复
    UNIQUE KEY `uk_session_turn` (`session_id`, `turn_no`, `role`),
    KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-情景记忆(消息流水, 只存结论与指针)';

-- -----------------------------------------------------------------------------
-- 已有旧表(阶段 0)升级：加内容侧密级列（执行前请先备份）
-- ALTER TABLE `chat_message`
--   ADD COLUMN `clearance_level` TINYINT UNSIGNED NOT NULL DEFAULT 5
--     COMMENT '该轮内容可能反映的最高密级(读取判定用; 无引用资料时=会话密级)'
--     AFTER `biz_date`;
--
-- 执行后既有行一律回填为 5 → 旧历史谁都读不到。这是**想要**的行为, 不是副作用：
-- 阶段 0 根本没存内容侧密级, 那些行的真实密级已无法回溯判定。
-- 本地练习库若不想留这批读不到的行：TRUNCATE TABLE `chat_message`;
-- -----------------------------------------------------------------------------
