-- =============================================================================
-- 银行内部 RAG 智能助手 · L2 语义记忆 DDL（user_memory）
-- 数据库 : MySQL 8.0+ / InnoDB / utf8mb4
-- 依据   : 《银行RAG-07-记忆分层与断点续传设计》§二 L2 / §三 / §四 / 附录 A.2
--
-- 一句话：存"用户自述的稳定事实", 每条都带密级、带保质期、可被取代、可被审计。
--
-- 与 L1 的三条本质区别（决定了本表的形状）：
--   ① L1 是"按时间铺开"的流水, L2 是"按主题收拢"的画像 —— 所以主键是
--      (owner, scope, fact_key) 而不是 (session, turn_no)
--   ② L1 是**审计记录**, 所以一条都不许拒写; L2 是**加工产物**,
--      所以可以且必须拒写（内容红线 / 需确认档 / 复述验证不过 / 配额已满）
--   ③ L1 从不 supersede（历史事实不会变假）, L2 会被新事实取代 —— 见"双时态"段
--
-- ⚠️ 无源不写：source_session_id + source_turn_no 都 NOT NULL。
--    每条记忆必须能指回具体某一轮的 L1 行。挡幻觉记忆的第一道闸, 由 DB 约束兜住。
--
-- ⚠️ 执行方式（**别**直接 `mysql -u root < 本文件`）：
--    Windows 的 mysql CLI 默认 character_set_client=gbk（跟随控制台代码页），会把本文件
--    的 UTF-8 中文注释按 GBK 解释后存成乱码。**表照样建得出来**，但 SHOW CREATE TABLE
--    里注释全是乱码 —— 而本项目的 DDL 头注释正是设计文档的一部分（见下）。
--    必须显式指定连接字符集：
--        mysql -u root --default-character-set=utf8mb4 < sql/user_memory.sql
--    校验：SELECT HEX(TABLE_COMMENT) FROM information_schema.TABLES
--          WHERE TABLE_NAME='user_memory';  -- 应以 E993B6E8A18C（"银行内部"）开头
-- =============================================================================

USE `fan_ai_agent`;

