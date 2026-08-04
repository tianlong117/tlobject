package cn.tianlong.tlobject.uniagent;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具执行引擎 — 收到 tool_calls，putMsg 到对应模块，收集结果返回。
 *
 * 核心理念：所有工具执行都是 putMsg(工具名, msg)。不区分类型，不关心模块内部如何实现。
 * 引擎只负责：调度、并行、停止、收集结果。
 *
 * 执行协议：向工具模块发送 execute 消息，携带 toolCallId + toolArguments，
 * 模块返回 skillOutput 作为执行结果。
 */
public class ToolExecutionEngine extends TLBaseModule implements UniAgentParamString {

    /** 用于并行工具执行的线程池 */
    private ExecutorService toolExecutor;

    /** 取消标志: sessionId → AtomicBoolean */
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** 运行中的工具任务: sessionId → List<Future<?>> */
    private final Map<String, List<Future<?>>> runningTasks = new ConcurrentHashMap<>();

    // ======================== 生命周期 ========================

    @Override
    protected TLBaseModule init() {
        // 默认线程池大小为 CPU 核数 × 2
        toolExecutor = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        return this;
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        if (toolExecutor != null) {
            toolExecutor.shutdownNow();
        }
        return super.destroy(fromWho, msg);
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action = msg.getAction();
        if (action == null) return null;

        switch (action) {
            case ENGINE_EXECUTETOOLS: return executeTools(fromWho, msg);
            case AGENT_STOPCHAT:      return cancelExecution(fromWho, msg);
            default: break;
        }
        return null;
    }

    // ======================== 核心：执行工具调用 ========================

