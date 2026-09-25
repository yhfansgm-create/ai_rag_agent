# 会话表设计 —— 银行内部 RAG 智能助手

> 项目阶段：初期。本文档定义第一张业务表：`rag_assistant_session`（MySQL 8.0）。
> DDL 见 [`sql/rag_assistant_session.sql`](../sql/rag_assistant_session.sql)。

## 1. 设计定位：一张表，两个职责

`rag_assistant_session` 同时承担两类职责，缺一不可：

1. **会话数据**：`session_id`、对话上下文、主题、状态、时间线，支撑记忆恢复与继续对话。
2. **权限信封（数据边界）**：把用户在会话创建那一刻的身份/角色/职级/密级/可检索分片**快照**进表。
   检索与输出只按本会话的 scope 执行 —— 这就是"三把锁"里的**数据锁**落地，
   而不是在代码里 `if(user.role=="xx")` 到处判断。

```
身份锁(IAM 认证) ──▶ 数据锁(会话表 access_scope_json 限定检索切片) ──▶ 资源锁(quota/熔断)
```

任一环失败即拒绝。会话表是"数据锁"的持久化载体。

## 2. 字段分组说明

| 分组 | 字段 | 要点 |
|---|---|---|
| 基础标识 | `id` / `session_id` / `last_trace_id` | `session_id` 与 Spring AI `CONVERSATION_ID` 对齐；`trace_id` 每次请求新生成 |
| 身份/权限域 | `user_id` / `user_name_hash` / `role_code` / `rank_code` / `dept_code` / `org_path` / `data_clearance_level` / `access_scope_json` / `auth_version` / `permission_checked_at` / `permission_source` / `auth_fail_count` | 会话创建时从 IAM 快照；姓名只存哈希；`permission_source` 记录快照来源(IAM/MOCK)；`auth_fail_count` 连续失败计数(≥3 锁定会话，防重放爆破) |
| 状态 | `session_status` / `topic` | 对话内容记忆由 Spring AI `MessageWindowChatMemory` 负责，会话表不存上下文（避免与记忆重复 + PII 重复落库） |
| 高风险/决策边界 | `is_high_risk_topic` / `manual_review_status` / `manual_reviewed_at` / `manual_reviewer_id` | 命中高风险场景 → 答案强制带"AI生成，仅供参考"+ 人工复核状态机 |
| 可解释性/溯源 | （v2 已移除，迁至 `rag_assistant_audit_log`） | 逐请求的模型/embedding/提示词版本、命中文档、置信度、拒答、脱敏标记，一条请求一行，放审计表 |
| 会话安全/资源锁 | `client_ip` / `channel` / `device_fingerprint` / `session_token_hash` / `quota_used` / `quota_limit` | token 只存哈希；设备指纹防劫持；配额超限熔断 |
| 生命周期/合规 | `is_deleted` / `deleted_at` / `data_retention_until` / `created_at` / `updated_at` / `last_active_at` / `expires_at` | 逻辑删除 + 保留期（留存不低于业务存续期） |

## 3. 需要配套的表（本次未建，建议二期）

| 表 | 用途 |
|---|---|
| `rag_assistant_message`（明细） | 逐条消息 + 引文 + 是否打码 + 人工复核结论。会话表只留快照/摘要 |
| `rag_assistant_audit_log`（审计） | 每次请求一条：`trace_id`、问题、命中文档、答案、模型/版本、置信度、拒答行为。**只写不删、防篡改、接 SIEM** |
| `rag_kb_document` / `rag_kb_version`（知识库元数据） | 来源、生效/失效日期、密级、业务负责人、版本号 —— 权限过滤与版本管控的前提 |
| 现有衔接 | Spring AI `JdbcChatMemoryRepository` 已把消息落 PG/PGVector，`session_id` 即 `CONVERSATION_ID`，与 MySQL 会话表通过它关联 |

## 4. 在用户提供清单之外，本设计补充的安全要点

用户已列出的：入库前脱敏分级 / 输出打码 / 日志不落明文 / 不微调只检索 / 高风险场景准入 /
外部模型备案与 embedding 闭环 / 数据分类分级与商业秘密 / 内容标识与可解释性 /
三把锁防越权 / 提示词注入 / 审计留痕 / 引文溯源与拒答 / 业务+代码专项。

**本表额外落地的 10 条：**

