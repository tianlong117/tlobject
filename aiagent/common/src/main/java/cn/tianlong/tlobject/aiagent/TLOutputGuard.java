package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 输出护栏模块。规则检查 + 可选 LLM-as-Judge 审查。
 *
 * 规则格式（params.rules，分号分隔）：
 *   amountLimit:10000           — 金额超限追加 ⚠️ 警告
 *   forbiddenTerms:词1,词2,词3  — 含禁止词则拦截替换
 *   maxLength:2000              — 超长截断
 *
 * LLM 审查（可选，与规则检查配合使用）：
 *   params.llmJudge=true 时，规则检查通过后再调用 LLM 审查回复质量
 *   （答非所问、胡说八道等规则覆盖不了的场景）。需配 llmJudgeProvider 和 llmJudgeModel。
 *   LLM 审查是同步的，会增加回复延迟。
 *
 * 使用方式：
 *   <afterMsgTable>
 *       <action name="chat">
 *           <msg action="validateOutput" destination="outputGuard"
 *                paramsFromMsg="aiResponse"/>
 *       </action>
 *   </afterMsgTable>
 *
 * 创建日期：2026/7/9
 * 作者:tianlong
 */
public class TLOutputGuard extends TLBaseModule implements TLAiAgentParamString {

    // ---- 规则检查 ----
    private String rulesStr;
    private double amountLimit = -1;
    private String[] forbiddenTerms = new String[0];
    private int maxLength = -1;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("[\\d,]+(?:\\.[\\d]*)?\\s*[元钱块]");

    // ---- LLM 审查 ----
    private boolean llmJudge = false;
    private String llmJudgeProvider;
    private String llmJudgeModel = "gpt-4o";
    private TLLlmProvider llmJudgeProviderInstance;

    public TLOutputGuard() { super(); }
    public TLOutputGuard(String name) { super(name); }
    public TLOutputGuard(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            if (params.get("rules") != null)
                rulesStr = params.get("rules");
            if ("true".equals(params.get("llmJudge")))
                llmJudge = true;
            if (params.get("llmJudgeProvider") != null)
                llmJudgeProvider = params.get("llmJudgeProvider");
            if (params.get("llmJudgeModel") != null)
                llmJudgeModel = params.get("llmJudgeModel");
        }
        parseRules();
    }

    private void parseRules() {
        if (rulesStr == null || rulesStr.isEmpty()) return;
        for (String rule : rulesStr.split(";")) {
            rule = rule.trim();
            if (rule.isEmpty()) continue;
            String[] kv = rule.split(":", 2);
            if (kv.length < 2) continue;
            String key = kv[0].trim();
            String value = kv[1].trim();
            switch (key) {
                case "amountLimit":
                    try { amountLimit = Double.parseDouble(value); } catch (NumberFormatException ignored) {}
                    break;
                case "forbiddenTerms":
                    forbiddenTerms = value.split(",");
                    break;
                case "maxLength":
                    try { maxLength = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                    break;
            }
        }
    }

    @Override
    protected TLBaseModule init() {
        putLog("TLOutputGuard [" + name + "] rules: " + (rulesStr != null ? rulesStr : "(none)")
                + " llmJudge=" + llmJudge, LogLevel.DEBUG);
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if ("validateOutput".equals(msg.getAction())) {
            return validateOutput(fromWho, msg);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private TLMsg validateOutput(Object fromWho, TLMsg msg) {
        String response = msg.getStringParam(AI_P_RESPONSE, "");
        if (response.isEmpty()) return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, response);

        StringBuilder warnings = new StringBuilder();

        // 1. 金额超限检查
        if (amountLimit > 0) {
            var matcher = AMOUNT_PATTERN.matcher(response);
            while (matcher.find()) {
                String amountStr = matcher.group().replaceAll("[^\\d.]", "");
                try {
                    double amount = Double.parseDouble(amountStr);
                    if (amount > amountLimit) {
                        warnings.append("⚠️ 回复含大额金额 ").append(amountStr)
                                .append("（超限 ").append(amountLimit).append("），请人工确认。\n");
                        break;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 2. 禁止词过滤
        for (String term : forbiddenTerms) {
            term = term.trim();
            if (!term.isEmpty() && response.contains(term)) {
                response = response.replace(term, "***");
                warnings.append("⚠️ 回复含禁止内容已模糊处理。\n");
                break;
            }
        }

        // 3. 超长截断
        if (maxLength > 0 && response.length() > maxLength) {
            response = response.substring(0, maxLength) + "\n...(已截断)";
            warnings.append("⚠️ 回复过长已截断至 ").append(maxLength).append(" 字符。\n");
        }

        // 4. LLM-as-Judge 审查（规则通过后才调，避免为明显违规内容浪费 API 调用）
        if (llmJudge && response.length() > 20) {  // 极短回复不审
            String judgeIssue = llmReview(response, msg);
            if (judgeIssue != null)
                warnings.append("⚠️ LLM审查: ").append(judgeIssue).append("\n");
        }

        if (warnings.length() > 0) {
            response = response + "\n\n" + warnings.toString().trim();
        }

        return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, response);
    }

    /** LLM 质量审查：返回 null 表示通过，返回字符串表示问题描述 */
    private String llmReview(String response, TLMsg msg) {
        if (llmJudgeProviderInstance == null && llmJudgeProvider != null) {
            synchronized (this) {
                if (llmJudgeProviderInstance == null) {
                    TLBaseModule m = (TLBaseModule) getMyModule(llmJudgeProvider);
                    if (m instanceof TLLlmProvider)
                        llmJudgeProviderInstance = (TLLlmProvider) m;
                }
            }
        }
        if (llmJudgeProviderInstance == null) return null;
        try {
            List<TLConversationHistory> reviewHistory = new ArrayList<>();
            reviewHistory.add(new TLConversationHistory(TLConversationHistory.Role.system,
                    "你是AI输出质量审查员。检查以下回复是否存在问题：答非所问、胡说八道(幻觉)、有害内容。"
                            + "如果没问题只回复 PASS，有问题回复 ISSUE:具体问题描述（一句话）。"));
            // 带上用户原始消息作为上下文（如果有）
            String userMsg = msg.getStringParam("userMessage", "");
            if (!userMsg.isEmpty())
                reviewHistory.add(new TLConversationHistory(TLConversationHistory.Role.user,
                        "用户提问：" + userMsg + "\n\nAI回复：" + response));
            else
                reviewHistory.add(new TLConversationHistory(TLConversationHistory.Role.user, response));

            TLMsg reviewMsg = createMsg()
                    .setAction(LLM_COMPLETION)
                    .setParam(AI_P_MESSAGEHISTORY, reviewHistory)
                    .setParam(AI_P_MODEL, llmJudgeModel)
                    .setParam(AI_P_MAXTOKENS, 100)
                    .setParam(AI_P_TEMPERATURE, 0.0);
            TLMsg result = putMsg(llmJudgeProviderInstance, reviewMsg);
            if (result == null || !result.parseBoolean(RESULT, false)) return null;
            String text = result.getStringParam(AI_P_RESPONSE, "");
            if (text == null || text.trim().equalsIgnoreCase("PASS")) return null;
            // 如果返回 ISSUE: 前缀则去掉，只留描述
            if (text.startsWith("ISSUE:"))
                return text.substring(6).trim();
            return text.trim();
        } catch (Exception e) {
            putLog("LLM judge review failed: " + e.toString(), LogLevel.WARN);
            return null; // 失败不拦截，避免误杀
        }
    }
}
