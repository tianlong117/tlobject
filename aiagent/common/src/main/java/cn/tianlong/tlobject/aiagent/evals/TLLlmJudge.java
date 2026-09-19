package cn.tianlong.tlobject.aiagent.evals;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.aiagent.TLConversationHistory;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.Map;

/**
 * LLM-as-Judge 评判器。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class TLLlmJudge implements TLEvalJudge, TLAiAgentParamString {

    @Override
    public String getType() { return "llm_judge"; }

    @Override
    public TLEvalVerdict judge(TLEvalCase evalCase, TLEvalRunResult runResult, JudgeContext context) {
        JudgeConfig config = findConfig(evalCase);
        String judgePrompt = config.getString("prompt");
        if (judgePrompt == null || judgePrompt.isEmpty()) {
            return TLEvalVerdict.fail("llm_judge", 0.0, "llm_judge 缺少 prompt 配置");
        }

        double passThreshold = config.getDouble("passThreshold", 0.7);
        String evalPrompt = buildEvalPrompt(evalCase.getInput(), runResult.getResponse(), judgePrompt);

        try {
            // Provider 只从 AI_P_MESSAGEHISTORY 取消息（见 TLLlmProvider.doCompletionStream /
            // TLOpenAiProvider.completion，都没有 userMessage 回退）——原来只塞 AI_P_USERMESSAGE
            // 会让请求体带上空的 messages 数组，LLM Judge 实际是坏的
            java.util.List<TLConversationHistory> judgeHistory = new java.util.ArrayList<>();
            judgeHistory.add(new TLConversationHistory(TLConversationHistory.Role.user, evalPrompt));

            // max_tokens 512 → 1024：裁判只要吐一个短 JSON，但推理模型的 reasoning token 与正文
            // 共用这个额度，512 被推理吃光后 content 是空串（拿到的"判决"其实是内心独白，一律判成
            // "无法解析为 JSON"）。同时默认显式关推理——判决 JSON 不需要推理过程
            TLMsg llmMsg = context.evalsModule.createMsg()
                    .setAction(LLM_COMPLETION)
                    .setParam(AI_P_MESSAGEHISTORY, judgeHistory)
                    .setParam(AI_P_TEMPERATURE, 0.1)
                    .setParam(AI_P_MAXTOKENS, 1024);
            if (context.judgeReasoningMode != null && !context.judgeReasoningMode.isEmpty()) {
                llmMsg.setParam(AI_P_REASONING_MODE, context.judgeReasoningMode);
            }

            String model = config.getString("model");
            if (model != null && !model.isEmpty()) {
                llmMsg.setParam(AI_P_MODEL, model);
            }

            // IGNOREMODULEISNULL：judgeProvider 配错（名字写错/该模块不存在）时只让这条裁决失败，
            // 而不是走 putMsg 默认的那条 moduleFactory.shutdown(-1) —— 配错一个 provider 不该关掉整个应用
            llmMsg.setSystemParam(IGNOREMODULEISNULL, true);
            TLMsg result = context.evalsModule.putMsg(context.judgeProviderName, llmMsg);

            if (result == null) {
                return TLEvalVerdict.fail("llm_judge", 0.0, "LLM Judge 调用失败: null result");
            }
            if (TLEvalsModule.isModuleMissing(result)) {
                return TLEvalVerdict.fail("llm_judge", 0.0,
                        "LLM Judge 调用失败: Provider 模块不存在: " + context.judgeProviderName);
            }
            if (!result.parseBoolean(RESULT, false)) {
                return TLEvalVerdict.fail("llm_judge", 0.0,
                        "LLM Judge 调用失败: " + result.getStringParam("error", ""));
            }

            String judgeResponse = result.getStringParam(AI_P_RESPONSE, "");
            // 正文是 reasoning_content 提升来的（content 为空）= 裁判压根没出判决，别把它当
            // "模型没按格式输出"报——真因是 token 被推理吃光，调 judgeReasoningMode 就能解
            if (result.parseBoolean(AI_P_CONTENT_FROM_REASONING, false) || judgeResponse.trim().isEmpty()) {
                return TLEvalVerdict.fail("llm_judge", 0.0,
                        "裁判没有产出判决（模型可能把 token 全花在推理上了：reasoningTokens="
                        + result.getIntParam(AI_P_REASONING_TOKENS, 0)
                        + "，finish_reason=" + result.getStringParam(AI_P_FINISH_REASON, "?")
                        + "）——把 judgeReasoningMode 设成 disabled 关掉裁判的推理");
            }
            JudgeOutput output = parseJudgeOutput(judgeResponse);

            boolean passed = output.pass && output.score >= passThreshold;
            String reason = output.reason != null ? output.reason
                    : String.format("score=%.2f, threshold=%.2f", output.score, passThreshold);

            return new TLEvalVerdict("llm_judge", passed, output.score, reason);

        } catch (Exception e) {
            return TLEvalVerdict.fail("llm_judge", 0.0, "LLM Judge 异常: " + e.toString());
        }
    }

    private String buildEvalPrompt(String userInput, String agentOutput, String judgeCriteria) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个AI输出质量评估器。请根据以下标准评估AI助手的回复质量。\n\n");
        sb.append("【用户输入】\n").append(userInput != null ? userInput : "").append("\n\n");
        sb.append("【AI助手回复】\n").append(agentOutput != null ? agentOutput : "").append("\n\n");
        sb.append("【评判标准】\n").append(judgeCriteria).append("\n\n");
        sb.append("请严格以JSON格式输出评判结果，不要包含其他内容：\n");
        sb.append("{\"pass\": true或false, \"score\": 0.0到1.0的分数, \"reason\": \"评判理由\"}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private JudgeOutput parseJudgeOutput(String response) {
        JudgeOutput output = new JudgeOutput();
        try {
            String json = response;
            int braceStart = response.indexOf('{');
            int braceEnd = response.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = response.substring(braceStart, braceEnd + 1);
            }

            com.google.gson.Gson gson = new com.google.gson.Gson();
            Map<String, Object> map = gson.fromJson(json, Map.class);

            if (map.containsKey("pass")) {
                Object p = map.get("pass");
                output.pass = p instanceof Boolean ? (Boolean) p : Boolean.parseBoolean(p.toString());
            }
            if (map.containsKey("score")) {
                Object s = map.get("score");
                output.score = s instanceof Number ? ((Number) s).doubleValue() : Double.parseDouble(s.toString());
            }
            if (map.containsKey("reason")) {
                Object r = map.get("reason");
                output.reason = r != null ? r.toString() : "";
            }
        } catch (Exception e) {
            // 解析失败不要给一个能跨过阈值的分数（原来是 0.8，默认 passThreshold=0.7 → 直接判通过）。
            // 判据本身不可信时按失败处理，避免"评审器坏掉"被当成"用例通过"。
            output.pass = false;
            output.score = 0.0;
            output.reason = "裁判输出无法解析为 JSON，判为不通过（可能是模型未按格式输出）。原始输出前 200 字: "
                    + (response != null ? response.substring(0, Math.min(200, response.length())) : "null");
        }
        return output;
    }

    private JudgeConfig findConfig(TLEvalCase evalCase) {
        if (evalCase.judges != null) {
            for (JudgeConfig jc : evalCase.judges) {
                if ("llm_judge".equals(jc.type)) return jc;
            }
        }
        return new JudgeConfig("llm_judge", null);
    }

    private static class JudgeOutput {
        boolean pass;
        double score;
        String reason;
    }
}
