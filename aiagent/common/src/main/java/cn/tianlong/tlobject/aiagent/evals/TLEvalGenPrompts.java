package cn.tianlong.tlobject.aiagent.evals;

/**
 * 用例生成器用的两段提示词。
 *
 * 单独成类而不是塞进模块：判据策略是这套生成器的核心——提示词里那四条规则决定了
 * 产出的是"能发现退化的用例"还是"全绿但没意义的用例"，所以要能单独读、单独改。
 *
 * 创建日期：2026/9/19
 * 作者:tianlong
 */
public class TLEvalGenPrompts {

    private TLEvalGenPrompts() {}

    /** 第一次调用：材料 + 要求 → 能力点清单 */
    public static String buildAnalysisPrompt(String agentName, String materials, String requirement) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在为一个 AI Agent 设计评测方案。\n\n");
        sb.append("## 被测对象\n").append(agentName).append("\n\n");
        sb.append("## 已知材料\n").append(materials == null ? "" : materials).append("\n\n");
        if (requirement != null && !requirement.trim().isEmpty()) {
            sb.append("## 本次评测要求（优先覆盖）\n").append(requirement.trim()).append("\n\n");
        }
        sb.append("## 任务\n");
        sb.append("列出这个 Agent 值得评测的能力点。每个能力点给出三件事：\n");
        sb.append("1. 能力描述（一句话）\n");
        sb.append("2. 预期行为：什么算对、什么算错（要具体到能判断）\n");
        sb.append("3. 建议判据类型：constraint / exact_match / llm_judge\n\n");
        sb.append("必须覆盖三类：正常路径、边界情况、**不该做的事**——"
                + "负向用例（例如\"信息不足时不该瞎报价\"）最有价值，也最容易被漏掉。\n\n");
        sb.append("最多 6 个能力点，按重要性排序。只输出清单本身，每条一行，格式：\n");
        sb.append("能力点: <描述> | 预期: <可判定的行为> | 判据: <类型>\n");
        return sb.toString();
    }

    /** 第二次调用：能力点清单 → 用例 JSON 数组 */
    public static String buildCasesPrompt(String agentName, String analysis, String requirement) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在为一个 AI Agent 生成评测用例。\n\n");
        sb.append("## 被测对象\n").append(agentName).append("\n\n");
        sb.append("## 已确定的能力点\n").append(analysis == null ? "" : analysis).append("\n\n");
        if (requirement != null && !requirement.trim().isEmpty()) {
            sb.append("## 本次评测要求\n").append(requirement.trim()).append("\n\n");
        }

        sb.append("## 输出格式\n");
        sb.append("只输出一个 JSON 数组，不要任何解释、不要 markdown 代码围栏。每个元素形如：\n");
        sb.append("{\n");
        sb.append("  \"id\": \"<可读且稳定的唯一标识，如 priceteam-single-item-001>\",\n");
        sb.append("  \"name\": \"<人类可读的名称>\",\n");
        sb.append("  \"input\": \"<发给 Agent 的消息，必须是字符串>\",\n");
        sb.append("  \"expectedOutput\": \"<仅 exact_match 判据需要；用了 exact_match 就必须填，否则该判据永远失败>\",\n");
        sb.append("  \"metadata\": { \"stability\": \"stable\" },\n");
        sb.append("  \"judges\": [\n");
        sb.append("    { \"type\": \"constraint\", \"config\": { \"maxIterations\": 3, \"minResponseLength\": 10 } },\n");
        sb.append("    { \"type\": \"llm_judge\", \"config\": { \"prompt\": \"1) … 2) … 3) …\", \"passThreshold\": 0.7 } }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");

        sb.append("## 判据规则（必须遵守）\n");
        sb.append("1. **优先程序化**：能用 constraint（mustContain / mustNotContain / maxIterations / "
                + "minResponseLength / mustCallTools / mustNotCallTools）或 exact_match 表达的，一律不要用 llm_judge。"
                + "用 exact_match 时必须填 expectedOutput，否则该判据永远失败。\n");
        sb.append("2. **用 llm_judge 时，prompt 必须逐条可核对**：写成编号的评分要点（例："
                + "\"1) 是否指出缺少商品信息 2) 是否追问具体商品 3) 若信息不足却给出具体金额则本条计 0 分\"）。"
                + "禁止\"回答是否准确友好\"这类套话。\n");
        sb.append("3. **每条用 llm_judge 的用例必须同时带一条客观下限判据**"
                + "（如 maxIterations / minResponseLength / mustNotContain 明显错误的词）——"
                + "裁判放水时还有硬判据兜底。\n");
        sb.append("4. **覆盖三类**：正常路径、边界、不该做的事。\n\n");

        sb.append("另外：每条用例都必须有 judges（没有判据的用例会被直接判通过，等于假绿灯）；"
                + "id 用英文小写加连字符，可读且稳定（同一能力点的 id 不要每次变化）。\n");
        sb.append("总共生成 3-12 条，每个能力点 1-3 条。\n");
        return sb.toString();
    }
}
