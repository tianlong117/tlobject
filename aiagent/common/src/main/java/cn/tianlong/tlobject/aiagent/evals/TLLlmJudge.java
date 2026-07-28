package cn.tianlong.tlobject.aiagent.evals;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
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
            TLMsg llmMsg = context.evalsModule.createMsg()
                    .setAction(LLM_COMPLETION)
                    .setParam(AI_P_USERMESSAGE, evalPrompt)
                    .setParam(AI_P_TEMPERATURE, 0.1)
                    .setParam(AI_P_MAXTOKENS, 512)
                    .setParam("skipHistory", true);

            String model = config.getString("model");
            if (model != null && !model.isEmpty()) {
                llmMsg.setParam(AI_P_MODEL, model);
            }

            TLMsg result = context.evalsModule.putMsg(context.judgeProviderName, llmMsg);

            if (result == null || !result.parseBoolean(RESULT, false)) {
                return TLEvalVerdict.fail("llm_judge", 0.0,
                        "LLM Judge 调用失败: " + (result != null ? result.getStringParam("error", "") : "null result"));
            }

            String judgeResponse = result.getStringParam(AI_P_RESPONSE, "");
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
            output.pass = response.toLowerCase().contains("\"pass\": true")
                    || response.toLowerCase().contains("\"pass\":true");
            output.score = output.pass ? 0.8 : 0.3;
            output.reason = "JSON解析失败，从文本推断: " + response.substring(0, Math.min(200, response.length()));
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