CREATE TABLE IF NOT EXISTS `user_memory` (
    -- 标识 -------------------------------------------------------------------
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物理主键(不进 record, 与 chat_message / 会话表一致)',
    -- ⚠️ fact_uid 是对 RAG-07 §A.2 的**必要偏离**, 不是偏好：
    --    A.2 让 superseded_by 指向"新记忆 id", 但本项目约定"物理主键不进 record"
    --    (见 ChatMessageDao.insertTurn 的注释), 而 record 不可变 ⇒ 插入前拿不到生成键。
    --    所以必须有一个 Java 侧生成的业务键。先例: Document.getId() 用 nameUUIDFromBytes
    --    派生确定性 UUID。附带好处: DAO 的 supersede / 续期全部按业务键定位。
    `fact_uid`          CHAR(36)        NOT NULL COMMENT '业务键(Java 生成 UUID), superseded_by 与审计日志都指向它',
    `owner_id`          VARCHAR(32)     NOT NULL COMMENT '归属人工号(scope=USER 时的读取过滤主键; 从信封取, 模型无权提供)',
    `scope`             VARCHAR(16)     NOT NULL DEFAULT 'USER' COMMENT '可见范围: USER(仅本人)/DEPT(同 org_path)。⚠️ 不开 GLOBAL(§七)',

    -- 事实本体 ---------------------------------------------------------------
    -- fact_key 存 ASCII code 而非中文标签 —— 这处偏离 A.2 的理由是**可辩护的工程理由**：
    --   中文标签活在**提示词**里, 一旦有人把「关注条线」改成「业务条线」, 旧行立刻变孤儿,
    --   而 supersede 会**静默失效**(不报错, 只是永远替代不上)。
    --   ASCII code 把"稳定标识"与"展示文本"解耦, 展示文本由 FactKey.label() 提供(单一真相源)。
    `fact_key`          VARCHAR(64)     NOT NULL COMMENT '事实键(受控词表 ASCII code, 如 business_line/preferred_org)',
    `fact_value`        VARCHAR(512)    NOT NULL COMMENT '事实值(归一化后; 多值以 / 连接; 长度与字符白名单由 FactValueNormalizer 校验)',
    -- 密级继承: = max(该轮引用资料的 doc_level), 写入时由 Java 从 TurnFacts 取, 不重算。
    -- ⚠️ 绝不能简化为"写入者密级"(§三 D2): 用户有权在一次问答里看到密级4的结论,
    --    不等于有权长期持有这条记忆。
    -- ⚠️ 不要写成 min(写入者密级, 资料密级): passesGate 已保证"资料密级 <= 写入者密级",
    --    于是 min 恒等于后者 = 空操作; 而它唯一能生效的方向恰恰是把高密级内容标低(§三 勘误)。
    -- DEFAULT 5(最高密级) 的理由与 chat_message 同: 迁移回填 + 漏写 fail-closed。
    `clearance_level`   TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '该条记忆的密级上限(读取判定用; = 该轮内容的最高密级)',
    `org_path`          VARCHAR(255)    DEFAULT NULL COMMENT 'scope=DEPT 时的可见组织路径(精确匹配, 不做前缀匹配)',

    -- 溯源 -------------------------------------------------------------------
    `source_session_id` CHAR(36)        NOT NULL COMMENT '来源会话(必填, 无源不写)',
    `source_turn_no`    INT UNSIGNED    NOT NULL COMMENT '来源轮次(必填; 指向真实存在的 L1 行, 由写入顺序保证)',
    `confidence`        DECIMAL(3,2)    DEFAULT NULL COMMENT '模型自报置信度。⚠️ 仅供人工复核排序, 本阶段不参与任何判定 —— 模型自报的置信度不是校准信号, 拿它当门禁是装饰性的安全性',

    -- 双时态(supersede 而非 delete) ------------------------------------------
    -- 旧事实标 valid_to + superseded_by, 历史可回溯。
    -- 与 DocumentVersionResolver 对制度做的"版本收敛 + 标注取代关系"是同一套观(§四)。
    --
    -- ⚠️ 「是否为当前有效」由 valid_to 表达, **不是**由 status 表达 —— 见 status 列注释。
    `valid_from`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '何时开始为真',
    `valid_to`          DATETIME        DEFAULT NULL COMMENT '何时不再为真(NULL = 当前有效)',
    `superseded_by`     CHAR(36)        DEFAULT NULL COMMENT '被哪条新记忆取代(指向后继的 fact_uid; A.2 原为物理 id, 见 fact_uid 段)',

    -- 时效 -------------------------------------------------------------------
    -- 时效事实必填 expires_at。更稳的做法是存指针不存值(§四 TTL)。
    -- v1 的四个事实键**全部**带 TTL: 真正永久稳定的事实(部门/角色/密级)已经在 L5 权限画像里了,
    -- L2 存在的意义就是**会变的事实**(RAG-07 校正记录 C1)。expires_at 由 Java 按
    -- FactKey.ttlDays 算, **模型无权输出** —— 留存策略不能交给模型判断。
    `expires_at`        DATETIME        DEFAULT NULL COMMENT '过期时间(= valid_from + FactKey.ttlDays; 到期不删, 只是不再召回)',
    `ref_doc_id`        VARCHAR(64)     DEFAULT NULL COMMENT '指向制度文档(存指针不存值; 由 Java 从该轮 citedRefs 取, 模型看不见 doc_id 故无从编造)',

    -- 生命周期与审计 ----------------------------------------------------------
    -- ⚠️ status 表达的是**生命周期/健康度**, 不承载 supersede 语义：
    --    STALE 的定义是「所依制度已废止」(见 RAG-07 §四 巡检①), 不是「被新事实取代」。
    --    被取代的行仍然是 ACTIVE —— 它只是"不再当前"(valid_to IS NOT NULL), 不是"坏"。
    --    给 STALE 塞第二个含义会直接破坏阶段 4 的巡检①。
    --    风险: 将来有人写 WHERE status='ACTIVE' 会复活被取代的行 —— 对策是 DAO 刻意
    --    **不提供**任何只按 status 筛的通用查询(接口形状防误用, 与 ChatMessageDao 同一手法)。
    `status`            VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PENDING(待确认, 本阶段不产生)/STALE(所依制度已废止)/USER_DELETED/EXPIRED',
    `last_hit_at`       DATETIME        DEFAULT NULL COMMENT '最近命中时间(巡检③ LRU 归档用; 召回时批量更新, 只更新真正注入 prompt 的那些)',
    -- ⚠️ 仅审计: 记录"这条记忆在什么权限下产生", 不参与读取判定(§三 D3)。
    `perm_auth_version` BIGINT          NOT NULL DEFAULT 0 COMMENT '写入时权限版本(审计用)',

    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- ⚠️ 生成列 + 唯一索引：把"同一 (owner, scope, fact_key) 至多一条有效行"这条不变量
    --    从 Java 层落到 DB 层。唯一索引对 NULL **不生效**(多个 NULL 互不相等!), 所以
    --    必须先用生成列把"有效"压成非 NULL 值, 才能被唯一键约束住：
    --        valid_to IS NULL → active_key = fact_key(参与唯一性)
    --        valid_to 非空    → active_key = NULL  (唯一键放行, 历史行不互相冲突)
    --    收益: 阶段 4 的巡检②「同 key 多条有效记录」从"报警"降级成"不该发生"。
    --    代价: 这是 MySQL 8 生成列语法, 本项目 DDL 里此前没有先例(照 chat_message 的
    --    uk_session_turn 思路 —— 不变量交给 DB, 别只靠应用层自觉)。
    --    并发说明: 无有效行时靠 InnoDB REPEATABLE READ 的间隙锁 + 这个唯一键双保险；
    --    ⚠️ 若将来把隔离级别降到 READ COMMITTED, 间隙锁不存在, 就只有这个唯一键兜着了。
    `active_key`        VARCHAR(64)     GENERATED ALWAYS AS (IF(`valid_to` IS NULL, `fact_key`, NULL)) VIRTUAL
        COMMENT '仅用于唯一约束: 有效行=fact_key, 历史行=NULL',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_fact_uid` (`fact_uid`),
    -- 同 owner + 同 scope + 同 key 只能有一条当前有效的记忆
    UNIQUE KEY `uk_owner_active` (`owner_id`, `scope`, `active_key`),
    KEY `idx_owner_status` (`owner_id`, `status`),
    -- 兼作 supersede 的查找路径: (owner, key, valid_to IS NULL)
    KEY `idx_fact_key` (`owner_id`, `fact_key`, `valid_to`),
    KEY `idx_ref_doc` (`ref_doc_id`),
    KEY `idx_scope_org` (`scope`, `org_path`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '银行内部RAG助手-语义记忆(蒸馏事实, 带密级继承与双时态)';

-- -----------------------------------------------------------------------------
-- 升级：若从 RAG-07 §A.2 的草案版建过表（执行前请先备份）
-- -----------------------------------------------------------------------------
-- ① 加业务键（A.2 草案没有它, 而 superseded_by 指向物理 id 在实现上不成立）
-- ALTER TABLE `user_memory`
--   ADD COLUMN `fact_uid` CHAR(36) NOT NULL COMMENT '业务键(Java 生成 UUID)' AFTER `id`,
--   ADD UNIQUE KEY `uk_fact_uid` (`fact_uid`);
--
-- ② superseded_by 从 BIGINT 改成 CHAR(36)（指向 fact_uid 而非物理 id）
-- ⚠️ 若表里已有数据, 这一句会清空既有的取代关系 —— 先备份再执行
-- ALTER TABLE `user_memory` MODIFY COLUMN `superseded_by` CHAR(36) DEFAULT NULL;
--
-- ③ 把"至多一条有效行"落到 DB 层
-- ALTER TABLE `user_memory`
--   ADD COLUMN `active_key` VARCHAR(64)
--     GENERATED ALWAYS AS (IF(`valid_to` IS NULL, `fact_key`, NULL)) VIRTUAL,
--   ADD UNIQUE KEY `uk_owner_active` (`owner_id`, `scope`, `active_key`);
--
-- ④ 阶段 4 的标注列（本阶段刻意不加 —— 没有消费方之前是投机设计）
-- ALTER TABLE `user_memory` ADD COLUMN `needs_review` TINYINT(1) NOT NULL DEFAULT 0;
-- -----------------------------------------------------------------------------
