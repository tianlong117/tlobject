package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;

/**
 * 输出校验器自测（runnable main，无 JUnit——仓库惯例）。
 * 覆盖：规则匹配、类型/范围/跨字段算术、output 别名、self 转发、
 * retryable/correctedOutput、缺字段/无规则默认通过。
 * 失败退出码 1。
 */
public class TLOutputValidatorSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        TLOutputValidator v = new TLOutputValidator("outputValidatorSelfTest");

        TLOutputValidator.Rule priceRule = rule("calculate_price",
                "total:number; total>=0; total==quantity*unitPrice", "", "", false);
        v.rules.add(priceRule);

        TLMsg good = new TLMsg().setParam(AI_P_SKILLOUTPUT, "汉堡×2=4元")
                .setParam("total", 4.0).setParam("quantity", 2).setParam("unitPrice", 2.0);
        check("合法结果通过", valid(v, "calculate_price", "priceCalc", good));

        TLMsg badType = new TLMsg().setParam(AI_P_SKILLOUTPUT, "x")
                .setParam("total", "abc").setParam("quantity", 2).setParam("unitPrice", 2.0);
        TLMsg badTypeResp = validate(v, "calculate_price", "priceCalc", badType);
        check("非数字 total 失败", !badTypeResp.parseBoolean("valid", true)
                && badTypeResp.getStringParam("reason", "").contains("total"));
        check("checks 失败默认不可重试", !badTypeResp.parseBoolean("retryable", false));

        TLMsg mismatch = new TLMsg().setParam(AI_P_SKILLOUTPUT, "x")
                .setParam("total", 5.0).setParam("quantity", 2).setParam("unitPrice", 2.0);
        TLMsg mismatchResp = validate(v, "calculate_price", "priceCalc", mismatch);
        check("跨字段算术不一致失败", !mismatchResp.parseBoolean("valid", true)
                && mismatchResp.getStringParam("reason", "").contains("total"));

        TLMsg withParen = new TLMsg().setParam("total", 6.0)
                .setParam("quantity", 2).setParam("unitPrice", 2.0);
        TLOutputValidator.Rule parenRule = rule("paren_tool",
                "total==(quantity+1)*unitPrice", "", "", false);
        v.rules.add(parenRule);
        check("算术括号支持", valid(v, "paren_tool", "x", withParen));
        check("无匹配规则默认通过", valid(v, "other_tool", "other", good));

        TLOutputValidator.Rule intRule = rule("integer_tool", "quantity:integer", "", "", false);
        v.rules.add(intRule);
        TLMsg intOk = new TLMsg().setParam("quantity", 2);
        TLMsg intBad = new TLMsg().setParam("quantity", 2.5);
        check("integer 类型通过", valid(v, "integer_tool", "x", intOk));
        check("integer 类型拒绝小数", !valid(v, "integer_tool", "x", intBad));

        TLOutputValidator.Rule requiredRule = rule("required_tool", "total:number", "", "", false);
        v.rules.add(requiredRule);
        TLMsg missing = new TLMsg().setParam(AI_P_SKILLOUTPUT, "x");
        TLMsg missingResp = validate(v, "required_tool", "x", missing);
        check("缺字段失败", !missingResp.parseBoolean("valid", true)
                && missingResp.getStringParam("reason", "").contains("缺少字段"));

        TLOutputValidator.Rule outRule = rule("text_tool", "output:nonEmpty", "", "", false);
        v.rules.add(outRule);
        TLMsg textViaResponse = new TLMsg().setParam(AI_P_SKILLOUTPUT, "")
                .setParam(AI_P_RESPONSE, "hello");
        check("output 别名取 AI_P_RESPONSE", valid(v, "text_tool", "x", textViaResponse));
        TLMsg textViaSkill = new TLMsg().setParam(AI_P_SKILLOUTPUT, "汉堡×1=2元");
        check("output 别名取 AI_P_SKILLOUTPUT", valid(v, "text_tool", "x", textViaSkill));

        // self 校验：目标模块返回 invalid/retryable/correctedOutput
        TLOutputValidator.Rule selfRule = rule("self_tool", "", TLOutputValidator.SELF,
                OUTPUT_VALIDATE_ACTION, false);
        v.rules.add(selfRule);
        TLMsg selfReq = request("self_tool", "x", good,
                new FakeTarget(new TLMsg().setParam("valid", false)
                        .setParam("reason", "自定义不通过")
                        .setParam("retryable", true)));
        TLMsg selfResp = v.validateRequest(selfReq);
        check("self 自定义校验失败", !selfResp.parseBoolean("valid", true)
                && selfResp.getStringParam("reason", "").contains("自定义不通过"));
        check("self retryable 透传", selfResp.parseBoolean("retryable", false));

        TLMsg correctedResp = new TLMsg().setParam("valid", false)
                .setParam("reason", "已修正")
                .setParam("correctedOutput", "修正后的输出");
        TLMsg corrected = v.validateRequest(request("self_tool", "x", good,
                new FakeTarget(correctedResp)));
        check("correctedOutput 按通过处理", corrected.parseBoolean("valid", true)
                && "修正后的输出".equals(corrected.getStringParam("correctedOutput", "")));

        TLMsg selfOk = v.validateRequest(request("self_tool", "x", good,
                new FakeTarget(new TLMsg().setParam("valid", true))));
        check("self 自定义校验通过", selfOk.parseBoolean("valid", true));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static TLOutputValidator.Rule rule(String tool, String checks,
                                               String validator, String action,
                                               boolean retryable) {
        TLOutputValidator.Rule r = new TLOutputValidator.Rule();
        r.tool = tool;
        r.checks = checks;
        r.validator = validator;
        r.action = action;
        r.retryable = retryable;
        return r;
    }

    private static TLMsg request(String functionName, String moduleName,
                                 TLMsg result, IObject target) {
        TLMsg req = new TLMsg().setAction(OUTPUT_VALIDATE_ACTION)
                .setParam("functionName", functionName)
                .setParam("moduleName", moduleName)
                .setParam("toolCallId", "call_1")
                .setParam("agentName", "testAgent")
                .setParam("args", new java.util.HashMap<String, Object>())
                .setParam("result", result);
        if (target != null) req.setParam("targetModule", target);
        return req;
    }

    private static TLMsg validate(TLOutputValidator v, String functionName,
                                  String moduleName, TLMsg result) {
        return v.validateRequest(request(functionName, moduleName, result, null));
    }

    private static boolean valid(TLOutputValidator v, String functionName,
                                 String moduleName, TLMsg result) {
        return validate(v, functionName, moduleName, result).parseBoolean("valid", true);
    }

    static class FakeTarget implements IObject {
        private final TLMsg response;
        FakeTarget(TLMsg response) { this.response = response; }
        public String getName() { return "fakeTarget"; }
        public TLMsg putMsg(IObject toWho, TLMsg msg) { return response; }
        public TLMsg getMsg(Object fromWho, TLMsg msg) { return response; }
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
