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
 * <p>2026-11-16 扩展：普通 checkpoint（非待批，意外中断）也必须能丢弃——否则 web 登录弹框
 * 每次登录都重新提示且无服务端入口可清除。{@code discardOpenRound} 同时覆盖
 * pending_approval 与 checkpoint（待批优先，黏住语义不变）；旧名 discardPendingRound 保留为别名。
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
        check("失败原因指向目标轮", d2.getStringParam("error", "").contains("目标断点轮"));

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

        // 5) 物理最后一行不是最大 roundSeq → stateRound 必须取排序后的最后一条
        //    （DB 后端 ORDER BY round_seq 在并列/物理序不可靠时会踩到）
        FakeSessionManager order = new FakeSessionManager();
        order.addRound("s5", "r2", 2, SESSION_STATE_COMPLETED);
        order.addRound("s5", "r1", 1, SESSION_STATE_COMPLETED);
        TLMsg asm = order.assemble("s5", "u5");
        check("stateRound 取最大 roundSeq 而非物理末行",
                "r2".equals(asm.getStringParam("roundId", "")));

        // A) 单个普通 checkpoint 轮（非待批，意外中断）→ discardOpenRound 必须能丢弃
        FakeSessionManager cp = new FakeSessionManager();
        cp.addRound("sa", "r1", 1, SESSION_STATE_CHECKPOINT);
        TLMsg da = cp.discardOpen("sa", "ua");
        check("A 单 checkpoint 轮丢弃成功", da != null && da.parseBoolean(RESULT, false));
        check("A checkpoint 轮已变 completed", cp.latest("sa", "r1") != null
                && SESSION_STATE_COMPLETED.equals(
                        String.valueOf(cp.latest("sa", "r1").get("state"))));

        // B) checkpoint 轮 + 更晚的 completed 轮 → 丢弃目标是 checkpoint 轮，completed 轮不受影响
        FakeSessionManager mixed = new FakeSessionManager();
        mixed.addRound("sb", "r1", 1, SESSION_STATE_CHECKPOINT);
        mixed.addRound("sb", "r2", 2, SESSION_STATE_COMPLETED);
        TLMsg db = mixed.discardOpen("sb", "ub");
        check("B 混合轮次时丢弃成功", db != null && db.parseBoolean(RESULT, false));
        check("B checkpoint 轮已 completed", mixed.latest("sb", "r1") != null
                && SESSION_STATE_COMPLETED.equals(
                        String.valueOf(mixed.latest("sb", "r1").get("state"))));
        check("B 更晚的 completed 轮保持不变", mixed.latest("sb", "r2") != null
                && SESSION_STATE_COMPLETED.equals(
                        String.valueOf(mixed.latest("sb", "r2").get("state"))));

        // C) 只有 completed 轮 → 没有可丢弃的开态轮，必须失败
        FakeSessionManager done = new FakeSessionManager();
        done.addRound("sc", "r1", 1, SESSION_STATE_COMPLETED);
        TLMsg dc = done.discardOpen("sc", "uc");
        check("C 全 completed 时返回失败", dc != null && !dc.parseBoolean(RESULT, false));

        // D) 只有待批轮 + state=checkpoint → 状态不匹配，必须失败且待批保持开态
        //    （否则 API/控制台陈旧弹框会关闭待批轮却不撤销审批 → 审批 PENDING、会话永久卡住）
        FakeSessionManager stateD = new FakeSessionManager();
        stateD.addRound("sd", "r1", 1, SESSION_STATE_PENDING_APPROVAL);
        TLMsg dd = stateD.discardState("sd", "ud", SESSION_STATE_CHECKPOINT);
        check("D state=checkpoint 不丢弃待批轮", dd != null && !dd.parseBoolean(RESULT, false)
                && SESSION_STATE_PENDING_APPROVAL.equals(
                        String.valueOf(stateD.latest("sd", "r1").get("state"))));

        // E) 只有 checkpoint 轮 + state=pending_approval → 状态不匹配，必须失败且 checkpoint 保持开态
        //    （abandonApproval 在待批已被别处解决时不得误丢更新的普通断点）
        FakeSessionManager stateE = new FakeSessionManager();
        stateE.addRound("se", "r1", 1, SESSION_STATE_CHECKPOINT);
        TLMsg de = stateE.discardState("se", "ue", SESSION_STATE_PENDING_APPROVAL);
        check("E state=pending_approval 不丢弃 checkpoint 轮", de != null && !de.parseBoolean(RESULT, false)
                && SESSION_STATE_CHECKPOINT.equals(
                        String.valueOf(stateE.latest("se", "r1").get("state"))));

        // F) 待批 r1 + 更晚 checkpoint r2：state=checkpoint 只关 checkpoint，待批保持 pending
        FakeSessionManager stateF = new FakeSessionManager();
        stateF.addRound("sf", "r1", 1, SESSION_STATE_PENDING_APPROVAL);
        stateF.addRound("sf", "r2", 2, SESSION_STATE_CHECKPOINT);
        TLMsg df = stateF.discardState("sf", "uf", SESSION_STATE_CHECKPOINT);
        check("F state=checkpoint 关 checkpoint 且待批保持", df != null && df.parseBoolean(RESULT, false)
                && SESSION_STATE_COMPLETED.equals(
                        String.valueOf(stateF.latest("sf", "r2").get("state")))
                && SESSION_STATE_PENDING_APPROVAL.equals(
                        String.valueOf(stateF.latest("sf", "r1").get("state"))));

        // G) 同组合：state=pending_approval 只关待批，checkpoint 保持 checkpoint
        FakeSessionManager stateG = new FakeSessionManager();
        stateG.addRound("sg", "r1", 1, SESSION_STATE_PENDING_APPROVAL);
        stateG.addRound("sg", "r2", 2, SESSION_STATE_CHECKPOINT);
        TLMsg dg = stateG.discardState("sg", "ug", SESSION_STATE_PENDING_APPROVAL);
        check("G state=pending_approval 关待批且 checkpoint 保持", dg != null && dg.parseBoolean(RESULT, false)
                && SESSION_STATE_COMPLETED.equals(
                        String.valueOf(stateG.latest("sg", "r1").get("state")))
                && SESSION_STATE_CHECKPOINT.equals(
                        String.valueOf(stateG.latest("sg", "r2").get("state"))));

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
            // 旧名保留为别名（agentService 的 abandonApproval 路径仍可能用旧名）
            return checkMsgAction(null, createMsg().setAction("discardPendingRound")
                    .setParam("sessionId", sessionId).setParam("userId", userId));
        }

        TLMsg discardOpen(String sessionId, String userId) {
            return checkMsgAction(null, createMsg().setAction("discardOpenRound")
                    .setParam("sessionId", sessionId).setParam("userId", userId));
        }

        TLMsg discardState(String sessionId, String userId, String state) {
            TLMsg m = createMsg().setAction("discardOpenRound")
                    .setParam("sessionId", sessionId).setParam("userId", userId);
            if (state != null) m.setParam("state", state);
            return checkMsgAction(null, m);
        }

        TLMsg assemble(String sessionId, String userId) {
            return assembleSession(sessionId, userId);
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
        @Override protected boolean setSessionPinned(String sessionId, String userId, boolean pinned) {
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
