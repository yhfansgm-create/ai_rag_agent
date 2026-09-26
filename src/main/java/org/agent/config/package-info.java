/**
 * 全局装配决定，集中放这里 —— 目前只有一条：**两套数据源谁做主**。
 *
 * <p>这个包刻意不属于任何业务模块：它管的是「整个应用怎么装起来」，
 * 而不是「某个功能怎么实现」。{@link org.agent.config.DataSourceConfig} 里写了
 * 为什么业务库必须显式标 {@code @Primary}（不标就会出现「应用能启动、但业务表全写进向量库」
 * 那种无声故障）。
 *
 * <p>与本包平级的业务包：{@code api}（门面/Controller）· {@code permission}（权限模型）·
 * {@code rag}（检索与入库）· {@code memory}（记忆分层）· {@code audit}（审计哈希链）·
 * {@code support}（小工具）· {@code playground}（学习用样例）。
 * 依赖方向严格单向，本包**不依赖**上面任何一个。
 */
package org.agent.config;
