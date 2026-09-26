-- =============================================================================
-- 银行内部 RAG 智能助手 · 会话摘要 DDL（session_summary）
-- 数据库 : MySQL 8.0+ / InnoDB / utf8mb4
-- 依据   : 《银行RAG-07-记忆分层与断点续传设计》§5.5（会话级断点）
--          ⚠️ §5.5 只有散文、没有 DDL —— 本文件是它的首个落地方案
--
-- 一句话：把**滑出窗口的那一段历史**压成一句定位信息, 继续留在上下文里。
--
-- 为什么需要它：L1 的窗口是**硬滑窗** —— 第 5 轮时第 1 轮就整段消失, 而"3 轮以前
-- 但很相关"的内容恰恰经常是要接着聊的那件事。摘要是这个损失的解药。
--
-- ⚠️ 但摘要本身是个**已知偏差**, 必须按 RAG-07 附录 A1 的校正来做：
--    「摘要**有损, 且破坏引用归因**。上一轮『年利率 1.35%』被摘要成『讨论了存款利率』,
--      下一轮用户问『刚才那个利率』, 模型要么答不出, 要么编一个被 NumericGuardrailAdvisor 拦掉」
--    **校正：摘要只能用来定位, 不能用来承载事实。数字按指针回原文取。**
--    **一句话钩子：摘要负责「找得到」, 原文负责「答得对」。**
--    机械化落实(不靠提示词自觉)：
--      · 提示词明令禁止记录金额/比例/期限
--      · Java 侧再用现成的 NumericExtractor 验证 —— extract() 非空即违规,
--        打回重压缩一次, 仍违规就**丢弃这份摘要**(宁可让窗口滑走, 也不让有损的数字
--        进入未来所有轮次的上下文)
--      · 之所以复用 NumericExtractor: 它的三类正是 A1 说的那三类, 而它的白名单设计
--        已经排除了文号/条款号/计数/日期 —— 所以「讨论了《XX 办法》(银保监发〔2019〕23 号)」
--        **不会误报**
--
-- ⚠️ 为什么摘要也必须**继承密级**（本文件最重要的一条）：
--    摘要把高密级轮次的内容**压缩后长期携带**。若不继承密级, 它就是一条比 L1 旁路
--    **更宽**的路 —— **一份摘要能顶好几轮**, 阶段 1 费尽力气堵的那个漏洞会被整段绕过。
--    所以:
--      · clearance_level = max(被摘要轮次的 clearance_level), 由 Java 算, **模型无权输出**
--      · 读取时按**本轮实况信封**判定, **整份摘要不过就整份丢弃**（与 L1「整轮丢弃」同一
--        手法 —— 绝不出现"半份摘要"）
--      · DEFAULT 5(最高密级), 与 chat_message / user_memory 同一套 fail-closed 理由
--
-- ⚠️ 为什么摘要要**版本化**（append-only + supersede）而不是原地 UPDATE：
--    §二 L0 那段承认「不落库」的代价是"**无法事后复现『当时模型看到了什么』**"。
--    而摘要正是**被注入模型的东西** —— 版本化的摘要就是这条缺口在记忆层的补法,
--    直接服务"审计"这根评价轴。代价只是几行 supersede 代码, 且复用的是 L2 已建的
--    同一套双时态手法, 不引入新机制。
--
-- ⚠️ 执行方式：必须显式 `mysql -u root --default-character-set=utf8mb4 < 本文件`
--    —— 理由见 sql/user_memory.sql 头部（Windows CLI 默认 gbk, 会把中文注释存成乱码）。
-- =============================================================================

USE `fan_ai_agent`;

CREATE TABLE IF NOT EXISTS `session_summary` (
    `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键(不进 record, 与其它记忆表一致)',
    `summary_uid`         CHAR(36)        NOT NULL COMMENT '业务键(Java 生成 UUID), superseded_by 与审计日志指向它',
    `session_id`          CHAR(36)        NOT NULL COMMENT '所属会话(读取窗口的第一道条件)',
    `user_id`             VARCHAR(32)     NOT NULL COMMENT '归属人工号(归属复核用; 与 L1「会话串号兜底」同一手法)',

    -- 覆盖区间：摘要恰好覆盖"滑出窗口的那一段", 与 L1 窗口无缝且不重叠 -------------
    -- 于是每一轮必然落在三者之一：窗口里 / 摘要里 / 被密级过滤掉。不存在"两边都没有"。
    `covers_from_turn_no` INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '覆盖起始轮次(含)',
    `covers_through_turn_no` INT UNSIGNED NOT NULL COMMENT '已摘要到第几轮(**不含**该轮; 即"下一轮该从哪开始")',

    -- 密级继承（见文件头"为什么摘要也必须继承密级"）-----------------------------
    `clearance_level`     TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '被摘要轮次的最高密级(读取判定用; 整份摘要按此过滤)',

    `summary_text`        VARCHAR(2000)   NOT NULL COMMENT '摘要正文(只含定位信息: 聊过哪些主题/哪份制度/结论方向; 禁止出现金额/比例/期限)',
    `source_turn_count`   INT UNSIGNED    NOT NULL COMMENT '本次折叠了几轮(审计与调试用; 也是"摘要膨胀"的观察指标)',

    -- 双时态（与 user_memory 同一套观）----------------------------------------
    `valid_from`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '何时开始为真',
    `valid_to`            DATETIME        DEFAULT NULL COMMENT '何时不再为真(NULL = 当前有效)',
    `superseded_by`       CHAR(36)        DEFAULT NULL COMMENT '被哪一版新摘要取代(指向后继的 summary_uid)',

    -- 审计与生命周期 ----------------------------------------------------------
    `perm_auth_version`   BIGINT          NOT NULL DEFAULT 0 COMMENT '写入时权限版本(审计用, 不参与读取判定)',
    `expires_at`          DATETIME        DEFAULT NULL COMMENT '留存到期(与 chat_message 同一个 retention-days 口径)',
    `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 生成列 + 唯一索引：一个会话**至多一份当前有效的摘要**
    -- 唯一索引对 NULL 不生效, 所以用生成列把"有效"压成非 NULL 值(与 user_memory.active_key 同一手法)
    `active_session_id`   CHAR(36)        GENERATED ALWAYS AS (IF(`valid_to` IS NULL, `session_id`, NULL)) VIRTUAL
        COMMENT '仅用于唯一约束: 有效行=session_id, 历史版本=NULL',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_summary_uid` (`summary_uid`),
    UNIQUE KEY `uk_session_active` (`active_session_id`),
    KEY `idx_user` (`user_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-会话摘要(compaction 产物, 只定位不承载事实)';

-- -----------------------------------------------------------------------------
-- 无旧版本可升级（本表为首次落地）。
--
-- 阶段 4 的挂载点（本阶段刻意不做）：
--   · 摘要的坏数据巡检（例如摘要与窗口覆盖区间出现缝隙 —— 理论不可能, 但要能查）
--   · 会话被删除时摘要的连带处理（本阶段靠 expires_at 与 session 一致）
-- -----------------------------------------------------------------------------
