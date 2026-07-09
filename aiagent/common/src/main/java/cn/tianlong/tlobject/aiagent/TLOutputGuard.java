package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.regex.Pattern;

/**
 * 输出护栏模块。挂在 afterMsgTable 上，对 LLM 回复做规则检查。
 *
 * 规则格式（params.rules，分号分隔）：
 *   amountLimit:10000           — 金额超限追加 ⚠️ 警告
 *   forbiddenTerms:词1,词2,词3  — 含禁止词则拦截替换
 *   maxLength:2000              — 超长截断
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

    private String rulesStr;
    private double amountLimit = -1;
    private String[] forbiddenTerms = new String[0];
    private int maxLength = -1;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("[\\d,]+(?:\\.[\\d]*)?\\s*[元钱块]");

    public TLOutputGuard() { super(); }
    public TLOutputGuard(String name) { super(name); }
    public TLOutputGuard(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        if (params != null) {
            rulesStr = params.get("rules");
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
        putLog("TLOutputGuard [" + name + "] rules: " + (rulesStr != null ? rulesStr : "(none)"), LogLevel.DEBUG);
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if ("validateOutput".equals(msg.getAction())) {
            return validateOutput(fromWho, msg);
        }
        return null;
    }

    private TLMsg validateOutput(Object fromWho, TLMsg msg) {
        String response = msg.getStringParam(AI_P_RESPONSE, "");
        if (response.isEmpty()) return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, response);

        StringBuilder warnings = new StringBuilder();

        // 1. 金额超限
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

        // 2. 禁止词
        for (String term : forbiddenTerms) {
            term = term.trim();
            if (!term.isEmpty() && response.contains(term)) {
                // 不直接暴露原词，模糊替换
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

        if (warnings.length() > 0) {
            response = response + "\n\n" + warnings.toString().trim();
        }

        return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, response);
    }
}
