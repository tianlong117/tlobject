package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

/**
 * 错误监管 Agent——标准 Agent（extends TLAiAgent），通过 afterMsgTable 钩入目标 agent
 * 的 chat 返回结果，审查输出质量。发现未解决的错误时自动触发修正循环。
 *
 * <h3>配置方式</h3>
 * 在目标 agent 的 XML 中：
 * <pre>{@code
 * <afterMsgTable>
 *     <msg action="chat"
 *          destination="errorSupervisor"
 *          actionToDo="reviewChat">
 *         <params>
 *             <maxReviewRounds>2</maxReviewRounds>
 *         </params>
 *     </msg>
 * </afterMsgTable>
 *
 * <modules>
 *     <module name="errorSupervisor"
 *             classfile="cn.tianlong.tlobject.aiagent.TLErrorSupervisorAgent"
 *             singleton="true"/>
 * </modules>
 *
 * <modulesParams>
 *     <module name="errorSupervisor"
 *             provider="openAiProvider"
 *             model="deepseek-chat"
 *             defaultSystemMessage="你是输出质量监管员。审查标准：..."
 *             correctionPromptTemplate="你之前的回答存在问题，请修正。..."/>
 * </modulesParams>
 * }</pre>
 *
 * 不配 afterMsgTable = 不监管，完全零开销。
 *
 * <h3>与 Group Supervisor 的范式对照</h3>
 * Group 的 supervisor 是 agents 名单中 role="supervisor" 的成员；
 * Error supervisor 也是标准 agent，触发方式为 afterMsgTable hook。
 * 两者都是标准 Agent，有自己的 LLM Provider、Skill、子 Agent。
 *
 * @see TLAgentGroup  Group supervisor 参考实现
 */
public class TLErrorSupervisorAgent extends TLAiAgent {

    /** 修正提示模板（从自身 params 读取，支持 {{userMessage}} {{response}} {{suggestion}}） */
    private String correctionPromptTemplate;

    /** JSON 解析（宽容解析审查结果，与 Group.parseRetryDirective 同模式） */
    private final com.google.gson.Gson gson = new com.google.gson.Gson();

    // ======================== 构造函数 ========================

    public TLErrorSupervisorAgent() {
        super();
    }

    public TLErrorSupervisorAgent(String name) {
        super(name);
    }

