# RAG 知识库文档模板

> 用途：给准备投喂给 RAG 的文档标注"谁能看、能看多密"的关键信息，检索层据此过滤，实现防越权。
> 配套阅读：`会话表设计-银行RAG助手.md` 的"权限校验实现"章节。

---

## 一、核心原理（30 秒看懂）

文档标注的元数据 → 切分时提取进每个 chunk（向量库 metadata）→ 检索时与会话权限比对：

```
kb_id ∈ 用户可检索知识库   &&   doc_level <= 用户密级上限   &&   用户组织路径 ∈ 文档 org_codes
```

任一条件不满足 → 该 chunk 不会被检索到 → 不会喂给 LLM → **不越权**。

**关键提醒：标注必须入库时提取进向量库 metadata，只写在文档头上、不提取，等于白标。**

---

## 二、Markdown 文档模板（推荐，头部 front-matter）

```markdown
---
kb_id: kb-ops                 # 知识库分片 ID                     ← 必须
doc_id: OPS-MANUAL-2024-007   # 文档唯一 ID                       ← 建议
title: 生产系统变更管理规范
doc_type: manual              # manual / policy / script / code
doc_level: 3                  # 密级 1公开/2内部/3秘密/4机密/5绝密  ← 必须
org_codes:                    # 显式授权组织清单                   ← 必须
  - "0100/0100"               # 总行运维中心的组织路径
owner: 李四-总行运维中心        # 业务负责人                        ← 建议
source: 运维中心制度库
effective_from: 2024-03-01    # 生效日期
effective_until: null         # null = 长期有效
version: "1.2"                # 版本号
is_code: false                # 是否代码类(商业秘密, 最高一档)
---

# 一、适用范围
本规范适用于总行运维中心……

# 二、变更流程
2.1 变更申请……
2.2 变更审批……
```

### 填表示例（总行运维文档 / 分行研发用户）

| 文档标注 | 分行研发用户的会话权限 | 结果 |
|---|---|---|
| `kb_id: kb-ops` | kbIds=`[kb-rd]` | `kb_id` 不匹配 → 第一道挡掉 |
| `doc_level: 3` | clearance=`2` | `3 <= 2` 不成立 → 第二道挡掉 |
| `org_codes: ["0100/0100"]` | orgPath=`0201/0105` | 不含 → 第三道挡掉 |
| **三道全挡 → 该文档一块都不会被检索到** | | ✅ 不越权 |

---

## 三、非 Markdown（Word / PDF）模板 —— 入库清单 manifest

Word/PDF 写不了 front-matter，把标注放到一份入库清单里，入库时按文件匹配：

```yaml
documents:
  - file: ops/生产系统变更管理规范.docx
    kb_id: kb-ops
    doc_id: OPS-MANUAL-2024-007
    doc_type: manual
    doc_level: 3
    org_codes: ["0100/0100"]
    owner: 李四-总行运维中心
    source: 运维中心制度库
    effective_from: "2024-03-01"
    effective_until: null
    version: "1.2"

  - file: risk/信贷审批授权管理办法.pdf
    kb_id: kb-risk
    doc_id: RISK-POLICY-2023-002
    doc_type: policy
    doc_level: 4
    org_codes: ["0100/0202"]
    owner: 王五-风险管理部
    source: 风险管理部制度库
    effective_from: "2023-06-01"
    effective_until: null
    version: "3.0"
```

---

## 四、字段说明

### 防越权最小契约（三个，缺一不可）

| 字段 | 类型 | 作用 | 例子 |
|---|---|---|---|
| `kb_id` | string | 知识库分片归类，第一道闸 | `kb-ops` / `kb-risk` / `kb-rd` |
| `doc_level` | int 1-5 | 密级上限（JR/T 0197-2020） | `3` |
| `org_codes` | list | **显式授权组织清单**，谁可见就列谁 | `["0100/0100"]` |

> `org_codes` 必须是显式白名单，别靠组织树推导"上级可见=下级可见"，否则一疏忽就把子机构文档放给了全行。

### 建议字段（版本管控 + 审计溯源）

| 字段 | 作用 |
|---|---|
| `doc_id` / `title` / `version` | 唯一标识 + 版本，防止引旧规 |
| `effective_from` / `effective_until` | 政策时效（制度废改立） |
| `source` / `owner` | 审计溯源（哪份制度、谁负责） |
| `doc_type` | manual/policy/script/code |
| `is_code` | 代码类=商业秘密，最高密级 + 独立分片 |

---

## 五、文档内容红线（入库前自查）

1. **一个部门一个文件，别混密** —— 总行机密和分行公开内容别放同一篇，密级取最高会把公开部分也锁死
2. **正文不写 PII** —— 客户姓名/身份证/银行卡/手机号/员工花名册，一律不写；写了只能脱敏或整篇拒入
3. **正文不写密钥** —— 数据库密码、生产连接串、内网 IP、接口 token 严禁入库
4. **别写可执行的指令** —— 例如"你可以修改系统状态""执行该命令"，容易诱导模型越权动作
5. **版本号写清楚** —— 防止 AI 引用已废改的旧规
6. **按章节组织正文** —— 切分后能溯源到章节出处（引文溯源 / 审计）

---

## 六、入库动作链（标注怎么变成检索过滤）

```
文档 → ① 分级审批(填好上面的 front-matter/manifest, 负责人确认)
     → ② PII/密钥扫描(命中即拒入或脱敏)
     → ③ 提示词注入清洗
     → ④ 按标题切分, 每个 chunk 提取元数据(带 section 章节路径)
     → ⑤ 权限预检(缺 kb_id/doc_level/org_codes 任一 → 拒入)
     → ⑥ 只入当前生效版本
     → ⑦ 写入 PGVector(metadata 一起存)
```

检索侧：`PermissionAdvisor` 产出 `accessFilter` → 检索 advisor 下推 `SearchRequest.filterExpression(...)` → 向量库按 metadata 物理过滤，查不到就答不出来。
