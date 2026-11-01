package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import org.xmlpull.v1.XmlPullParser;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具/技能输出客观校验器。
 *
 * <p>由 {@code TLToolExecutor} 在技能/子 agent 返回结果之后、交给 LLM 之前调用。
 * 规则来自自身配置的 {@code <rules>}，不配规则默认通过（零侵入）。
 *
 * <p>规则形态：
 * <pre>{@code
 * <moduleConfig>
 *   <params>
 *     <failClosedOnError value="false"/> <!-- 可选：校验器自身异常时是否 fail-closed -->
 *   </params>
 *   <rules>
 *     <rule tool="calculate_price" checks="total:number; total>=0; total==quantity*unitPrice"/>
 *     <rule tool="priceTeam" validator="priceValidator" action="checkGroupPrice" retryable="false"/>
 *     <rule tool="calculate_price" validator="self" action="validateOutput" retryable="false"/>
 *   </rules>
 * </moduleConfig>
 * }</pre>
 *
 * <p>校验输入是执行器传来的完整 result TLMsg（含 total/quantity 等结构化字段），不是纯文本。
 * 一期只校验工具结果；agent 终答对拍是二期。
 *
 * 创建日期：2026/10/31
 */
public class TLOutputValidator extends TLBaseModule implements TLAiAgentParamString {

    public static final String SELF = "self";

    /** 单条校验规则 */
    protected static class Rule {
        String tool = "";
        String agent = "";
        String checks = "";
        String validator = "";
        String action = "";
        boolean retryable = false;
    }

    /** 通用检查/自定义检查的中间结果 */
    protected static class CheckResult {
        boolean valid = true;
        String reason = "";
        Boolean retryable;
        String correctedOutput;

        static CheckResult pass() { return new CheckResult(); }

        static CheckResult fail(String reason) {
            CheckResult r = new CheckResult();
            r.valid = false;
            r.reason = reason != null ? reason : "";
            return r;
        }
    }

    /** 校验器自身异常时的策略：默认 fail-open，避免一个坏规则拖死所有工具 */
    protected volatile boolean failClosedOnError = false;

    /** 规则快照（volatile）：重载线程整体替换，校验线程只读。
     *  初始为可写空表（兼容既有自测直接填充）；init/reload 后替换为不可变快照。 */
    protected volatile List<Rule> rules = new ArrayList<>();

    /** configure 期间解析出的候选规则；成功后才替换 rules */
    protected List<Rule> pendingRules;

    /** 本次配置解析错误；null 表示成功 */
    protected String parseError;

    /** 最近一次 setConfig 是否可用 */
    protected boolean lastConfigOk;

    /** 本次 setConfig 解析出的配置参数快照（区别与合并后的 params：删除项不残留） */
    protected HashMap<String, String> configParams;

    /** 配置文件实际提供过的参数键：只有这些键在“配置删除”时才从合并 params 清理，保留外部/工厂参数 */
    private final java.util.Set<String> configOwnedParams = new java.util.HashSet<>();

    /** 外部（start/工厂）提供的参数值；配置删除同名键时回退到它 */
    private HashMap<String, String> externalParams = new HashMap<>();

    /** configParams 是否为本次 configure 刚解析出的（供下一次 setModuleParams 消费） */
    private boolean configParamsPending;