    /**
     * 执行工具调用列表。
     * 入参: AI_P_TOOLCALLS (List<TLToolCall>), AI_P_SESSIONID
     * 返回: AI_P_TOOLRESULTS (List<ToolCallResult>)
     */
    @SuppressWarnings("unchecked")
    protected TLMsg executeTools(Object fromWho, TLMsg msg) {
        List<TLToolCall> toolCalls = (List<TLToolCall>) msg.getParam(AI_P_TOOLCALLS);
        String sessionId = (String) msg.getParam(AI_P_SESSIONID);

        if (toolCalls == null || toolCalls.isEmpty()) {
            return createMsg().setParam(AI_P_TOOLRESULTS, Collections.emptyList());
        }

        // 初始化取消标志
        cancelFlags.put(sessionId, new AtomicBoolean(false));

        int n = toolCalls.size();
        CountDownLatch latch = new CountDownLatch(n);
        List<ToolCallResult> results = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            final TLToolCall tc = toolCalls.get(i);
            final int idx = i;

            Future<?> future = toolExecutor.submit(() -> {
                try {
                    if (cancelFlags.get(sessionId).get()) {
                        results.add(ToolCallResult.cancelled(tc));
                        return;
                    }
                    ToolCallResult result = executeOneTool(tc, fromWho, sessionId);
                    results.add(result);
                } catch (Exception e) {
                    results.add(ToolCallResult.error(tc, e.toString()));
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        runningTasks.put(sessionId, futures);

        try {
            // 等待所有工具执行完成，或超时（默认 5 分钟）
            long timeout = 300_000L;
            if (msg.getParam("toolTimeout") instanceof Number) {
                timeout = ((Number) msg.getParam("toolTimeout")).longValue();
            }
            boolean completed = latch.await(timeout, TimeUnit.MILLISECONDS);
            if (!completed) {
                putLog("Tool execution timeout for session: " + sessionId, LogLevel.WARN);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 取消所有未完成的工具
            cancelFlags.get(sessionId).set(true);
            for (Future<?> f : futures) {
                f.cancel(true);
            }
        } finally {
            runningTasks.remove(sessionId);
            cancelFlags.remove(sessionId);
        }

        // 按原始顺序排列结果
        results.sort(Comparator.comparingInt(r -> toolCalls.indexOf(r.toolCall)));

        TLMsg result = createMsg();
        result.setParam(AI_P_TOOLRESULTS, new ArrayList<>(results));

        // 检查是否有取消
        boolean anyCancelled = results.stream().anyMatch(r -> r.cancelled);
        if (anyCancelled) {
            result.setParam(AI_P_CANCELLED, true);
        }

        return result;
    }

    /** 执行单个工具调用 */
    private ToolCallResult executeOneTool(TLToolCall tc, Object fromWho, String sessionId) {
        String toolName = tc.getFunctionName();
        Map<String, Object> args = tc.getArguments();

        // 工具名 = 模块名 = destination，构造执行消息
        TLMsg execMsg = createMsg()
                .setAction(SKILL_EXECUTE)          // 通用执行动作
                .setParam(AI_P_TOOLID, tc.getId())
                .setParam(AI_P_TOOLNAME, toolName)
                .setParam(AI_P_TOOLARGUMENTS, args)
                .setParam(AI_P_SKILLINPUT, args)
                .setParam(AI_P_SESSIONID, sessionId);

        try {
            TLMsg result = putMsg(toolName, execMsg);

            if (result == null) {
                return ToolCallResult.error(tc, "No response from tool: " + toolName);
            }

            // 提取执行结果
            String output = null;
            if (result.getParam(AI_P_SKILLOUTPUT) != null) {
                Object out = result.getParam(AI_P_SKILLOUTPUT);
                output = out instanceof String ? (String) out : out.toString();
            } else if (result.getParam(AI_P_RESPONSE) != null) {
                Object out = result.getParam(AI_P_RESPONSE);
                output = out instanceof String ? (String) out : out.toString();
            } else if (result.getParam(RESULT) != null) {
                output = String.valueOf(result.getParam(RESULT));
            } else {
                output = "[Tool executed: " + toolName + "]";
            }

            return ToolCallResult.success(tc, output);
        } catch (Exception e) {
            return ToolCallResult.error(tc, e.toString());
        }
    }

    // ======================== 取消执行 ========================

    /** 取消指定 session 的工具执行 */
    protected TLMsg cancelExecution(Object fromWho, TLMsg msg) {
        String sessionId = (String) msg.getParam(AI_P_SESSIONID);
        if (sessionId == null) {
            return createMsg().setParam(AI_P_RESPONSE, "Missing sessionId");
        }

        AtomicBoolean flag = cancelFlags.get(sessionId);
        if (flag != null) {
            flag.set(true);
        }

        List<Future<?>> tasks = runningTasks.get(sessionId);
        if (tasks != null) {
            for (Future<?> f : tasks) {
                f.cancel(true);
            }
        }

        putLog("Cancelled tool execution for session: " + sessionId, LogLevel.INFO);
        return createMsg()                .setParam(AI_P_RESPONSE, "Cancelled: " + sessionId);
    }

    // ======================== 结果数据类 ========================

    /** 单个工具调用的执行结果 */
    public static class ToolCallResult {
        public final TLToolCall toolCall;
        public final String output;
        public final String error;
        public final boolean cancelled;
        public final boolean success;

        private ToolCallResult(TLToolCall toolCall, String output, String error, boolean cancelled) {
            this.toolCall = toolCall;
            this.output = output;
            this.error = error;
            this.cancelled = cancelled;
            this.success = error == null && !cancelled;
        }

        static ToolCallResult success(TLToolCall tc, String output) {
            return new ToolCallResult(tc, output, null, false);
        }

        static ToolCallResult error(TLToolCall tc, String error) {
            return new ToolCallResult(tc, null, error, false);
        }

        static ToolCallResult cancelled(TLToolCall tc) {
            return new ToolCallResult(tc, null, null, true);
        }

        @Override
        public String toString() {
            if (cancelled) return "ToolCallResult{cancelled, tool=" + toolCall.getFunctionName() + "}";
            if (error != null) return "ToolCallResult{error=" + error + ", tool=" + toolCall.getFunctionName() + "}";
            return "ToolCallResult{output="
                    + (output != null ? output.substring(0, Math.min(100, output.length())) : "null")
                    + ", tool=" + toolCall.getFunctionName() + "}";
        }
    }
}
