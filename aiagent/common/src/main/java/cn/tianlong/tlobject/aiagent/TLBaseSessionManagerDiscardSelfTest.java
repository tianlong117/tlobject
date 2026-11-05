package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 丢弃待批断点回归自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>背景（2026-11-05）：写后校验原来调 findIncompleteMeta，它会返回该会话任意一个
 * 开态待批轮。同会话若还有另一个 pending 轮，本次丢弃已成功也会误报“未落盘”；
 * 用户按提示重试会把第二个待批轮也丢弃。修复后只校验目标 roundId 本身。
 *
 * <p>失败以退出码 1 结束。
 */
public class TLBaseSessionManagerDiscardSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // 1) 两个开态待批轮：丢弃最新的一个 → 必须成功，另一个保持 pending
        FakeSessionManager sm = new FakeSessionManager();
        sm.addRound("s1", "r1", 1, SESSION_STATE_PENDING_APPROVAL);
        sm.addRound("s1", "r2", 2, SESSION_STATE_PENDING_APPROVAL);

        TLMsg d1 = sm.discard("s1", "u1");
        check("两个待批轮时丢弃最新轮成功", d1.parseBoolean(RESULT, false));
        Map<String, Object> r2 = sm.latest("s1", "r2");
        Map<String, Object> r1 = sm.latest("s1", "r1");
        check("目标轮已变 completed", r2 != null
                && SESSION_STATE_COMPLETED.equals(String.valueOf(r2.get("state"))));
        check("另一个待批轮保持 pending", r1 != null
                && SESSION_STATE_PENDING_APPROVAL.equals(String.valueOf(r1.get("state"))));

        // 2) storeRound 失败（模拟静默吞异常）→ 必须如实报失败
        FakeSessionManager fail = new FakeSessionManager();
        fail.addRound("s2", "r1", 1, SESSION_STATE_PENDING_APPROVAL);
        fail.dropWrites = true;
        TLMsg d2 = fail.discard("s2", "u2");
        check("storeRound 未落盘时返回失败", !d2.parseBoolean(RESULT, false));
        check("失败原因指向目标轮", d2.getStringParam("error", "").contains("目标待批轮"));

        // 3) 单待批轮正常丢弃
        FakeSessionManager single = new FakeSessionManager();
        single.addRound("s3", "r1", 1, SESSION_STATE_PENDING_APPROVAL);
        TLMsg d3 = single.discard("s3", "u3");
        check("单待批轮丢弃成功", d3.parseBoolean(RESULT, false));
        check("单待批轮已 completed", SESSION_STATE_COMPLETED.equals(
                String.valueOf(single.latest("s3", "r1").get("state"))));

        // 4) 没有待批轮 → 失败
        FakeSessionManager none = new FakeSessionManager();
        none.addRound("s4", "r1", 1, SESSION_STATE_COMPLETED);
        TLMsg d4 = none.discard("s4", "u4");
        check("无待批轮时返回失败", !d4.parseBoolean(RESULT, false));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    /** 内存版 SessionManager：只实现本次测试依赖的轮次存储/读取。 */
    static class FakeSessionManager extends TLBaseSessionManager {
        private final List<Map<String, Object>> rounds = new ArrayList<>();
        boolean dropWrites = false;

        FakeSessionManager() {
            super("discardSelfTest");
            enableCheckpoint = true;
        }

        void addRound(String sessionId, String roundId, int roundSeq, String state) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sessionId", sessionId);
            r.put("roundId", roundId);
            r.put("roundSeq", roundSeq);
            r.put("state", state);
            r.put(AI_P_APPROVAL_ID, "appr-" + roundId);
            r.put("messages", new ArrayList<>());
            rounds.add(r);
        }

        Map<String, Object> latest(String sessionId, String roundId) {
            Map<String, Object> found = null;
            for (Map<String, Object> r : rounds) {
                if (!sessionId.equals(String.valueOf(r.get("sessionId")))) continue;
                if (roundId.equals(String.valueOf(r.get("roundId")))) found = r;
            }
            return found == null ? null : new LinkedHashMap<>(found);
        }

        TLMsg discard(String sessionId, String userId) {
            return checkMsgAction(null, createMsg().setAction("discardPendingRound")
                    .setParam("sessionId", sessionId).setParam("userId", userId));
        }

        @Override
        protected void storeRound(Map<String, Object> roundData) {
            if (dropWrites) return;
            rounds.add(new LinkedHashMap<>(roundData));   // 追加写，同 roundId 由 last-wins 收敛
        }

        @Override
        protected List<Map<String, Object>> loadRoundData(String sessionId, String userId) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : rounds) {
                if (!sessionId.equals(String.valueOf(r.get("sessionId")))) continue;
                out.add(new LinkedHashMap<>(r));   // 与真实后端一致：调用方拿到的是快照
            }
            return out;
        }

        @Override protected Map<String, Object> findLatestMeta(String userId) { return null; }
        @Override protected Map<String, Object> findIncompleteMeta(String userId, String sessionId) {
            return null;   // 修复后的 discard 不应依赖它
        }
        @Override protected void deleteSessionData(String sessionId, String userId) { }
        @Override protected List<Map<String, Object>> listSessionsMeta(String userId) {
            return new ArrayList<>();
        }
        @Override protected boolean setSessionTitle(String sessionId, String userId,
                                                    String title, boolean onlyIfEmpty) {
            return false;
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
