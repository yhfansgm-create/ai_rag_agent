# RAG 录入测试文档集（覆盖矩阵说明）

> 用途：按 `docs/RAG知识库文档模板.md` 的录入要求反向生成的一套测试数据，
> 用于覆盖入库链路各维度：**密级 / 分片 / 文档类型 / 组织授权 / 代码块识别 / 切块 / 时效 / 拒入边界**。
> 内容均为编造的银行测试数据，**不作为真实业务依据**。

## 目录结构

```
kb-test-data/
├── valid/                     # 合法文档：可直接被 ingestDir() 扫描入库
│   ├── public-...-密级1.md
│   ├── teller-...-密级2.md
│   ├── loan-...-密级3.md
│   ├── risk-...-密级4.md
│   ├── core-...-密级5.md
│   ├── ops-...-含代码块-密级3.md
│   ├── rd-...-密级3.md
│   ├── aml-...-密级4.md
│   ├── ops-...-脚本-密级4-is_code.md
│   ├── hr-...-密级2.md
│   ├── rd-...-波浪号代码块.md
│   ├── teller-...-已过期.md
│   ├── product-...-单值org.md
│   └── rd-...-标记代码无围栏.md
├── invalid/                   # 拒入夹具：应被入库校验拒绝
│   ├── missing-kb-id.md
│   ├── missing-doc-level.md
│   ├── missing-org-codes.md
│   ├── doc-level-0.md
│   ├── doc-level-6.md
│   ├── empty-org-codes.md
│   ├── missing-frontmatter.md
│   ├── unclosed-frontmatter.md
│   ├── empty-frontmatter.md
│   ├── manifest.bad-level.yml
│   └── manifest.missing-file.yml
└── manifest/
    ├── manifest.subdir-test.yml      # manifest 入库 + 子目录 file 路径
    └── referenced/旧版...md           # manifest 引用的文件（无 front-matter）
```

> ⚠️ **不要把 invalid/ 或 manifest/ 拷进 kb/ 之后还不修正**：
> `ingestDir()` 扫描整个 kb 目录，任何一个坏文档会让**整批**入库中断（fail-closed）。
> 想验证"单个坏文档被拒入"时，用单元测试或单独 ingestFile 调用，不要混进正常目录。
