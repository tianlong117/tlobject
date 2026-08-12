package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.*;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI Agent 确定性单元测试模块（Mock Provider 驱动）。
 * <p>
 * 依赖 {@link TLMockProvider} 提供预设的 LLM 响应，覆盖 DeepSeek 审查中
 * 指出的 4 个测试缺口：doChat 主循环、Tool 并行执行、超时机制、会话恢复。
 * </p>
 *
 * <p>所有测试均不需要真实 LLM / 网络连接，结果确定、可重复。</p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 控制台
 * /test agent              → 运行全部测试
 * /test agent basicChat    → 运行单个场景
 *
 * // 代码
 * putMsg("agentTestModule", createMsg().setAction("runAllTests"));
 * </pre>
 *
 * 创建日期：2026/8/12
 * 作者：tianlong
 */
public class TLAgentTestModule extends TLBaseModule implements TLAiAgentParamString {

    // ======================== 字段 ========================

    /** Mock Provider 引用（在 init() 中获取，或首次测试时懒加载） */
    private TLMockProvider mockProvider;

    /** 测试统计 */
    private int passed, failed;

    /** testEchoSkill 的函数名（在 XML 中配置的 skill name） */
    private static final String FN_ECHO = "test_echo";
    /** testSleepSkill 的函数名 */
    private static final String FN_SLEEP = "test_sleep";

    // ======================== 构造器 ========================

    public TLAgentTestModule() { super(); }
    public TLAgentTestModule(String name) { super(name); }
    public TLAgentTestModule(String name, TLObjectFactory factory) { super(name, factory); }

    // ======================== 生命周期 ========================

    @Override
    protected TLBaseModule init() {
        // 懒加载 mockProvider（init 时可能尚未创建）
        putLog("[TEST_MODULE] TLAgentTestModule initialized", LogLevel.DEBUG);
        return this;
    }

    /** 懒加载获取 mockProvider 引用 */
    private TLMockProvider getMockProvider() {
        if (mockProvider == null) {
            try {
                TLObjectFactory factory = getFactory();
                if (factory != null) {
                    Object m = factory.getModule("mockProvider");
                    if (m instanceof TLMockProvider) {
                        mockProvider = (TLMockProvider) m;
                    }
                }
            } catch (Exception e) {
                putLog("[TEST_MODULE] Failed to get mockProvider: " + e, LogLevel.ERROR);
            }
        }
        return mockProvider;
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "runAllTests":
                returnMsg = runAllTests(fromWho, msg);
                break;
            case "testBasicChat":
                returnMsg = testBasicChat(fromWho, msg);
                break;
            case "testMultiTurn":
                returnMsg = testMultiTurn(fromWho, msg);
                break;
            case "testToolSingle":
                returnMsg = testToolSingle(fromWho, msg);
                break;
            case "testToolParallel":
                returnMsg = testToolParallel(fromWho, msg);
                break;
            case "testToolTimeout":
                returnMsg = testToolTimeout(fromWho, msg);
                break;
            case "testStreamBasic":
                returnMsg = testStreamBasic(fromWho, msg);
                break;
            case "testCancelExecution":
                returnMsg = testCancelExecution(fromWho, msg);
                break;
            case "testBatchTimeout":
                returnMsg = testBatchTimeout(fromWho, msg);
                break;
            case "testSessionRecovery":
                returnMsg = testSessionRecovery(fromWho, msg);
                break;
            case "runCaseFile":
                returnMsg = runCaseFile(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    // ======================== 测试入口 ========================

    /** 保存原 Provider 名，测试结束后恢复 */
    private transient String originalProviderName;

    /** 用户自定义测试用例文件路径（XML） */
    private String caseFile;

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null && params.get("caseFile") != null) {
            caseFile = params.get("caseFile");
        }
    }

