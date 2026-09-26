package org.agent.memory.model;

import java.math.BigDecimal;

/**
 * 蒸馏器（模型）提出的候选事实 —— <b>它只是一份"申请表"，不是一条记忆</b>。
 *
 * <h2>⚠️ 这个 record 里<b>没有</b> owner / scope / 密级，这是刻意的安全设计</h2>
 * 一条记忆最终会有 {@code owner_id}、{@code scope}、{@code clearance_level} 三个决定
 * "谁能读到它"的字段，而它们<b>一个都不在模型能填的表单里</b>：
 * <ul>
 *   <li>{@code owner_id} 只从本轮<b>实况信封</b>取（模型无权指定"这条记给谁"）</li>
 *   <li>{@code scope} 恒为 {@code USER}（本阶段唯一的自动写入范围）</li>
 *   <li>{@code clearance_level} 由 {@code TurnFacts} 带进来（L1 已算好，见"一个公式一个出口"）</li>
 * </ul>
 * 这叫<b>归属由构造消解</b>：与其在写入前写一句"记得校验 owner"，不如让模型根本
 * 交不出 owner 这个字段 —— 校验写得再仔细也可能被绕过，<b>字段不存在则不可能被伪造</b>。
 * 同样的手法在 {@code ChatMessageRow} 的 {@code id} 上用过一次（物理主键不进 record）。
 * <p>
 * 唯一"多出来"的字段是 {@code confidence}，见下。
 *
 * @param rawKey     模型给出的键名。<b>未解析</b>的原始字符串 —— 可能是 code、
 *                   可能是中文标签、也可能不认识，由 {@code FactKey.resolve} 处理
 * @param rawValue   模型给出的值。<b>未归一化</b>的原始字符串
 * @param confidence 模型自报的置信度。
 *                   ⚠️ <b>仅供人工复核排序，本阶段不参与任何判定</b>：
 *                   模型自报的置信度不是校准信号（它在一件事上说自己 0.95、在另一件上
 *                   也说自己 0.95，两者并没有可比性），<b>拿它当门禁是装饰性的安全性</b> ——
 *                   看起来有阈值、有判断，实际拦不住任何东西。留着它只是为了阶段 4 的
 *                   "人工复核时按置信度排序"，既然不参与判定，也就没有伪造的收益
 */
public record FactDraft(
        String rawKey,
        String rawValue,
        BigDecimal confidence
) {
}
