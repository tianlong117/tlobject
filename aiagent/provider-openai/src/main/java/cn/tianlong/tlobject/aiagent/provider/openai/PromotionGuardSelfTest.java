package cn.tianlong.tlobject.aiagent.provider.openai;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.GsonBuilder;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.util.ArrayList;
import java.util.List;

/**
 * "空正文把 reasoning 提升为正文" 守卫自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>背景：该兜底（f9cbc75 引入）原意是救"答案全在 reasoning 里"（模型正常收尾 stop、正文为空）。
 * 但被两种情形滥用，把英文思考当正文入库并污染历史/下游聚合（写诗、汉堡披萨多轮实测）：
 * <ul>
 *   <li>工具回合：载荷在 tool_calls，正文为空是常态（该模型 tool 回合 content 恒为空），推理只是独白；</li>
 *   <li>截断回合：finish_reason=length，推理吃光预算，独白不是答案。</li>
 * </ul>
 * 判定：正文空 + 确有推理 + 非工具回合 + 非截断 才提升。
 *
 * <p>运行：java -cp "provider-openai/target/classes;...;gson.jar;okhttp.jar;okio.jar" \
 *      cn.tianlong.tlobject.aiagent.provider.openai.PromotionGuardSelfTest
 * 失败以退出码 1 结束（可直接接进脚本）。
 */