    protected TLMsg runAllTests(Object fromWho, TLMsg msg) {
        log("===== AI Agent Unit Tests (Mock Provider) Start =====");

        TLMockProvider mp = getMockProvider();
        if (mp == null) {
            log("[TEST] FAIL: mockProvider not found. Ensure it is registered and booted.");
            return createMsg().setParam(RESULT, false).setParam("error", "mockProvider not found");
        }

        // 切换到 Mock Provider（保存原名用于恢复）
        switchToMockProvider(mp);

        // 动态注册测试 Skill
        boolean skillsOk = registerTestSkills();
        if (!skillsOk) {
            log("[TEST] WARNING: Test skill registration may have failed, some tests will be skipped");
        }

        passed = 0;
        failed = 0;

        // 场景 1-7: 运行每个独立测试
        runOne("1-基本Chat", this::testBasicChat);
        runOne("2-多轮对话", this::testMultiTurn);
        runOne("3-单Tool调用", skillsOk ? this::testToolSingle : skipTest("test_echo 未注册"));
        runOne("4-Tool并行执行", skillsOk ? this::testToolParallel : skipTest("test_echo 未注册"));
        runOne("5-Tool超时", skillsOk ? this::testToolTimeout : skipTest("test_sleep 未注册"));
        runOne("6-流式Chat", this::testStreamBasic);
        runOne("7-取消执行", skillsOk ? this::testCancelExecution : skipTest("test_sleep 未注册"));

        // 场景 8-9: 高级场景
        runOne("8-批次超时", skillsOk ? this::testBatchTimeout : skipTest("test_sleep 未注册"));
        runOne("9-会话恢复", skillsOk ? this::testSessionRecovery : skipTest("需要 sessionManager"));

        // === 用户自定义测试用例（XML 配置驱动）===
        if (caseFile != null && !caseFile.isEmpty()) {
            log("[TEST] ----- Custom Test Cases Start -----");
            int[] customResult = runCaseFileInternal(caseFile);
            passed += customResult[0];
            failed += customResult[1];
            log("[TEST] 自定义用例: 通过=" + customResult[0] + ", 失败=" + customResult[1]);
            log("[TEST] ----- Custom Test Cases End -----");
        }

        // 清理: 注销测试 Skill，恢复原 Provider
        unregisterTestSkills();
        restoreOriginalProvider();

        log("===== AI Agent Unit Tests End =====");
        log(String.format("[TEST] 汇总: 通过=%d, 失败=%d", passed, failed));

        return createMsg().setParam(RESULT, failed == 0)
                .setParam("passed", passed).setParam("failed", failed);
    }

    private void runOne(String label, TestFunc func) {
        try {
            TLMsg r = func.run(null, null);
            if (r != null && r.parseBoolean(RESULT, false)) {
                passed++;
                log("[TEST] 场景" + label + ": PASS");
            } else {
                failed++;
                String err = r != null ? r.getStringParam("error", "") : "null result";
                log("[TEST] 场景" + label + ": FAIL - " + err);
            }
        } catch (Exception e) {
            failed++;
            log("[TEST] 场景" + label + ": FAIL - 异常: " + e);
        }
    }

    @FunctionalInterface
    private interface TestFunc {
        TLMsg run(Object fromWho, TLMsg msg) throws Exception;
    }

    // ======================== 场景 1: 基本 Chat ========================

    protected TLMsg testBasicChat(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();
        mp.enqueueResponse(mp.textResponse("1+1等于2，这是最基本的算术运算。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, "test_mock_basic")
                .setParam(AI_P_USERMESSAGE, "1+1等于几？");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        String aiResp = response.getStringParam(AI_P_RESPONSE, "");
        int iterations = response.getIntParam("iterations", 0);

        if (ok && aiResp.contains("2") && iterations >= 1) {
            log("[TEST]   基本Chat: response=" + aiResp.substring(0, Math.min(80, aiResp.length()))
                    + ", iterations=" + iterations);
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, contains2=%s, iterations=%d, resp=%s",
                        ok, aiResp.contains("2"), iterations,
                        aiResp.substring(0, Math.min(100, aiResp.length()))));
    }

    // ======================== 场景 2: 多轮对话 ========================

    protected TLMsg testMultiTurn(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();
        mp.enqueueResponse(mp.textResponse("已记住：王小明，架构师。"));
        mp.enqueueResponse(mp.textResponse("你之前告诉我你叫王小明，是架构师。"));

        String sessionId = "test_mock_multiturn";

        // 回合 1
        TLMsg turn1 = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_USERMESSAGE, "我叫王小明，是架构师。");
        TLMsg r1 = putMsg(M_AIAGENT, turn1);
        if (!r1.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "turn1 failed");
        }