    public TLErrorSupervisorAgent(String name, cn.tianlong.tlobject.base.TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    // ======================== 生命周期 ========================

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            correctionPromptTemplate = params.getOrDefault("correctionPromptTemplate",
                    "你之前的回答存在问题，请修正。\n\n"
                            + "原始请求：{{userMessage}}\n\n"
                            + "你的回答：{{response}}\n\n"
                            + "审查意见：{{suggestion}}\n\n"
                            + "请根据审查意见重新处理。如果确实无法完成，请诚实告知用户原因。");
        }
    }

    // ======================== 消息路由 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if ("reviewChat".equals(msg.getAction())) {
            return reviewChat(fromWho, msg);
        }
        // 其余全部走标准 Agent 逻辑（chat/chatStream/listSkills/...）
        return super.checkMsgAction(fromWho, msg);
    }

    // ======================== 核心：审查 + 修正循环 ========================

    /**
     * 审查目标 agent 的 chat 返回结果。
     * msg.systemArgs[PRERESULT] = 目标 agent doChat() 的返回值。
     * <p>
     * 审查标准和输出格式在监管 agent 自己的 defaultSystemMessage 中定义
     * （通过 modulesParams 传入，自动带入 AGENT_CHAT 的 system prompt），
     * 此处只负责拼动态数据 + 循环控制。
     */
    private TLMsg reviewChat(Object fromWho, TLMsg msg) {
        TLMsg chatResult = (TLMsg) msg.getSystemParam(PRERESULT);
        if (chatResult == null) {
            putLog("reviewChat: PRERESULT is null, pass through", LogLevel.DEBUG);
            return msg;
        }

        // 配在谁的 afterMsgTable 里，fromWho 就是谁
        String targetAgent = fromWho instanceof TLBaseModule
                ? ((TLBaseModule) fromWho).getName() : null;
        // maxReviewRounds 从 afterMsgTable 的 <msg> 属性读取
        int maxReviewRounds = 1;
        String maxRoundsStr = msg.getStringParam("maxReviewRounds", null);
        if (maxRoundsStr != null) {
            try { maxReviewRounds = Integer.parseInt(maxRoundsStr); }
            catch (NumberFormatException ignored) {}
        }

        String response = chatResult.getStringParam(AI_P_RESPONSE, "");
        String userMessage = msg.getStringParam(AI_P_USERMESSAGE, "");
        String sessionId = msg.getStringParam(AI_P_SESSIONID, "");

        if (targetAgent == null) {
            putLog("reviewChat: cannot determine target agent, pass through", LogLevel.WARN);
            return chatResult;
        }
        if (response.isEmpty()) {
            putLog("reviewChat: empty response, pass through", LogLevel.DEBUG);
            return chatResult;
        }

        for (int round = 0; round <= maxReviewRounds; round++) {
            // 1. 用自己的 LLM 审查——直接发 chat 给自己
            String reviewPrompt = buildReviewPrompt(userMessage, response);
            TLMsg reviewResult = putMsg(this,
                    createMsg().setAction(AGENT_CHAT)
                            .setParam(AI_P_USERMESSAGE, reviewPrompt)
                            .setParam(AI_P_SESSIONID, sessionId + "_review_" + round));

            String verdict = reviewResult != null
                    ? reviewResult.getStringParam(AI_P_RESPONSE, "") : "";

            // 2. 解析 JSON 判决（与 Group.parseRetryDirective 同模式）
            if (isPass(verdict)) {
                putLog("reviewChat: pass (round " + round + ")", LogLevel.DEBUG);
                return chatResult;
            }

            // 3. 不合格 + 轮次用尽 → 终止
            if (round == maxReviewRounds) {
                putLog("reviewChat: review rounds exhausted for session " + sessionId, LogLevel.WARN);
                break;
            }

            // 4. 触发修正
            String suggestion = parseSuggestion(verdict);
            if (suggestion == null || suggestion.isEmpty()) {
                putLog("reviewChat: no suggestion parsed, pass through", LogLevel.WARN);
                return chatResult;
            }

            String correctionPrompt = buildCorrectionPrompt(userMessage, response, suggestion);
            putLog("reviewChat: triggering correction (round " + (round + 1) + "/" + maxReviewRounds
                    + ") suggestion=" + suggestion.substring(0, Math.min(80, suggestion.length())), LogLevel.INFO);

            TLMsg correctionMsg = createMsg()
                    .setAction(AGENT_CHAT)
                    .setParam(AI_P_USERMESSAGE, correctionPrompt)
                    .setParam(AI_P_SESSIONID, sessionId + "_fix_" + round);
            correctionMsg.setSystemParam(IGNOREAFTER, true);

            TLMsg corrected = putMsg(targetAgent, correctionMsg);
            if (corrected != null && corrected.parseBoolean(RESULT, false)) {
                response = corrected.getStringParam(AI_P_RESPONSE, response);
                chatResult = corrected;
            } else {
                putLog("reviewChat: correction failed, keep previous result", LogLevel.WARN);
                break;
            }
        }
        return chatResult;
    }

    // ======================== 辅助方法 ========================

    /**
     * 构造审查请求——只带动态数据。
     * 审查标准和输出格式在监管 agent 自己的 defaultSystemMessage 中定义。
     */
    private String buildReviewPrompt(String userMessage, String response) {
        return "请审查以下 Agent 回答是否合格。\n\n"
                + "用户请求：\n" + userMessage + "\n\n"
                + "Agent 回答：\n" + response;
    }

    /**
     * 构造修正请求——模板从自身 params 读取，支持 {{变量}} 替换。
     */
    private String buildCorrectionPrompt(String userMsg, String response, String suggestion) {
        return correctionPromptTemplate
                .replace("{{userMessage}}", userMsg)
                .replace("{{response}}", response)
                .replace("{{suggestion}}", suggestion);
    }

    /**
     * 宽容解析审查 JSON，判断是否通过。
     * 截取首个 '{' 到末个 '}'（剥掉可能的 ```json 围栏），
     * 取 "pass" 字段。解析失败默认 true（不阻塞正常流程）。
     */
    private boolean isPass(String verdict) {
        if (verdict == null || verdict.isEmpty()) return true;
        try {
            int start = verdict.indexOf('{');
            int end = verdict.lastIndexOf('}');
            if (start < 0 || end <= start) return true;
            com.google.gson.JsonObject obj =
                    gson.fromJson(verdict.substring(start, end + 1), com.google.gson.JsonObject.class);
            if (obj == null || !obj.has("pass")) return true;
            return obj.get("pass").getAsBoolean();
        } catch (Exception e) {
            putLog("isPass parse error, default pass: " + e.toString(), LogLevel.DEBUG);
            return true;
        }
    }

    /**
     * 宽容解析审查 JSON，提取 suggestion 字段。
     * 解析失败返回 null（调用方会跳过修正）。
     */
    private String parseSuggestion(String verdict) {
        if (verdict == null || verdict.isEmpty()) return null;
        try {
            int start = verdict.indexOf('{');
            int end = verdict.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            com.google.gson.JsonObject obj =
                    gson.fromJson(verdict.substring(start, end + 1), com.google.gson.JsonObject.class);
            if (obj == null || !obj.has("suggestion")) return null;
            return obj.get("suggestion").getAsString();
        } catch (Exception e) {
            putLog("parseSuggestion error: " + e.toString(), LogLevel.DEBUG);
            return null;
        }
    }
}