public class PromotionGuardSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // ================= 非流式 parseResponse =================
        TestProvider p = new TestProvider();

        TLMsg r1 = p.parseResponse(json("", "let me think about the tools", toolCalls(), "tool_calls"), new TLMsg());
        check("parseResponse 工具回合：推理不塞正文（正文留空）",
                isEmpty(r1.getStringParam(AI_P_RESPONSE, "")));

        TLMsg r2 = p.parseResponse(json("", "I ran out of budget…", null, "length"), new TLMsg());
        check("parseResponse 截断回合：推理不塞正文（正文留空）",
                isEmpty(r2.getStringParam(AI_P_RESPONSE, "")));

        TLMsg r3 = p.parseResponse(json("", "the answer is 42", null, "stop"), new TLMsg());
        check("parseResponse 正常收尾：保留原兜底（答案在推理里 → 提升）",
                "the answer is 42".equals(r3.getStringParam(AI_P_RESPONSE, "")));

        // ================= 流式 StreamCallback（done 事件） =================
        TestProvider s1 = new TestProvider();
        s1.feedStream(sse(deltaReasoning("let me think about the tools"),
                deltaToolCall("c1", "f", "{}"), finishChunk("tool_calls")));
        check("流式 工具回合：done 正文为空（不提升）",
                isEmpty(s1.lastDone().getStringParam(AI_P_RESPONSE, "")));
        check("流式 工具回合：done 仍带 toolCalls",
                "c1".equals(firstToolCallId(s1.lastDone())));

        TestProvider s2 = new TestProvider();
        s2.feedStream(sse(deltaReasoning("I ran out of budget…"), finishChunk("length")));
        TLMsg done2 = s2.lastDone();
        check("流式 截断回合：done 正文为空（不提升）",
                isEmpty(done2.getStringParam(AI_P_RESPONSE, "")));
        check("流式 截断回合：done 带 finish_reason=length（供上层如实告知）",
                "length".equals(done2.getStringParam(AI_P_FINISH_REASON, "")));

        TestProvider s3 = new TestProvider();
        s3.feedStream(sse(deltaReasoning("the answer is 42"), finishChunk("stop")));
        check("流式 正常收尾：保留原兜底（提升）",
                "the answer is 42".equals(s3.lastDone().getStringParam(AI_P_RESPONSE, "")));

        // ================= trace 文件大小上限轮转 =================
        // 背景：某 provider 的文件名落到 "default" 后，所有会话/所有日子的调用全追加进同一个
        // .trace（实测 176MB 且无轮转）。上限轮转保证任何单文件有界（当前 + .1 两份）。
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("tlTraceRotation");
            TestProvider tr = new TestProvider();
            String body = "{\"payload\":\"" + "x".repeat(1500) + "\"}";
            tr.writeTracesForRotation(tmp.toString(), 4000, 8, body);
            try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(tmp)) {
                java.util.List<java.nio.file.Path> files = st.collect(java.util.stream.Collectors.toList());
                long maxLen = files.stream().mapToLong(fp -> {
                    try { return java.nio.file.Files.size(fp); } catch (Exception ex) { return 0; }
                }).max().orElse(0);
                check("trace 轮转：文件数≤2（当前 + .1）", files.size() <= 2);
                check("trace 轮转：确有转储（8 次写入触发）", files.size() == 2);
                check("trace 轮转：单文件有界（≤上限×1.6）", maxLen <= 6400);
            }
            try (java.util.stream.Stream<java.nio.file.Path> st = java.nio.file.Files.list(tmp)) {
                for (java.nio.file.Path fp : st.collect(java.util.stream.Collectors.toList())) {
                    java.nio.file.Files.deleteIfExists(fp);
                }
            }
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception e) {
            check("trace 轮转：执行异常 " + e, false);
        }

        System.out.println("========================================");
        System.out.println("PromotionGuardSelfTest: passed=" + passed + ", failed=" + failed);
        if (failed > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    // ======================== 断言 ========================

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static String firstToolCallId(TLMsg m) {
        Object v = m.getParam(AI_P_TOOLCALLS);
        if (!(v instanceof List) || ((List<?>) v).isEmpty()) return "";
        Object first = ((List<?>) v).get(0);
        try {
            return (String) first.getClass().getMethod("getId").invoke(first);
        } catch (Exception e) {
            return "";
        }
    }

    // ======================== 测试替身 ========================

    /** 捕获出站消息、屏蔽日志/工厂依赖的 Provider 替身 */
    static class TestProvider extends TLOpenAiProvider implements TLAiAgentParamString {
        final List<TLMsg> sent = new ArrayList<>();

        TestProvider() {
            super("promotionGuardSelfTest");
            gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
        }

        @Override
        public TLMsg putMsg(String moduleName, TLMsg msg) {
            sent.add(msg);
            return null;
        }

        @Override
        public void putLog(String content, LogLevel logLevel) { /* 自测静默 */ }

        /** 把一段 SSE 文本喂给流式回调，模拟 provider 收到 HTTP 响应 */
        void feedStream(String sseText) {
            Response resp = new Response.Builder()
                    .request(new Request.Builder().url("http://localhost/v1/chat/completions").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create(MediaType.parse("text/event-stream"), sseText))
                    .build();
            try {
                new StreamCallback("selfTest", "onStreamChunk", "selfTestSession", new TLMsg())
                        .onResponse(null, resp);
            } catch (Exception e) {
                System.out.println("[ERROR] feedStream: " + e);
            }
        }

        TLMsg lastDone() {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).parseBoolean(AI_P_STREAMDONE, false)) return sent.get(i);
            }
            return new TLMsg();
        }

        /**
         * trace 轮转自测：设 traceDir/上限后连写 n 次。
         * 放类内是因为 traceDir/traceFileMaxBytes/traceLlmCall 是 TLLlmProvider 的 protected 成员，
         * 跨包只能经由子类自身访问。
         */
        void writeTracesForRotation(String dir, long capBytes, int n, String body) {
            debugMode = true;
            traceDir = dir;
            traceFileMaxBytes = capBytes;
            for (int i = 0; i < n; i++) {
                traceLlmCall("sess_rot", "selfTest", body, body, 200, "m", 1L, "r1");
            }
        }
    }

    // ======================== JSON/SSE 构造 ========================

    private static String json(String content, String reasoning, String toolCalls, String finish) {
        return "{\"id\":\"t1\",\"choices\":[{\"index\":0,\"finish_reason\":\"" + finish + "\",\"message\":{"
                + "\"role\":\"assistant\",\"content\":\"" + content + "\""
                + (reasoning == null ? "" : ",\"reasoning_content\":\"" + reasoning + "\"")
                + (toolCalls == null ? "" : ",\"tool_calls\":" + toolCalls)
                + "}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    private static String toolCalls() {
        return "[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]";
    }

    private static String sse(String... chunks) {
        StringBuilder sb = new StringBuilder();
        for (String c : chunks) sb.append("data: ").append(c).append("\n\n");
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }

    private static String deltaReasoning(String text) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"" + text + "\"}}]}";
    }

    private static String deltaToolCall(String id, String fn, String args) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + fn + "\",\"arguments\":\"" + args + "\"}}]}}]}";
    }

    private static String finishChunk(String finish) {
        return "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}";
    }
}
