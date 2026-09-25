/**
 * 入库侧：文档 → front-matter 解析 → 切分 → chunk 元数据注入 → 校验 → 写向量库。
 *
 * <h2>入口与主线</h2>
 * {@link org.agent.rag.ingest.RagDocumentIngestService} 是唯一编排点，
 * 分<b>两阶段</b>（这是它类注释里反复强调的设计，改动前先读）：
 * <ol>
 *   <li><b>收集</b>：把目录下全部文档解析成待入库单元（含 manifest 展开）。
 *       任一文档元数据不合法 → <b>整体中止</b>，向量库里不会留下半份知识库</li>
 *   <li><b>收敛 + 写入</b>：按 {@code doc_id} 做版本收敛
 *       （{@link org.agent.rag.ingest.DocumentVersionResolver}），推出旧版本失效日，再逐个写入</li>
 * </ol>
 * 分两阶段是<b>必须</b>的，不是风格选择：要判断"哪一版是现行的"，就得先看见同一
 * {@code doc_id} 的<b>全部</b>版本。边扫边写的话，先写入的那版还不知道后面有更新的版本存在，
 * 失效日无从推导。
 * <p>
 * 旧版本<b>照常入库、不删除</b>，靠时效区间把它挡在现行口径之外 —— 这样"当时该按哪版办"
 * 才查得回来。实现是 {@link org.agent.rag.TemporalGate}。
 *
 * <h2>⚠️ "权限预检"不是鉴权</h2>
 * 本包的 {@code validate()} 校验 {@code kb_id} / {@code doc_level} / {@code org_codes}
 * 三个元数据齐全且合法，注释里叫"权限预检"。但那是<b>元数据完整性校验</b>，
 * 不是会话鉴权 —— 入库是离线批处理，<b>本包不 import {@code org.agent.permission}，
 * 也不认识 {@code SessionEnvelope}</b>。这三个字段的作用是"给检索层的 filter 备好原料"。
 *
 * <h2>依赖方向</h2>
 * 只依赖 {@link org.agent.rag.TemporalGate}（本包之外、{@code rag} 根之下）。
 * <b>与 {@code rag.retrieve} 之间没有任何 Java 依赖</b> —— 两侧通过向量库里的
 * metadata 字段约定通信。
 *
 * <h2>读代码顺序</h2>
 * {@code RagDocumentIngestService}（两阶段主线）→ {@code DocMetadataParser}（front-matter）
 * → {@code MarkdownChunker}（按标题切分）→ {@code DocumentVersionResolver}（版本收敛，
 * 逻辑最绕的一块）→ {@code CodeSniffer}（判定 {@code is_code}）。
 */
package org.agent.rag.ingest;