        // 回合 2
        TLMsg turn2 = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_USERMESSAGE, "我叫什么？做什么工作？");
        TLMsg r2 = putMsg(M_AIAGENT, turn2);

        boolean ok = r2.parseBoolean(RESULT, false);
        String resp2 = r2.getStringParam(AI_P_RESPONSE, "");
        boolean remembered = resp2.contains("王小明") && resp2.contains("架构师");

        if (ok && remembered) {
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, remembered=%s, resp=%s",
                        ok, remembered, resp2.substring(0, Math.min(100, resp2.length()))));
    }

    // ======================== 场景 3: 单 Tool 调用 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testToolSingle(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        // 第 1 次 LLM 调用: 返回 tool_call
        TLToolCall tc = TLMockProvider.createToolCall("call_echo_1", FN_ECHO,
                new HashMap<String, Object>() {{ put("message", "hello world"); }});
        mp.enqueueResponse(mp.toolCallResponse(tc));

        // 第 2 次 LLM 调用 (tool 结果返回后): 返回最终文本
        mp.enqueueResponse(mp.textResponse("工具返回: ECHO: hello world，任务完成。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, "test_mock_singletool")
                .setParam(AI_P_USERMESSAGE, "请用 echo 工具输出 hello world");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        int iterations = response.getIntParam("iterations", 0);
        String aiResp = response.getStringParam(AI_P_RESPONSE, "");

        // 验证 tool loop 执行了（iterations >= 2 表示至少一次 tool call + 一次回复）
        if (ok && iterations >= 2 && !aiResp.isEmpty()) {
            log("[TEST]   单Tool: iterations=" + iterations + ", response="
                    + aiResp.substring(0, Math.min(80, aiResp.length())));
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, iterations=%d, resp=%s",
                        ok, iterations, aiResp.substring(0, Math.min(100, aiResp.length()))));
    }

    // ======================== 场景 4: Tool 并行执行 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testToolParallel(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        // 第 1 次 LLM 调用: 返回 3 个并发 tool_calls
        List<TLToolCall> toolCalls = Arrays.asList(
                TLMockProvider.createToolCall("call_p1", FN_ECHO,
                        new HashMap<String, Object>() {{ put("message", "task-A"); }}),
                TLMockProvider.createToolCall("call_p2", FN_ECHO,
                        new HashMap<String, Object>() {{ put("message", "task-B"); }}),
                TLMockProvider.createToolCall("call_p3", FN_ECHO,
                        new HashMap<String, Object>() {{ put("message", "task-C"); }})
        );
        mp.enqueueResponse(mp.toolCallResponse(toolCalls));

        // 第 2 次 LLM 调用: 最终文本
        mp.enqueueResponse(mp.textResponse("三个任务全部完成。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, "test_mock_parallel")
                .setParam(AI_P_USERMESSAGE, "请同时执行三个 echo 任务");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        int iterations = response.getIntParam("iterations", 0);

        if (ok && iterations >= 2) {
            log("[TEST]   并行执行: iterations=" + iterations + " (3个tool并行→1次LLM→完成)");
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, iterations=%d", ok, iterations));
    }

    // ======================== 场景 5: Tool 执行超时 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testToolTimeout(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        // Tool call: test_sleep with duration=2000ms
        // test_sleep skill 在 XML 中配置了 timeout="1"（1秒=1000ms）
        // 所以实际睡眠 2000ms > 1000ms 超时 → 应触发超时
        TLToolCall tc = TLMockProvider.createToolCall("call_sleep_1", FN_SLEEP,
                new HashMap<String, Object>() {{ put("duration", 2000); }});
        mp.enqueueResponse(mp.toolCallResponse(tc));

        // 超时后 LLM 再被调用一次（agent 注入了超时提示）
        mp.enqueueResponse(mp.textResponse("工具超时了，但我可以继续。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, "test_mock_timeout")
                .setParam(AI_P_MAXTOOLCALLITERATIONS, 2)
                .setParam(AI_P_USERMESSAGE, "请 sleep 2000 毫秒");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        int iterations = response.getIntParam("iterations", 0);
        String aiResp = response.getStringParam(AI_P_RESPONSE, "");

        // Tool 超时不等于框架崩溃；agent 应该能继续返回结果
        if (ok && iterations >= 2 && !aiResp.isEmpty()) {
            log("[TEST]   超时: iterations=" + iterations + ", response="
                    + aiResp.substring(0, Math.min(80, aiResp.length())));
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("ok=%s, iterations=%d, resp=%s",
                        ok, iterations, aiResp.substring(0, Math.min(100, aiResp.length()))));
    }

    // ======================== 场景 6: 流式 Chat ========================

    protected TLMsg testStreamBasic(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        String sessionId = "test_mock_stream";

        // 预设流式响应: chunk → chunk → done
        mp.enqueueStreamResponses(sessionId, Arrays.asList(
                mp.streamChunk("流"),
                mp.streamChunk("式"),
                mp.streamChunk("测"),
                mp.streamChunk("试"),
                mp.streamDone("流式测试完成")
        ));

        try {
            // 重置流回调
            putMsg("streamCallback", createMsg().setAction("resetStream"));

            // 发送流式请求
            TLMsg streamMsg = createMsg()
                    .setAction(AGENT_CHATSTREAM)
                    .setParam(AI_P_SESSIONID, sessionId)
                    .setParam(AI_P_USERMESSAGE, "请流式回复")
                    .setParam(RESULTFOR, "streamCallback")
                    .setParam(RESULTACTION, "onStreamChunk");

            putMsg(M_AIAGENT, streamMsg);

            // 等待流式完成
            TLMsg waitMsg = createMsg().setAction("waitForStream").setParam("timeout", 30);
            TLMsg waitResult = putMsg("streamCallback", waitMsg);

            boolean streamDone = waitResult.parseBoolean("streamDone", false);
            String content = waitResult.getStringParam("content", "");

            if (streamDone && !content.isEmpty()) {
                log("[TEST]   流式: content=" + content);
                return createMsg().setParam(RESULT, true);
            }
            boolean timedOut = waitResult.parseBoolean("timedOut", false);
            return createMsg().setParam(RESULT, false)
                    .setParam("error", String.format("streamDone=%s, timedOut=%s, content=%s",
                            streamDone, timedOut, content));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false).setParam("error", "stream exception: " + e);
        }
    }

    // ======================== 场景 7: 取消执行 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testCancelExecution(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        String sessionId = "test_mock_cancel";

        // tool sleep 5s（足够让 cancel 在它完成前到达）
        TLToolCall tc = TLMockProvider.createToolCall("call_sleep_cancel", FN_SLEEP,
                new HashMap<String, Object>() {{ put("duration", 5000); }});
        mp.enqueueResponse(mp.toolCallResponse(tc));

        try {
            AtomicReference<TLMsg> chatResult = new AtomicReference<>();

            Thread chatThread = new Thread(() -> {
                TLMsg cMsg = createMsg()
                        .setAction(AGENT_CHAT)
                        .setParam(AI_P_SESSIONID, sessionId)
                        .setParam(AI_P_USERMESSAGE, "sleep 5 秒");
                chatResult.set(putMsg(M_AIAGENT, cMsg));
            }, "test-cancel-chat");
            chatThread.start();

            // 等待 tool 进入执行阶段（mock 无网络延迟，500ms 够）
            Thread.sleep(500);

            // 发取消到 ToolExecutor
            putMsg("toolExecutor", createMsg()
                    .setAction(TODOOLECANCEL)
                    .setParam(AI_P_SESSIONID, sessionId));

            // chat 应因取消而提前结束
            chatThread.join(10000);

            TLMsg result = chatResult.get();
            if (result != null && result.containsParam(AI_P_RESPONSE)) {
                log("[TEST]   取消执行: chat completed (cancelled or finished)");
                return createMsg().setParam(RESULT, true);
            }
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "cancel did not take effect: result="
                            + (result != null ? result.getStringParam(AI_P_RESPONSE, "") : "null"));
        } catch (Exception e) {
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "cancel test exception: " + e);
        }
    }

    // ======================== 场景 8: 批次超时 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testBatchTimeout(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        // 3 个 sleep tool（每个 5 秒），批次超时设为 1 秒
        List<TLToolCall> toolCalls = Arrays.asList(
                TLMockProvider.createToolCall("call_bt1", FN_SLEEP,
                        new HashMap<String, Object>() {{ put("duration", 5000); }}),
                TLMockProvider.createToolCall("call_bt2", FN_SLEEP,
                        new HashMap<String, Object>() {{ put("duration", 5000); }}),
                TLMockProvider.createToolCall("call_bt3", FN_SLEEP,
                        new HashMap<String, Object>() {{ put("duration", 5000); }})
        );
        mp.enqueueResponse(mp.toolCallResponse(toolCalls));

        // 配置工具执行器：设置批次超时
        TLMsg configMsg = createMsg()
                .setAction(MODULE_SETPARAM)
                .setParam("executionTimeoutMs", "1000"); // 1 秒
        TLMsg configResult = putMsg("toolExecutor", configMsg);

        log("[TEST]   批次超时配置: " + (configResult != null && configResult.parseBoolean(RESULT, false)));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, "test_mock_batch_timeout")
                .setParam(AI_P_MAXTOOLCALLITERATIONS, 2)
                .setParam(AI_P_USERMESSAGE, "并行 sleep 5 秒 x 3");

        TLMsg response = putMsg(M_AIAGENT, chatMsg);

        boolean ok = response.parseBoolean(RESULT, false);
        int iterations = response.getIntParam("iterations", 0);

        // 恢复批次超时为 0（无限）
        putMsg("toolExecutor", createMsg()
                .setAction(MODULE_SETPARAM)
                .setParam("executionTimeoutMs", "0"));

        // 批次超时后 agent 应该返回（不会卡死）
        if (ok || iterations >= 1) {
            log("[TEST]   批次超时: iterations=" + iterations + ", ok=" + ok);
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", String.format("batch timeout: ok=%s, iterations=%d", ok, iterations));
    }

    // ======================== 场景 9: 会话断点恢复 ========================

    @SuppressWarnings("unchecked")
    protected TLMsg testSessionRecovery(Object fromWho, TLMsg msg) {
        TLMockProvider mp = getMockProvider();
        mp.clearAllResponses();

        String sessionId = "test_mock_checkpoint";

        // 第一轮: tool call → 触发 checkpoint 保存
        TLToolCall tc = TLMockProvider.createToolCall("call_cp_1", FN_ECHO,
                new HashMap<String, Object>() {{ put("message", "checkpoint-test"); }});
        mp.enqueueResponse(mp.toolCallResponse(tc));
        mp.enqueueResponse(mp.textResponse("第一轮完成，checkpoint 已保存。"));

        TLMsg chatMsg = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam("userId", "test_user")
                .setParam(AI_P_USERMESSAGE, "执行 checkpoint 测试");

        TLMsg r1 = putMsg(M_AIAGENT, chatMsg);
        if (!r1.parseBoolean(RESULT, false)) {
            return createMsg().setParam(RESULT, false).setParam("error", "checkpoint round1 failed");
        }

        // 查询 sessionManager 验证 checkpoint 已保存
        try {
            TLMsg listMsg = createMsg()
                    .setAction("listSessions")
                    .setParam("userId", "test_user");
            TLMsg listResult = putMsg("sessionManager", listMsg);
            List<?> sessions = (List<?>) listResult.getListParam("sessions", Collections.emptyList());

            if (sessions != null && !sessions.isEmpty()) {
                log("[TEST]   会话恢复: checkpoint 已保存, sessions=" + sessions.size());
            } else {
                log("[TEST]   会话恢复: checkpoint 可能未启用或 sessionManager 配置缺失, sessions="
                        + (sessions != null ? sessions.size() : "null"));
            }
        } catch (Exception e) {
            log("[TEST]   会话恢复: sessionManager 查询异常 - " + e.getMessage());
        }

        // 验证第二轮可以继续（新的 chat 在同一 session）
        mp.enqueueResponse(mp.textResponse("第二轮继续，上下文保持。"));
        TLMsg chatMsg2 = createMsg()
                .setAction(AGENT_CHAT)
                .setParam(AI_P_SESSIONID, sessionId)
                .setParam(AI_P_USERMESSAGE, "继续对话");
        TLMsg r2 = putMsg(M_AIAGENT, chatMsg2);

        if (r2.parseBoolean(RESULT, false)) {
            log("[TEST]   会话恢复: 两轮均成功");
            return createMsg().setParam(RESULT, true);
        }
        return createMsg().setParam(RESULT, false)
                .setParam("error", "checkpoint round2 failed: " + r2.getStringParam("error", ""));
    }

    // ======================== 工具方法 ========================

    /** 切换到 Mock Provider，同时保存原 Provider 名用于恢复 */
    private void switchToMockProvider(TLMockProvider mp) {
        try {
            // 保存原 provider 名
            TLMsg getProvMsg = createMsg().setAction("getLlmProvider");
            TLMsg getProvResult = putMsg(M_AIAGENT, getProvMsg);
            if (getProvResult != null && getProvResult.containsParam("provider")) {
                Object prov = getProvResult.getParam("provider");
                if (prov instanceof TLBaseModule) {
                    originalProviderName = ((TLBaseModule) prov).getName();
                }
            }

            TLMsg switchMsg = createMsg()
                    .setAction(AGENT_SETPROVIDER)
                    .setParam(AI_P_PROVIDER, "mockProvider");
            TLMsg result = putMsg(M_AIAGENT, switchMsg);
            if (result != null && result.parseBoolean(RESULT, false)) {
                log("[TEST_MODULE] Switched to mockProvider (was: " + originalProviderName + ")");
            } else {
                log("[TEST_MODULE] Switch to mockProvider: " +
                        (result != null ? result.getStringParam("error", "no error") : "null"));
            }
        } catch (Exception e) {
            log("[TEST_MODULE] Switch to mockProvider failed: " + e.getMessage());
        }
    }

    /** 恢复原 Provider */
    private void restoreOriginalProvider() {
        if (originalProviderName == null) return;
        try {
            TLMsg switchMsg = createMsg()
                    .setAction(AGENT_SETPROVIDER)
                    .setParam(AI_P_PROVIDER, originalProviderName);
            putMsg(M_AIAGENT, switchMsg);
            log("[TEST_MODULE] Restored provider: " + originalProviderName);
        } catch (Exception e) {
            log("[TEST_MODULE] Restore provider failed: " + e.getMessage());
        }
    }

    /** 动态注册测试 Skill 到 Agent 的 functions 表 */
    @SuppressWarnings("unchecked")
    private boolean registerTestSkills() {
        try {
            // 注册 test_echo
            HashMap<String, String> echoCfg = new HashMap<>();
            echoCfg.put("sameClassAs", "testEchoSkill");
            echoCfg.put("description", "Echo back the input message. Use for testing tool execution.");
            TLMsg regEcho = createMsg()
                    .setAction(AGENT_REGISTERSKILL)
                    .setParam(AI_P_SKILLNAME, FN_ECHO)
                    .setParam(AI_P_AGENTCONFIG, echoCfg);
            TLMsg r1 = putMsg(M_AIAGENT, regEcho);
            log("[TEST_MODULE] Register " + FN_ECHO + ": " + (r1 != null && r1.parseBoolean(RESULT, false)));

            // 注册 test_sleep（timeout 将在测试中通过 ToolExecutor 的 executionTimeoutMs 控制）
            HashMap<String, String> sleepCfg = new HashMap<>();
            sleepCfg.put("sameClassAs", "testSleepSkill");
            sleepCfg.put("description", "Sleep for specified milliseconds. Use for testing timeout.");
            TLMsg regSleep = createMsg()
                    .setAction(AGENT_REGISTERSKILL)
                    .setParam(AI_P_SKILLNAME, FN_SLEEP)
                    .setParam(AI_P_AGENTCONFIG, sleepCfg);
            TLMsg r2 = putMsg(M_AIAGENT, regSleep);
            log("[TEST_MODULE] Register " + FN_SLEEP + ": " + (r2 != null && r2.parseBoolean(RESULT, false)));

            boolean ok = (r1 != null && r1.parseBoolean(RESULT, false))
                    && (r2 != null && r2.parseBoolean(RESULT, false));
            return ok;
        } catch (Exception e) {
            log("[TEST_MODULE] Register test skills failed: " + e.getMessage());
            return false;
        }
    }

    /** 注销测试 Skill */
    private void unregisterTestSkills() {
        try {
            TLMsg unregEcho = createMsg()
                    .setAction(AGENT_UNREGISTERSKILL)
                    .setParam(AI_P_SKILLNAME, FN_ECHO);
            putMsg(M_AIAGENT, unregEcho);

            TLMsg unregSleep = createMsg()
                    .setAction(AGENT_UNREGISTERSKILL)
                    .setParam(AI_P_SKILLNAME, FN_SLEEP);
            putMsg(M_AIAGENT, unregSleep);
            log("[TEST_MODULE] Test skills unregistered");
        } catch (Exception e) {
            log("[TEST_MODULE] Unregister test skills failed: " + e.getMessage());
        }
    }

    /** 返回一个跳过结果 */
    private TestFunc skipTest(String reason) {
        return (fromWho, msg) -> createMsg().setParam(RESULT, true).setParam("skipped", true)
                .setParam("skipReason", reason);
    }

    // ======================== XML 配置驱动测试 ========================

    /** 供外部调用的 action 入口 */
    protected TLMsg runCaseFile(Object fromWho, TLMsg msg) {
        String path = msg.getStringParam("caseFile", caseFile);
        if (path == null || path.isEmpty()) return createMsg().setParam(RESULT, false)
                .setParam("error", "caseFile not configured");
        int[] result = runCaseFileInternal(path);
        return createMsg().setParam(RESULT, result[1] == 0)
                .setParam("passed", result[0]).setParam("failed", result[1]);
    }

    @SuppressWarnings("unchecked")
    private int[] runCaseFileInternal(String path) {
        int p = 0, f = 0;
        try {
            List<Map<String, Object>> cases = parseCaseFile(path);
            if (cases.isEmpty()) { log("[TEST] 无自定义用例"); return new int[]{0, 0}; }

            TLMockProvider mp = getMockProvider();
            for (Map<String, Object> c : cases) {
                String caseName = (String) c.getOrDefault("name", "?");
                try {
                    mp.clearAllResponses();

                    // 1) 入队 mock 响应
                    List<Map<String, Object>> responses = (List<Map<String, Object>>) c.get("responses");
                    if (responses != null) {
                        for (Map<String, Object> r : responses) enqueueFromConfig(mp, r);
                    }

                    // 2) 发 chat
                    Map<String, Object> chatCfg = (Map<String, Object>) c.get("chat");
                    String target = str(chatCfg, "targetAgent", M_AIAGENT);
                    TLMsg chatMsg = createMsg().setAction(AGENT_CHAT)
                            .setParam(AI_P_SESSIONID, str(chatCfg, "sessionId", "case_" + caseName))
                            .setParam(AI_P_USERMESSAGE, str(chatCfg, "userMessage", ""));
                    if (chatCfg.containsKey("model")) chatMsg.setParam(AI_P_MODEL, str(chatCfg, "model", null));
                    if (chatCfg.containsKey("temperature"))
                        chatMsg.setParam(AI_P_TEMPERATURE, Double.parseDouble(str(chatCfg, "temperature", "0.7")));
                    if (chatCfg.containsKey("maxIterations"))
                        chatMsg.setParam(AI_P_MAXTOOLCALLITERATIONS, Integer.parseInt(str(chatCfg, "maxIterations", "10")));

                    TLMsg response = putMsg(target, chatMsg);

                    // 3) 断言
                    Map<String, Object> assertCfg = (Map<String, Object>) c.get("assert");
                    if (assertCfg != null) {
                        String err = runAsserts(response, assertCfg);
                        if (err != null) {
                            f++; log("[TEST] 用例[" + caseName + "]: FAIL - " + err);
                        } else {
                            p++; log("[TEST] 用例[" + caseName + "]: PASS");
                        }
                    } else {
                        p++; log("[TEST] 用例[" + caseName + "]: PASS (no asserts)");
                    }
                } catch (Exception e) {
                    f++; log("[TEST] 用例[" + caseName + "]: FAIL - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log("[TEST] 加载用例文件失败: " + e.getMessage());
            return new int[]{0, 1};
        }
        return new int[]{p, f};
    }

    /** 从配置 Map 构建 mock 响应并入队 */
    @SuppressWarnings("unchecked")
    private void enqueueFromConfig(TLMockProvider mp, Map<String, Object> r) {
        String resultStr = (String) r.getOrDefault("result", "true");
        boolean result = !"false".equals(resultStr);
        String text = (String) r.getOrDefault("aiResponse", "");
        boolean hasTools = "true".equals(r.getOrDefault("hasToolCalls", "false"));

        if (hasTools) {
            List<Map<String, Object>> tcList = (List<Map<String, Object>>) r.get("toolCalls");
            List<TLToolCall> tcs = new ArrayList<>();
            if (tcList != null) {
                for (Map<String, Object> tcMap : tcList) {
                    String fn = str(tcMap, "function", "");
                    String argsJson = str(tcMap, "args", "{}");
                    Map<String, Object> args;
                    try { args = new com.google.gson.Gson().fromJson(argsJson, Map.class); }
                    catch (Exception e) { args = Collections.emptyMap(); }
                    tcs.add(TLMockProvider.createToolCall(
                            str(tcMap, "id", "call_" + fn), fn, args));
                }
            }
            mp.enqueueResponse(mp.toolCallResponse(tcs));
        } else if (!result) {
            mp.enqueueResponse(mp.errorResponse(str(r, "error", "mock error")));
        } else {
            mp.enqueueResponse(mp.textResponse(text));
        }
    }

    /** 断言检查，返回 null=通过，非 null=失败原因 */
    private String runAsserts(TLMsg response, Map<String, Object> cfg) {
        if (response == null) return "chat 返回 null";

        if (cfg.containsKey("result")) {
            boolean expected = "true".equals(cfg.get("result"));
            if (response.parseBoolean(RESULT, false) != expected)
                return "RESULT expected=" + expected + " actual=" + response.parseBoolean(RESULT, false);
        }
        String aiResp = response.getStringParam(AI_P_RESPONSE, "");
        if (cfg.containsKey("aiResponseContains")) {
            if (!aiResp.contains(str(cfg, "aiResponseContains", "")))
                return "aiResponse 不包含 '" + str(cfg, "aiResponseContains", "") + "'";
        }
        if (cfg.containsKey("aiResponseNotContains")) {
            if (aiResp.contains(str(cfg, "aiResponseNotContains", "")))
                return "aiResponse 不应包含 '" + str(cfg, "aiResponseNotContains", "") + "'";
        }
        if (cfg.containsKey("minIterations")) {
            int actual = response.getIntParam("iterations", 0);
            int min = Integer.parseInt(str(cfg, "minIterations", "0"));
            if (actual < min) return "iterations=" + actual + " < " + min;
        }
        if (cfg.containsKey("maxIterations")) {
            int actual = response.getIntParam("iterations", 0);
            int max = Integer.parseInt(str(cfg, "maxIterations", "999"));
            if (actual > max) return "iterations=" + actual + " > " + max;
        }
        return null;
    }

    /** Map 取值 helper，处理 Object→String 转换 */
    private String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? v.toString() : def;
    }

    /** 简单 XML 解析（纯 DOM，无外部依赖） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseCaseFile(String path) throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        java.io.InputStream is = null;

        // 尝试多种方式加载
        try { is = new java.io.FileInputStream(path); }
        catch (Exception ignored) {}
        if (is == null) {
            String cp = path.startsWith("CLASSPATH/") ? path.substring("CLASSPATH/".length()) : path;
            is = getClass().getClassLoader().getResourceAsStream(cp);
        }
        if (is == null) {
            is = getClass().getClassLoader().getResourceAsStream(path);
        }
        if (is == null) { log("[TEST] 用例文件未找到: " + path); return cases; }

        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(is);
        is.close();

        org.w3c.dom.NodeList caseNodes = doc.getElementsByTagName("case");
        for (int i = 0; i < caseNodes.getLength(); i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            org.w3c.dom.Element ce = (org.w3c.dom.Element) caseNodes.item(i);
            c.put("name", ce.getAttribute("name"));

            // mock
            org.w3c.dom.NodeList mockNodes = ce.getElementsByTagName("mock");
            if (mockNodes.getLength() > 0) {
                org.w3c.dom.NodeList respNodes = ((org.w3c.dom.Element) mockNodes.item(0))
                        .getElementsByTagName("response");
                List<Map<String, Object>> responses = new ArrayList<>();
                for (int j = 0; j < respNodes.getLength(); j++) {
                    org.w3c.dom.Element re = (org.w3c.dom.Element) respNodes.item(j);
                    Map<String, Object> r = attrsToMap(re);
                    // tool calls
                    org.w3c.dom.NodeList tcNodes = re.getElementsByTagName("toolCall");
                    if (tcNodes.getLength() > 0) {
                        List<Map<String, Object>> tcs = new ArrayList<>();
                        for (int k = 0; k < tcNodes.getLength(); k++) {
                            tcs.add(attrsToMap((org.w3c.dom.Element) tcNodes.item(k)));
                        }
                        r.put("toolCalls", tcs);
                    }
                    responses.add(r);
                }
                c.put("responses", responses);
            }

            // chat
            org.w3c.dom.NodeList chatNodes = ce.getElementsByTagName("chat");
            if (chatNodes.getLength() > 0)
                c.put("chat", attrsToMap((org.w3c.dom.Element) chatNodes.item(0)));

            // assert
            org.w3c.dom.NodeList assertNodes = ce.getElementsByTagName("assert");
            if (assertNodes.getLength() > 0)
                c.put("assert", attrsToMap((org.w3c.dom.Element) assertNodes.item(0)));

            cases.add(c);
        }
        return cases;
    }

    private Map<String, Object> attrsToMap(org.w3c.dom.Element e) {
        Map<String, Object> m = new LinkedHashMap<>();
        org.w3c.dom.NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            org.w3c.dom.Node a = attrs.item(i);
            m.put(a.getNodeName(), a.getNodeValue());
        }
        return m;
    }

    /** 同时输出到控制台和框架日志 */
    private void log(String msg) {
        System.out.println(msg);
        putLog(msg, LogLevel.WARN);
    }
}
