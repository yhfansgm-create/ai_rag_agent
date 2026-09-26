package org.agent.memory.distill;

import java.util.List;

/**
 * 会话压缩的输入：把"滑出窗口的那一段"压成一句定位信息。
 *
 * @param previousSummary 上一版摘要；{@code null} = 这是第一版。
 *                        <b>带着它是为了让压缩是增量的</b>：不传的话，第二次压缩要么
 *                        只看新滑出的那几轮（丢掉更早的定位能力），要么把
 *                        "已被摘要过的轮次 + 新轮次"全部重读一遍（输入无谓地翻倍，
 *                        而摘要的有损性会让重复压缩越压越糊）
 * @param sourceTurns     本次要折叠进去的轮次，按轮号升序。
 *                        ⚠️ 它的覆盖区间必须与最终落库的
 *                        {@code covers_from_turn_no / covers_through_turn_no} 一致 ——
 *                        否则"摘要覆盖了哪几轮"这句话就是假的，而它正是
 *                        "每一轮要么在窗口里、要么在摘要里"这条完备性的依据
 * @param maxChars        本次摘要的字符上限。由调用方按 {@code SummaryProperties}
 *                        算出后传入（预算的算法在别处，这里只是个数字）
 */
public record CompactionRequest(
        String previousSummary,
        List<SourceTurn> sourceTurns,
        int maxChars
) {

    public CompactionRequest {
        sourceTurns = sourceTurns == null ? List.of() : List.copyOf(sourceTurns);
    }

    /** 待压缩的一轮。 */
    public record SourceTurn(
            int turnNo,
            String userMessage,
            String answerText,
            boolean answered
    ) {

        /**
         * 渲染成提示词里的一段。
         *
         * <h2>⚠️ 拒答轮<b>不给</b>答案文本，这不是省略而是拒绝</h2>
         * {@code answered == false} 时，{@code answerText} 会被<b>忽略</b>（调用方应当传 null，
         * 但这里不信任它 —— 见下）。两个理由，与 L1 拒绝把拒答轮注入 prompt 是同一套：
         * <ul>
         *   <li><b>它没有信息量</b>：拒答文案（"未在您的授权范围内检索到资料"）里
         *       没有任何可用于定位主题的内容</li>
         *   <li><b>它会强化拒答倾向</b>：把"被拒了"反复喂回模型，会让它更倾向于拒答。
         *       L1 的类注释已经论证过这一点，这里是同一个结论的第二次应用</li>
         * </ul>
         * 但"用户问了 XX 未获答复"<b>要</b>记下来：下一轮用户说"那它呢"时，
         * 这是唯一的定位线索（L1 那边这是个已知损失，摘要把这个洞补上了一半）。
         * <p>
         * ⚠️ 为防调用方传错，这里<b>主动不看</b> {@code answerText}：
         * 只要 {@code answered} 为 false 就一律渲染成"未获答复"。
         * 一个字段该不该用，由这个字段自己的语义决定，不该寄望于上游每次都记得置空。
         */
        public String renderForPrompt() {
            if (!answered) {
                return "第" + turnNo + "轮 用户：" + nullToEmpty(userMessage) + "\n（本轮未获答复）";
            }
            return "第" + turnNo + "轮 用户：" + nullToEmpty(userMessage)
                    + "\n第" + turnNo + "轮 助手：" + nullToEmpty(answerText);
        }

        private static String nullToEmpty(String text) {
            return text == null ? "" : text;
        }
    }
}
