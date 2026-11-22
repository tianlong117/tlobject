package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前会话历史只读检索（函数名通常为 session_history）。
 *
 * <p>补上下文窗口的短板：模型只能看到最近 N 条，更早的轮次虽已持久化但进不了 prompt。
 * 本技能把"按关键词找旧轮次 + 读回原文"做成工具，检索/读取逻辑全部委托给
 * SessionManager（DB/文件版共用），自身只做身份注入、参数校验与结果排版。</p>
 *
 * <p>安全边界：只查<b>当前会话</b>——sessionId/userId 只从 systemArgs 取，LLM 在
 * SKILLINPUT 里传 sessionId 无效；只读，不删除/不修改任何会话数据。</p>
 */
public class TLSessionHistorySkill extends TLBaseSkill {

    /** 会话管理模块名；demo(DB) 配 dbSessionManager，文件版配 sessionManager */
    private String sessionManagerModule = "sessionManager";
    private int maxResults = 5;
    private int maxSnippetChars = 400;
    private int maxReadChars = 20000;
    private int maxOutputChars = 8000;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public TLSessionHistorySkill() { super(); }
    public TLSessionHistorySkill(String name) { super(name); }
    public TLSessionHistorySkill(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("sessionManager") != null)
                sessionManagerModule = params.get("sessionManager");
            maxResults = intParam("maxResults", maxResults);
            maxSnippetChars = intParam("maxSnippetChars", maxSnippetChars);
            maxReadChars = intParam("maxReadChars", maxReadChars);
            maxOutputChars = intParam("maxOutputChars", maxOutputChars);
        }
        if (skillDescription == null || skillDescription.isEmpty()) {
            skillDescription = "只读检索当前会话的历史轮次（更早的对话，包括已被上下文窗口裁掉的内容）。"
                    + "当用户提到「之前/上次/刚才/那个会话里你给的…」且当前上下文看不到原文，"
                    + "或要求导出/复用/继续加工旧回答时使用。operation=search 用 1-3 个关键词查找；"
                    + "operation=read 用 roundSeq 或 roundId 读回该轮原文。只查当前会话，不能跨会话；"
                    + "不要用它查工作区文件或外部信息。";
        }
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();

            Map<String, Object> op = new LinkedHashMap<>();
            op.put("type", "string");
            op.put("description", "search=按关键词检索；read=读取命中轮次的原文");
            op.put("enum", new String[]{"search", "read"});
            op.put("required", true);
            parameterSchema.put("operation", op);

            Map<String, Object> query = new LinkedHashMap<>();
            query.put("type", "string");
            query.put("description", "search 必填：1-3 个关键词（如「可能的爱」「流程图」），不要传整句");
            parameterSchema.put("query", query);

            Map<String, Object> roundSeq = new LinkedHashMap<>();
            roundSeq.put("type", "integer");
            roundSeq.put("description", "read 用：search 结果里的 roundSeq");
            parameterSchema.put("roundSeq", roundSeq);

            Map<String, Object> roundId = new LinkedHashMap<>();
            roundId.put("type", "string");
            roundId.put("description", "read 用：轮次 ID（与 roundSeq 二选一，优先 roundId）");
            parameterSchema.put("roundId", roundId);

            Map<String, Object> messageIndex = new LinkedHashMap<>();
            messageIndex.put("type", "integer");
            messageIndex.put("description", "read 可选：指定消息下标；默认取该轮最后一条 assistant");
            parameterSchema.put("messageIndex", messageIndex);

            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("type", "integer");
            limit.put("description", "search 可选：最多返回几条，默认 " + maxResults);
            parameterSchema.put("limit", limit);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        String op = asString(input.get("operation"), "").trim().toLowerCase();
        if (op.isEmpty()) return fail("operation 必填（search/read）");
        String sessionId = sys(msg, AI_P_SESSIONID, "");
        String userId = sys(msg, AI_P_USERID, "");
        if (sessionId.isEmpty()) return fail("当前会话标识缺失，无法检索会话历史");

        Object sm = resolveSessionManager();
        if (!(sm instanceof IObject)) {
            putLog("session manager not configured: " + sessionManagerModule, LogLevel.WARN);
            return fail("会话管理模块未配置或不可用（" + sessionManagerModule + "），请联系管理员");
        }

        TLMsg req = createMsg()
                .setParam("sessionId", sessionId)
                .setParam("userId", userId);
        switch (op) {
            case "search": {
                String query = asString(input.get("query"), "").trim();
                if (query.isEmpty()) return fail("query 必填（1-3 个关键词）");
                int limit = Math.min(maxResults, Math.max(1, asInt(input.get("limit"), maxResults)));
                req.setAction(SESSION_SEARCHHISTORY)
                        .setParam("query", query)
                        .setParam("limit", limit)
                        .setParam("maxSnippetChars", maxSnippetChars)
                        .setParam("role", "assistant")
                        .setParam("excludeRoundId", sys(msg, AI_P_ROUNDID, ""));
                break;
            }
            case "read": {
                String roundId = asString(input.get("roundId"), "").trim();
                int roundSeq = asInt(input.get("roundSeq"), -1);
                if (roundId.isEmpty() && roundSeq < 0)
                    return fail("read 需要 roundSeq 或 roundId");
                req.setAction(SESSION_READHISTORYROUND)
                        .setParam("roundId", roundId)
                        .setParam("roundSeq", roundSeq)
                        .setParam("messageIndex", asInt(input.get("messageIndex"), -1))
                        .setParam("maxChars", maxReadChars);
                break;
            }
            default:
                return fail("未知 operation: " + op);
        }

        TLMsg resp;
        try {
            resp = putMsg((IObject) sm, req);
        } catch (Throwable t) {
            putLog("session history call failed: " + t, LogLevel.ERROR);
            return fail("会话历史检索调用失败（详见错误日志）");
        }
        if (resp == null) return fail("会话管理模块无响应");
        if (!resp.parseBoolean(RESULT, false))
            return fail(resp.getStringParam("error", "会话历史检索失败"));

        String text = "search".equals(op) ? formatSearch(input, resp) : formatRead(resp);
        return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, truncate(text));
    }

    /** 取会话管理模块（测试/子类可覆写注入；默认按工厂模块名解析） */
    protected Object resolveSessionManager() {
        return moduleFactory != null ? moduleFactory.getModule(sessionManagerModule) : null;
    }

    @SuppressWarnings("unchecked")
    private String formatSearch(Map<String, Object> input, TLMsg resp) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.getListParam("results", null);
        int total = resp.getIntParam("totalMatches", 0);
        String query = asString(input.get("query"), "");
        if (results == null || results.isEmpty()) {
            return "当前会话历史中没有找到包含「" + query + "」的 assistant 回答。"
                    + "可以换一个更具体的关键词；如果内容确实在别的会话或文件里，请说明。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前会话历史命中 ").append(total).append(" 条")
                .append(total > results.size() ? "（显示前 " + results.size() + " 条）" : "")
                .append("。以下按“助手长回答优先”排序：");
        int i = 1;
        for (Map<String, Object> r : results) {
            sb.append("\n\n[").append(i++).append("] round ").append(r.get("roundSeq"))
                    .append(" · ").append(r.get("role"));
            String ts = formatTs(r.get("createdAt"));
            if (!ts.isEmpty()) sb.append(" · ").append(ts);
            String um = oneLine(asString(r.get("userMessage"), ""), 160);
            if (!um.isEmpty()) sb.append("\n用户问：").append(um);
            sb.append("\n片段：").append(asString(r.get("snippet"), ""));
            sb.append("\n读取原文：session_history operation=read roundSeq=")
                    .append(r.get("roundSeq"));
        }
        return sb.toString();
    }

    private String formatRead(TLMsg resp) {
        StringBuilder sb = new StringBuilder();
        sb.append("来源：当前会话 round ").append(resp.getParam("roundSeq"))
                .append("（roundId=").append(resp.getStringParam("roundId", "")).append("）");
        String ts = formatTs(resp.getParam("createdAt"));
        if (!ts.isEmpty()) sb.append(" · ").append(ts);
        String um = oneLine(resp.getStringParam("userMessage", ""), 200);
        if (!um.isEmpty()) sb.append("\n用户问：").append(um);
        sb.append("\n角色：").append(resp.getStringParam("role", ""))
                .append("；消息下标：").append(resp.getIntParam("messageIndex", -1))
                .append("/").append(resp.getIntParam("messageCount", 0));
        if (resp.parseBoolean("truncated", false)) sb.append("；内容已截断");
        sb.append("\n--- 原文开始 ---\n")
                .append(resp.getStringParam("content", ""))
                .append("\n--- 原文结束 ---");
        return sb.toString();
    }

    private String sys(TLMsg msg, String key, String def) {
        Object v = msg.getSystemParam(key, def);
        if (v == null || String.valueOf(v).trim().isEmpty()) return def;
        return String.valueOf(v).trim();
    }

    private int intParam(String key, int def) {
        try { return Integer.parseInt(String.valueOf(params.get(key))); }
        catch (Exception e) { return def; }
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (Exception e) { return def; }
    }

    private static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String formatTs(Object v) {
        if (v == null) return "";
        if (v instanceof Number) {
            long ms = ((Number) v).longValue();
            if (ms <= 0) return "";
            if (ms < 100000000000L) ms *= 1000;   // 有些后端存秒
            return TS_FMT.format(Instant.ofEpochMilli(ms));
        }
        return String.valueOf(v);
    }

    private String truncate(String text) {
        if (text == null) return "";
        if (maxOutputChars <= 0 || text.length() <= maxOutputChars) return text;
        return text.substring(0, maxOutputChars) + "\n...[结果过长已截断]";
    }

    private TLMsg fail(String message) {
        return createMsg().setParam(RESULT, false)
                .setParam(AI_P_SKILLOUTPUT, "会话历史检索失败：" + message);
    }
}
