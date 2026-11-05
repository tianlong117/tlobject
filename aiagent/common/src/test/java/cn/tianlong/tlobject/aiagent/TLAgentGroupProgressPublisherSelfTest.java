package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 组进度发布器自测（runnable main，无 JUnit——仓库惯例）。
 * 判定：事件 schema 完整、不含产出、bus/会话缺失静默、终态只发一次。
 * 失败退出码 1。
 */
public class TLAgentGroupProgressPublisherSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        RecordingBus bus = new RecordingBus();
        TLMsg input = new TLMsg()
                .setSystemParam(AI_P_SESSIONID, "sid1")
                .setSystemParam("rootSessionId", "root1")
                .setSystemParam(AI_P_USERID, "u1")
                .setSystemParam(AI_P_ROUNDID, "r1");
        TLAgentGroupProgressPublisher p = new TLAgentGroupProgressPublisher(
                bus, input, "priceTeam", "app:priceTeam", "run1", 3);

        p.overall("RUNNING", "mode=parallel members=2", 0);
        p.member("hamburgerAgent", TLAgentGroupProgressPublisher.TYPE_MEMBER,
                "RUNNING", 1, 0, "");
        p.member("hamburgerAgent", TLAgentGroupProgressPublisher.TYPE_MEMBER,
                "DONE", 1, 1200, "");
        p.overall("DONE", "", 1500);
        p.ensureTerminal("INTERRUPTED", "late", 0);

        check("收到 4 条事件（ensureTerminal 被忽略）", bus.events.size() == 4);
        TLMsg first = bus.events.get(0);
        check("action=topic", WORKFLOW_PROGRESS_TOPIC.equals(first.getAction()));
        check("destination=topic", WORKFLOW_PROGRESS_TOPIC.equals(first.getDestination()));
        check("source=group", "group".equals(first.getStringParam("source", "")));
        check("workflowName 兼容组名", "priceTeam".equals(first.getStringParam("workflowName", "")));
        check("groupName", "priceTeam".equals(first.getStringParam("groupName", "")));
        check("整体行 _group", "_group".equals(first.getStringParam("nodeId", "")));
        check("整体 nodeType=GROUP", "GROUP".equals(first.getStringParam("nodeType", "")));
        check("totalNodes=3", first.getIntParam("totalNodes", 0) == 3);
        check("sessionId", "sid1".equals(first.getStringParam("sessionId", "")));
        check("rootSessionId", "root1".equals(first.getStringParam("rootSessionId", "")));
        check("userId", "u1".equals(first.getStringParam("userId", "")));
        check("roundId", "r1".equals(first.getStringParam("roundId", "")));
        check("RUNNING detail", first.getStringParam("detail", "").contains("mode=parallel"));
        check("成员行 nodeId", "hamburgerAgent".equals(bus.events.get(1).getStringParam("nodeId", "")));
        check("成员 nodeType=MEMBER", "MEMBER".equals(bus.events.get(1).getStringParam("nodeType", "")));
        check("attempt 透传", bus.events.get(1).getIntParam("attempt", 0) == 1);
        check("成员耗时透传", bus.events.get(2).getLongParam("durationMs", 0L) == 1200L);
        check("整体终态耗时", bus.events.get(3).getLongParam("durationMs", 0L) == 1500L);
        check("事件不含成员产出",
                !bus.events.get(3).containsParam("output")
                        && !bus.events.get(3).containsParam(AI_P_SKILLOUTPUT));

        // bus=null：不抛异常、无事件
        TLAgentGroupProgressPublisher nullBus = new TLAgentGroupProgressPublisher(
                null, input, "g", "g", "r", 1);
        nullBus.overall("FAILED", "x", 0);
        check("null bus 静默", true);

        // 无会话 id：不发布
        RecordingBus noSidBus = new RecordingBus();
        new TLAgentGroupProgressPublisher(noSidBus, new TLMsg(), "g", "g", "r", 1)
                .overall("RUNNING", "", 0);
        check("无会话 id 静默", noSidBus.events.isEmpty());

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static class RecordingBus implements IObject {
        final List<TLMsg> events = new ArrayList<>();
        public String getName() { return "recordingBus"; }
        public TLMsg putMsg(IObject toWho, TLMsg msg) { events.add(msg); return null; }
        public TLMsg getMsg(Object fromWho, TLMsg msg) { return null; }
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
