-- =============================================================================
-- 银行内部 RAG 智能助手 · 会话表 DDL（精简版 v2）
-- 数据库 : MySQL 8.0+ / InnoDB / utf8mb4
--
-- 设计原则：只留"权限信封 + 会话生命周期"两类职责，砍掉与记忆/审计重复的字段。
--   已移除(相对 v1)：
--     · conversation_context_json / context_encrypted / context_message_count
--         —— 对话记忆由 Spring AI MessageWindowChatMemory 负责，会话表不存第二份上下文(避免 PII 重复落库)
--     · last_question / last_answer_summary / last_retrieved_docs_json /
--       last_confidence / refusal_count / last_refusal_reason / is_masked
--         —— "最近一次请求"粒度，归 rag_assistant_audit_log(逐请求审计表, 待建)
--     · model_version / embedding_model_version / prompt_version
--         —— 答案级可复现信息，同样归审计表
--   保留但语义明确：
--     · dept_code = 本人所属部门(审计)；org_path = 授权范围(检索过滤用, 可含子机构)
--     · 预留位仅保留会话级字段(高风险/复核/防劫持/合规留存)，不占存储成本
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `fan_ai_agent`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `fan_ai_agent`;

CREATE TABLE IF NOT EXISTS `rag_assistant_session` (
    -- 基础标识 ---------------------------------------------------------------
    `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    `session_id`            CHAR(36)        NOT NULL COMMENT '会话唯一ID(UUID, 对应 Spring AI CONVERSATION_ID)',
    `last_trace_id`         CHAR(32)        DEFAULT NULL COMMENT '最近一次请求的审计 trace_id(明细在审计表)',

    -- 身份 / 权限域（权限信封：会话创建时从 IAM 快照, 构成"数据锁"） -------------
    `user_id`               VARCHAR(32)     NOT NULL COMMENT '员工工号(IAM 主体, 权限判定主键)',
    `user_name_hash`        VARCHAR(64)     DEFAULT NULL COMMENT '员工姓名 SHA-256 哈希(禁止明文 PII, 供审计反查)',
    `role_code`             VARCHAR(32)     NOT NULL COMMENT '角色编码(柜员/风控/合规/研发等)',
    `rank_code`             VARCHAR(32)     DEFAULT NULL COMMENT '职级编码',
    `dept_code`             VARCHAR(32)     NOT NULL COMMENT '本人所属部门编码(审计用)',
    `org_path`              VARCHAR(255)    DEFAULT NULL COMMENT '授权范围组织路径(如 0100/0101, 检索过滤用, 可含子机构)',
    `data_clearance_level`  TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '可访问数据密级上限(1公开/2内部/3秘密/4机密/5绝密, 参照 JR/T 0197-2020)',
    `access_scope_json`     JSON            DEFAULT NULL COMMENT '权限范围快照: {"kb_ids":[]} — 检索只注入这些分片',
    `auth_version`          BIGINT          NOT NULL DEFAULT 0 COMMENT 'IAM 权限版本号(会话创建时快照; 变化须重鉴权/失效会话, 防 TOCTOU)',
    `permission_checked_at` DATETIME        DEFAULT NULL COMMENT '最近一次权限复核时间(TOCTOU 审计)',
    `permission_source`     VARCHAR(32)     NOT NULL DEFAULT 'IAM' COMMENT '权限快照来源(IAM/MOCK), 审计用',
    `auth_fail_count`       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '连续校验失败次数(>=3 锁定会话, 防重放爆破)',

    -- 会话状态 ---------------------------------------------------------------
    `session_status`        TINYINT         NOT NULL DEFAULT 1 COMMENT '会话状态: 1活跃 2空闲超时 3正常关闭 4强制终止(越权/异常) 5已过期',
    `topic`                 VARCHAR(255)    DEFAULT NULL COMMENT '会话主题(首轮问题自动生成)',

    -- 高风险 / 决策边界(AI 只出材料, 最终决策永远是人) --------------------------
    `is_high_risk_topic`    TINYINT         NOT NULL DEFAULT 0 COMMENT '是否命中高风险场景(资金交易/信贷审批/资产评估/承保理赔/风险管理)',
    `manual_review_status`  TINYINT         NOT NULL DEFAULT 0 COMMENT '人工复核状态: 0无需 1待复核 2复核通过 3复核拒绝',
    `manual_reviewed_at`    DATETIME        DEFAULT NULL COMMENT '人工复核时间',
    `manual_reviewer_id`    VARCHAR(32)     DEFAULT NULL COMMENT '复核人工号',

    -- 会话安全 / 资源锁 ---------------------------------------------------------
    `client_ip`             VARCHAR(64)     DEFAULT NULL COMMENT '来源 IP(审计+绑定校验)',
    `channel`               VARCHAR(32)     DEFAULT NULL COMMENT '访问渠道(web/移动/桌面/API)',
    `device_fingerprint`    VARCHAR(255)    DEFAULT NULL COMMENT '设备指纹(防会话劫持, 来源异常即终止)',
    `session_token_hash`    VARCHAR(64)     DEFAULT NULL COMMENT '会话令牌 SHA-256(不存明文 token)',
    `quota_used`            INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '本会话已用调用次数',
    `quota_limit`           INT UNSIGNED    NOT NULL DEFAULT 100 COMMENT '本会话调用次数上限(超限熔断转人工)',

    -- 生命周期 / 合规 ------------------------------------------------------------
    `is_deleted`            TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    `deleted_at`            DATETIME        DEFAULT NULL COMMENT '逻辑删除时间',
    `data_retention_until`  DATETIME        DEFAULT NULL COMMENT '数据保留截止(留存不低于业务存续期, 到期归档/销毁)',
    `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `last_active_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    `expires_at`            DATETIME        DEFAULT NULL COMMENT '空闲超时/绝对过期时间',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_trace_id` (`last_trace_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_role_dept` (`role_code`, `dept_code`),
    KEY `idx_org_path` (`org_path`),
    KEY `idx_status_expires` (`session_status`, `expires_at`),
    KEY `idx_last_active` (`last_active_at`),
    KEY `idx_created_at` (`created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-会话表(权限信封+生命周期)';

-- -----------------------------------------------------------------------------
-- 已有旧表(v1)升级：删除不再使用的列（已确认当前代码均未引用；执行前请先备份）
-- ALTER TABLE `rag_assistant_session`
--   DROP COLUMN `conversation_context_json`,
--   DROP COLUMN `context_encrypted`,
--   DROP COLUMN `context_message_count`,
--   DROP COLUMN `last_question`,
--   DROP COLUMN `last_answer_summary`,
--   DROP COLUMN `last_retrieved_docs_json`,
--   DROP COLUMN `last_confidence`,
--   DROP COLUMN `refusal_count`,
--   DROP COLUMN `last_refusal_reason`,
--   DROP COLUMN `is_masked`,
--   DROP COLUMN `model_version`,
--   DROP COLUMN `embedding_model_version`,
--   DROP COLUMN `prompt_version`;
-- -----------------------------------------------------------------------------