1. **会话即权限信封**：`access_scope_json` + `auth_version` 在会话创建时快照，检索只注入本会话切片。
2. **权限变更熔断（TOCTOU）**：`auth_version` 与 IAM 当前版本比对，变了就强制会话失效重鉴权，
   防止"建立会话后权限悄悄扩大"。`permission_checked_at` 记录复核时点。
3. **会话令牌绑定防劫持**：`session_token_hash`（SHA-256，不存明文）+ `device_fingerprint` +
   IP 段校验；来源异常即终止会话（`session_status=4`）。
4. **空闲超时 + 绝对过期**：`expires_at`；配套运维任务扫描 `idx_status_expires` / `idx_last_active` 清理。
5. **配额熔断落地**：会话级 `quota_used` / `quota_limit`，超限转人工，防止资源锁形同虚设。
6. **会话数据最小化 + 加密存储**：上下文 JSON 内 PII 打码/仅存摘要，或应用层加密后写入（`context_encrypted` 标记）。
7. **模型/embedding/提示词版本快照**：一行内记录，任何一条答案都能复现当时的检索与生成。
8. **检索文档快照 + 置信度 + 拒答留痕**：`last_retrieved_docs_json` 快照命中文档；`last_confidence`；
   `refusal_count` + `last_refusal_reason`。防幻觉的证据链。
9. **高风险话题标记 + 人工复核状态机**：识别资金/信贷/风控话题 → `is_high_risk_topic=1`，
   答案强制"AI生成，仅供参考"，进入 `manual_review_status`（待复核→通过/拒绝）闭环。
10. **逻辑删除 + 保留期**：`is_deleted` + `data_retention_until`，满足"日志留存不低于业务存续期"，到期归档/销毁。

## 5. 落地提醒

- **PII 红线**：`user_name_hash` 只存哈希；上下文/问题/摘要字段一律脱敏或加密；审计表不落明文。
- **embedding 闭环**：`embedding_model_version` 必须指向行内部署的模型，向量库不调外部 API。
- **产品定位**：所有高置信输出带"AI生成，仅供参考"，`is_high_risk_topic=1` 时人工终审 —— 助手是辅助工具，不是决策系统。
- **检索过滤**：检索层按 `access_scope_json` 里的 `kb_ids / collections / org_codes / max_doc_level`
  对向量库做**物理/元数据过滤**（PGVector metadata filter），而非查完再前端过滤。

## 6. 权限校验实现（PermissionAdvisor）

对应代码：`src/main/java/org/agent/permission/`。持久层为 **MyBatis**：`org.agent.permission.dao.SessionDao`（注解式 `@Mapper`）+ `SessionRow`，Service 层不直接碰 SQL。

**回答"不存用户数据怎么做校验"**：身份/权限主数据在 IAM（`UserPermissionProvider` 接口），
应用只存会话表里的**版本化快照**，每次请求做三层校验，**全部 fail-closed**（任一环失败即拒绝）：

```
请求 → PermissionAdvisor(从 advisorParams 取 sessionId/sessionToken)
  ① 会话有效性  : session_status=活跃、未过期、token SHA-256 匹配（连续失败≥3 锁定会话）
  ② 快照新鲜度  : auth_version vs IAM 当前版本 → 变了重拉权限刷新快照；用户已失效→强制终止
  ③ 资源锁      : quota_used + 1 > quota_limit → 熔断转人工
  → 通过: 把 accessFilter(metadata filter DSL) + envelope 写入 ChatClient context
```

- **建会话** `POST /session` `{userId}` → 从 IAM 拉画像快照入库，返回 `sessionId` + `sessionToken`（表中只存 SHA-256）。
- **对话** `POST /session/chat` `{sessionId, sessionToken, message}` → 过 PermissionAdvisor，非法请求 401/403。
- **权限来源**：`MockUserPermissionProvider`（内置 10086 柜员 / 10087 风控；`bumpAuthVersion()` 模拟权限变更）。接真实 IAM 时实现 `UserPermissionProvider` 接口即可，业务代码零改动。
- **检索层消费**：真正做 RAG 时，检索 advisor 从 context 读 `org.agent.permission.accessFilter`，
  下推给向量库 `SearchRequest.builder().query(q).filterExpression(accessFilter)` —— 数据锁在检索层执行，不是查完再前端过滤。
- **认证层**（本次未做）：真实环境在入口 Filter/网关用 IAM 换发 token，再把身份注入；本模块只负责授权（授权边界）。