    public TLOutputValidator() { super(); }
    public TLOutputValidator(String name) { super(name); }
    public TLOutputValidator(String name, String configFile) { super(name, configFile); }
    public TLOutputValidator(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected Object setConfig() {
        pendingRules = new ArrayList<>();
        parseError = null;
        lastConfigOk = false;
        String resolvedFile = resolveConfigFile();
        String configDir = moduleFactory != null ? moduleFactory.getConfigDir() : "";
        myConfig config = new myConfig(resolvedFile != null ? resolvedFile : configFile, configDir);
        mconfig = config;
        // 记录外部（start/工厂）提供的参数：配置曾拥有的键保留之前记录的外部值
        HashMap<String, String> ext = new HashMap<>(externalParams);
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!configOwnedParams.contains(e.getKey()))
                    ext.put(e.getKey(), e.getValue());
            }
        }
        externalParams = ext;
        super.setConfig();
        if (resolvedFile == null) {
            parseError = "无配置文件: " + name;
        } else if (mconfig == null) {
            parseError = "配置文件不可用: " + resolvedFile;
        } else if (!mconfig.isParseOk()) {
            if (parseError == null)
                parseError = "配置解析失败: " + mconfig.getParseError();
        }
        lastConfigOk = parseError == null;
        // 基类 initProperty 只合并、不清理已删除键；这里留一份本次配置文件参数快照
        // （拷贝一份，避免与基类 params = mconfig.getParams() 别名共享）
        configParams = lastConfigOk && mconfig.getParams() != null
                ? new HashMap<>(mconfig.getParams()) : null;
        configParamsPending = true;
        return config;
    }

    @Override
    protected synchronized void setModuleParams() {
        if (configParamsPending) {
            configParamsPending = false;
            if (params != null) {
                for (String key : new ArrayList<>(configOwnedParams)) {
                    if (configParams == null || !configParams.containsKey(key)) {
                        configOwnedParams.remove(key);
                        if (externalParams.containsKey(key))
                            params.put(key, externalParams.get(key));
                        else
                            params.remove(key);
                    }
                }
                if (configParams != null) {
                    for (Map.Entry<String, String> e : configParams.entrySet()) {
                        params.put(e.getKey(), e.getValue());
                        configOwnedParams.add(e.getKey());
                    }
                }
            }
        }
        failClosedOnError = params != null
                && "true".equals(params.get("failClosedOnError"));
    }

    @Override
    protected TLBaseModule init() {
        if (lastConfigOk && pendingRules != null) {
            rules = java.util.Collections.unmodifiableList(new ArrayList<>(pendingRules));
        } else {
            rules = java.util.Collections.emptyList();
            if (parseError != null)
                putLog("输出校验规则加载失败，校验将全部放行: " + parseError, LogLevel.ERROR);
        }
        putLog("TLOutputValidator [" + name + "] rules=" + rules.size()
                + " failClosedOnError=" + failClosedOnError, LogLevel.DEBUG);
        return this;
    }

    /**
     * 运行期重载：基类完成预检与 configure（会重入 setConfig），
     * 全部成功后才把 pendingRules 换入 rules；任何失败保留旧快照。
     */
    @Override
    protected TLMsg reloadConfig(Object fromWho, TLMsg msg) {
        synchronized (this) {
            List<Rule> old = rules;
            boolean oldFailClosed = failClosedOnError;
            boolean oldParamPresent = params != null && params.containsKey("failClosedOnError");
            String oldParamValue = oldParamPresent ? params.get("failClosedOnError") : null;
            java.util.Set<String> oldOwned = new java.util.HashSet<>(configOwnedParams);
            HashMap<String, String> oldExternal = new HashMap<>(externalParams);
            parseError = null;
            pendingRules = null;
            TLMsg r = super.reloadConfig(fromWho, msg);
            boolean superOk = r != null && r.parseBoolean(RESULT, false);
            if (superOk && lastConfigOk && pendingRules != null) {
                rules = java.util.Collections.unmodifiableList(new ArrayList<>(pendingRules));
                putLog("输出校验规则已重载: " + rules.size() + " 条 (" + configFile + ")", LogLevel.INFO);
                return createMsg().setParam(RESULT, true)
                        .setParam("rules", rules.size())
                        .setParam("file", configFile);
            }
            rules = old;
            failClosedOnError = oldFailClosed;
            configOwnedParams.clear();
            configOwnedParams.addAll(oldOwned);
            externalParams.clear();
            externalParams.putAll(oldExternal);
            if (params != null) {
                if (oldParamPresent) params.put("failClosedOnError", oldParamValue);
                else params.remove("failClosedOnError");
            }
            configParamsPending = false;
            String err = parseError != null ? parseError
                    : (r != null ? r.getStringParam("error", "") : "");
            if (err == null || err.isEmpty()) err = "重载失败";
            putLog("输出校验规则重载失败，保留旧规则: " + err, LogLevel.ERROR);
            return createMsg().setParam(RESULT, false).setParam("error", err);
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if (OUTPUT_VALIDATE_ACTION.equals(msg.getAction()))
            return validateRequest(msg);
        return null;
    }

    /**
     * 执行器调用入口（包级可见，便于自测）：
     * req 参数：functionName/moduleName/toolCallId/args/result/agentName/targetModule。
     * 返回：valid（boolean）、reason、retryable、correctedOutput。
     */
    TLMsg validateRequest(TLMsg req) {
        try {
            if (req == null) return pass();
            Object resultObj = req.getParam("result");
            if (!(resultObj instanceof TLMsg)) return pass();   // 无可校验结果，视为不适用
            TLMsg result = (TLMsg) resultObj;
            String functionName = req.getStringParam("functionName", "");
            String moduleName = req.getStringParam("moduleName", "");
            String agentName = req.getStringParam("agentName", "");
            Rule rule = matchRule(functionName, moduleName, agentName);
            if (rule == null) return pass();

            CheckResult checks = evaluateChecks(rule, result);
            if (!checks.valid) return fail(checks.reason, rule.retryable);

            if (rule.validator != null && !rule.validator.isEmpty()) {
                CheckResult custom = runCustom(rule, req);
                if (custom == null) return pass();   // fail-open：目标缺失/异常
                if (!custom.valid) {
                    if (custom.correctedOutput != null && !custom.correctedOutput.isEmpty())
                        return corrected(custom.correctedOutput);
                    boolean retryable = custom.retryable != null ? custom.retryable : rule.retryable;
                    return fail(custom.reason, retryable);
                }
                if (custom.correctedOutput != null && !custom.correctedOutput.isEmpty())
                    return corrected(custom.correctedOutput);
            }
            return pass();
        } catch (Throwable t) {
            putLog("output validator error: " + t, LogLevel.ERROR);
            if (failClosedOnError)
                return fail("校验器内部错误: " + t.getClass().getSimpleName(), false);
            return pass();
        }
    }

    // ======================== 规则匹配 ========================

    protected Rule matchRule(String functionName, String moduleName, String agentName) {
        List<Rule> snapshot = rules;
        Rule best = null;
        int bestScore = -1;
        for (Rule r : snapshot) {
            if (r == null || r.tool == null || r.tool.isEmpty()) continue;
            if (r.agent != null && !r.agent.isEmpty()
                    && !r.agent.equals(agentName)) continue;
            int toolScore;
            if (!functionName.isEmpty() && r.tool.equals(functionName)) toolScore = 4;
            else if (!moduleName.isEmpty() && r.tool.equals(moduleName)) toolScore = 2;
            else if ("*".equals(r.tool)) toolScore = 0;
            else continue;
            int score = toolScore * 2 + (r.agent != null && !r.agent.isEmpty() ? 1 : 0);
            if (score > bestScore) {
                best = r;
                bestScore = score;
            }
        }
        return best;
    }

    // ======================== 通用 checks ========================

    protected CheckResult evaluateChecks(Rule rule, TLMsg result) {
        if (rule.checks == null || rule.checks.trim().isEmpty()) return CheckResult.pass();
        for (String raw : rule.checks.split(";")) {
            String check = raw.trim();
            if (check.isEmpty()) continue;
            CheckResult r = evalCheck(check, result);
            if (!r.valid) return r;
        }
        return CheckResult.pass();
    }

    private CheckResult evalCheck(String check, TLMsg result) {
        java.util.regex.Matcher typeM = TYPE_CHECK.matcher(check);
        if (typeM.matches()) {
            String field = typeM.group(1);
            String type = typeM.group(2);
            Object value;
            try {
                value = fieldValue(result, field);
            } catch (IllegalArgumentException e) {
                return CheckResult.fail(e.getMessage());
            }
            return checkType(field, type, value);
        }

        java.util.regex.Matcher cmpM = COMPARE_CHECK.matcher(check);
        if (!cmpM.matches())
            return CheckResult.fail("无法解析检查规则: " + check);
        String field = cmpM.group(1);
        String op = cmpM.group(2);
        String rhs = cmpM.group(3).trim();
        Object value;
        try {
            value = fieldValue(result, field);
            if (isQuoted(rhs)) {
                if (!"==".equals(op) && !"!=".equals(op))
                    return CheckResult.fail("字符串比较只支持 ==/!=: " + check);
                String s = rhs.substring(1, rhs.length() - 1);
                boolean eq = String.valueOf(value).equals(s);
                return ("==".equals(op) ? eq : !eq) ? CheckResult.pass()
                        : CheckResult.fail(field + " != " + s);
            }
            if ("true".equalsIgnoreCase(rhs) || "false".equalsIgnoreCase(rhs)) {
                if (!"==".equals(op) && !"!=".equals(op))
                    return CheckResult.fail("布尔比较只支持 ==/!=: " + check);
                boolean expected = Boolean.parseBoolean(rhs);
                boolean actual = toBoolean(value, field);
                boolean eq = actual == expected;
                return ("==".equals(op) ? eq : !eq) ? CheckResult.pass()
                        : CheckResult.fail(field + " != " + expected);
            }
            BigDecimal left = toNumber(value, field);
            BigDecimal right = evalArithmetic(rhs, result);
            int cmp = left.compareTo(right);
            boolean ok;
            switch (op) {
                case ">":  ok = cmp > 0; break;
                case ">=": ok = cmp >= 0; break;
                case "<":  ok = cmp < 0; break;
                case "<=": ok = cmp <= 0; break;
                case "==": ok = cmp == 0; break;
                case "!=": ok = cmp != 0; break;
                default:   ok = false;
            }
            return ok ? CheckResult.pass()
                    : CheckResult.fail(field + " " + op + " " + rhs + " 不成立（实际 " + left.toPlainString() + "）");
        } catch (IllegalArgumentException e) {
            return CheckResult.fail(e.getMessage());
        }
    }

    private static final java.util.regex.Pattern TYPE_CHECK =
            java.util.regex.Pattern.compile(
                    "^([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(number|integer|boolean|string|nonEmpty)$");
    private static final java.util.regex.Pattern COMPARE_CHECK =
            java.util.regex.Pattern.compile(
                    "^([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");

    private CheckResult checkType(String field, String type, Object value) {
        switch (type) {
            case "number":
                try { toNumber(value, field); return CheckResult.pass(); }
                catch (IllegalArgumentException e) { return CheckResult.fail(e.getMessage()); }
            case "integer":
                try {
                    BigDecimal n = toNumber(value, field);
                    return n.stripTrailingZeros().scale() <= 0 ? CheckResult.pass()
                            : CheckResult.fail(field + " 不是整数: " + value);
                } catch (IllegalArgumentException e) {
                    return CheckResult.fail(e.getMessage());
                }
            case "boolean":
                try { toBoolean(value, field); return CheckResult.pass(); }
                catch (IllegalArgumentException e) { return CheckResult.fail(e.getMessage()); }
            case "string":
                return value instanceof String ? CheckResult.pass()
                        : CheckResult.fail(field + " 不是字符串: " + value);
            case "nonEmpty":
                if (value == null) return CheckResult.fail(field + " 为空");
                if (value instanceof String)
                    return ((String) value).trim().isEmpty()
                            ? CheckResult.fail(field + " 为空") : CheckResult.pass();
                if (value instanceof Collection)
                    return ((Collection<?>) value).isEmpty()
                            ? CheckResult.fail(field + " 为空") : CheckResult.pass();
                if (value instanceof Map)
                    return ((Map<?, ?>) value).isEmpty()
                            ? CheckResult.fail(field + " 为空") : CheckResult.pass();
                if (value.getClass().isArray())
                    return java.lang.reflect.Array.getLength(value) == 0
                            ? CheckResult.fail(field + " 为空") : CheckResult.pass();
                return CheckResult.pass();
            default:
                return CheckResult.fail("未知类型: " + type);
        }
    }

    /** 字段取值：output 是执行器实际输出别名，其余取 result 顶层参数 */
    private Object fieldValue(TLMsg result, String field) {
        if ("output".equals(field)) return effectiveOutput(result);
        if (!result.containsParam(field))
            throw new IllegalArgumentException("缺少字段 " + field);
        Object v = result.getParam(field);
        if (v == null) throw new IllegalArgumentException("字段 " + field + " 为空");
        return v;
    }

    private String effectiveOutput(TLMsg result) {
        String out = result.getStringParam(AI_P_SKILLOUTPUT, "");
        if (out == null || out.isEmpty()) out = result.getStringParam(AI_P_RESPONSE, "");
        if (out == null || out.isEmpty()) out = TLMsgUtils.msgToSimpleStr(result);
        return out != null ? out : "";
    }

    private static boolean isQuoted(String s) {
        return s.length() >= 2
                && ((s.startsWith("'") && s.endsWith("'"))
                    || (s.startsWith("\"") && s.endsWith("\"")));
    }

    private BigDecimal toNumber(Object value, String field) {
        if (value instanceof Number) return new BigDecimal(value.toString());
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.matches("[+-]?\\d+(\\.\\d+)?")) return new BigDecimal(s);
        }
        throw new IllegalArgumentException("字段 " + field + " 不是数字: " + value);
    }

    private boolean toBoolean(Object value, String field) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String s = ((String) value).trim();
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        throw new IllegalArgumentException("字段 " + field + " 不是布尔值: " + value);
    }

    // ======================== 小算术求值器 ========================

    /** 字段/数字/+-* / /括号，供 checks 的跨字段算术使用 */
    private BigDecimal evalArithmetic(String expr, TLMsg result) {
        ArithParser p = new ArithParser(expr, result);
        BigDecimal v = p.parseExpression();
        p.skipWs();
        if (p.pos < p.src.length())
            throw new IllegalArgumentException("算术表达式有多余内容: " + expr);
        return v;
    }

    private class ArithParser {
        final String src;
        final TLMsg result;
        int pos;

        ArithParser(String src, TLMsg result) {
            this.src = src;
            this.result = result;
        }

        BigDecimal parseExpression() {
            BigDecimal v = parseTerm();
            while (true) {
                skipWs();
                if (peek('+')) { pos++; v = v.add(parseTerm()); }
                else if (peek('-')) { pos++; v = v.subtract(parseTerm()); }
                else return v;
            }
        }

        BigDecimal parseTerm() {
            BigDecimal v = parseFactor();
            while (true) {
                skipWs();
                if (peek('*')) { pos++; v = v.multiply(parseFactor()); }
                else if (peek('/')) {
                    pos++;
                    BigDecimal d = parseFactor();
                    if (d.compareTo(BigDecimal.ZERO) == 0)
                        throw new IllegalArgumentException("算术表达式除零: " + src);
                    v = v.divide(d, MathContext.DECIMAL64);
                } else return v;
            }
        }

        BigDecimal parseFactor() {
            skipWs();
            if (peek('-')) { pos++; return parseFactor().negate(); }
            if (peek('+')) { pos++; return parseFactor(); }
            if (peek('(')) {
                pos++;
                BigDecimal v = parseExpression();
                skipWs();
                if (!peek(')')) throw new IllegalArgumentException("算术表达式缺少 ')' : " + src);
                pos++;
                return v;
            }
            if (pos >= src.length()) throw new IllegalArgumentException("算术表达式不完整: " + src);
            char c = src.charAt(pos);
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < src.length()
                        && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
                String num = src.substring(start, pos);
                try { return new BigDecimal(num); }
                catch (NumberFormatException e) {
                    throw new IllegalArgumentException("算术表达式数字非法: " + num);
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                while (pos < src.length()
                        && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
                String field = src.substring(start, pos);
                return toNumber(fieldValue(result, field), field);
            }
            throw new IllegalArgumentException("算术表达式非法字符: " + c);
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        boolean peek(char c) { return pos < src.length() && src.charAt(pos) == c; }
    }

    // ======================== 自定义校验（self / 任意模块 action） ========================

    private CheckResult runCustom(Rule rule, TLMsg req) {
        IObject target;
        if (SELF.equals(rule.validator)) {
            Object t = req.getParam("targetModule");
            target = t instanceof IObject ? (IObject) t : null;
        } else {
            Object t = getModule(rule.validator);
            target = t instanceof IObject ? (IObject) t : null;
        }
        if (target == null)
            return policyError("校验目标不可用: " + rule.validator);

        String action = rule.action != null && !rule.action.isEmpty()
                ? rule.action : OUTPUT_VALIDATE_ACTION;
        TLMsg m = createMsg().setAction(action)
                .setParam("functionName", req.getParam("functionName"))
                .setParam("moduleName", req.getParam("moduleName"))
                .setParam("toolCallId", req.getParam("toolCallId"))
                .setParam("agentName", req.getParam("agentName"))
                .setParam("args", req.getParam("args"))
                .setParam("result", req.getParam("result"))
                .setParam("targetModule", req.getParam("targetModule"));
        try {
            TLMsg resp = putMsg(target, m);
            if (resp == null) return policyError("校验模块无响应: " + rule.validator);
            boolean hasValid = resp.containsParam("valid");
            boolean hasResult = resp.containsParam(RESULT);
            if (!hasValid && !hasResult)
                return policyError("校验模块未返回 valid/RESULT: " + rule.validator);
            CheckResult cr = new CheckResult();
            cr.valid = hasValid ? resp.parseBoolean("valid", true)
                    : resp.parseBoolean(RESULT, true);
            cr.reason = resp.getStringParam("reason", "");
            if (cr.reason == null) cr.reason = "";
            if (resp.containsParam("retryable"))
                cr.retryable = resp.parseBoolean("retryable", false);
            cr.correctedOutput = resp.getStringParam("correctedOutput", null);
            return cr;
        } catch (Throwable t) {
            return policyError("校验模块异常: " + t);
        }
    }

    /** 目标缺失/异常：默认 fail-open；failClosedOnError=true 时返回失败 */
    private CheckResult policyError(String reason) {
        putLog("output validator: " + reason, LogLevel.ERROR);
        if (failClosedOnError) return CheckResult.fail(reason);
        return null;
    }

    // ======================== 返回构造 ========================

    private TLMsg pass() {
        return createMsg().setParam("valid", true);
    }

    private TLMsg fail(String reason, boolean retryable) {
        return createMsg().setParam("valid", false)
                .setParam("reason", reason != null ? reason : "")
                .setParam("retryable", retryable);
    }

    private TLMsg corrected(String output) {
        return createMsg().setParam("valid", true)
                .setParam("correctedOutput", output);
    }

    // ======================== 配置解析 ========================

    protected class myConfig extends TLModuleConfig {
        public myConfig() {}
        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        @Override
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if ("rules".equals(xpp.getName())) parseRules(xpp);
            } catch (Throwable t) {
                TLOutputValidator.this.parseError = "rules 解析失败: " + t;
                parseOk = false;
                putLog("TLOutputValidator config parse error: " + t, LogLevel.ERROR);
            }
        }

        private void parseRules(XmlPullParser xpp) throws Throwable {
            int depth = xpp.getDepth();
            while (true) {
                xpp.next();
                if (xpp.getEventType() == XmlPullParser.END_DOCUMENT) break;
                if (xpp.getEventType() == XmlPullParser.END_TAG
                        && xpp.getDepth() == depth
                        && "rules".equals(xpp.getName())) break;
                if (xpp.getEventType() == XmlPullParser.START_TAG
                        && "rule".equals(xpp.getName())) {
                    Rule r = new Rule();
                    r.tool = attr(xpp, "tool");
                    r.agent = attr(xpp, "agent");
                    r.checks = attr(xpp, "checks");
                    r.validator = attr(xpp, "validator");
                    r.action = attr(xpp, "action");
                    r.retryable = "true".equals(attr(xpp, "retryable"));
                    if (pendingRules == null) pendingRules = new ArrayList<>();
                    pendingRules.add(r);
                }
            }
        }

        private String attr(XmlPullParser xpp, String name) {
            String v = xpp.getAttributeValue(null, name);
            return v != null ? v : "";
        }
    }
}
