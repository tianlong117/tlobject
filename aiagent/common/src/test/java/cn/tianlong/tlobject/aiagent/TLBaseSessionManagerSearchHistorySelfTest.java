package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SessionManager 只读历史检索/读取自测（runnable main，无 JUnit——仓库惯例）。
 *
 * <p>覆盖：按关键词检索（默认只看 assistant）、长回答优先排序、条数/片段上限、
 * 当前轮排除、用户归属校验、无命中；read 默认最后一条 assistant、messageIndex、
 * 超长截断、轮次不存在。</p>
 */
public class TLBaseSessionManagerSearchHistorySelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    static class FakeSessionManager extends TLBaseSessionManager {
        final List<Map<String, Object>> rounds;
        FakeSessionManager(String name, List<Map<String, Object>> rounds) {
            super(name);
            this.rounds = rounds;
        }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }
        @Override protected void storeRound(Map<String, Object> roundData) { }
        @Override protected List<Map<String, Object>> loadRoundData(String sessionId, String userId) {
            return rounds;
        }
        @Override protected Map<String, Object> findLatestMeta(String userId) { return null; }
        @Override protected Map<String, Object> findIncompleteMeta(String userId, String sessionId) {
            return null;
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

    public static void main(String[] args) {
        searchFindsOriginalAnswerFirst();
        searchLimitAndSnippet();
        searchRoleAndExclude();
        searchUserIsolationAndNoMatch();
        readDefaultAndIndexed();
        readTruncationAndErrors();

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static void searchFindsOriginalAnswerFirst() {
        FakeSessionManager sm = fake("s1", "u1");
        TLMsg resp = search(sm, "s1", "u1", "可能的爱", 5, "assistant", "");
        List<Map<String, Object>> results = results(resp);

        check("检索成功", resp.parseBoolean(RESULT, false));
        check("命中原始答案与过程轮", resp.getIntParam("totalMatches", 0) >= 2);
        check("长 assistant 回答排第一",
                results.size() >= 1 && "r1".equals(results.get(0).get("roundId"))
                        && "assistant".equals(results.get(0).get("role")));
        check("片段含关键词",
                results.size() >= 1 && String.valueOf(results.get(0).get("snippet")).contains("可能的爱"));
    }

    private static void searchLimitAndSnippet() {
        FakeSessionManager sm = fake("s1", "u1");
        TLMsg resp = search(sm, "s1", "u1", "可能的", 1, "assistant", "");
        List<Map<String, Object>> results = results(resp);
        check("limit=1 只回一条", results.size() == 1);
        check("totalMatches 统计全部命中", resp.getIntParam("totalMatches", 0) > 1);
        String snippet = String.valueOf(results.get(0).get("snippet"));
        check("片段按 maxSnippetChars 截断", snippet.length() <= 400 + 4);
    }

    private static void searchRoleAndExclude() {
        FakeSessionManager sm = fake("s1", "u1");
        TLMsg userHit = search(sm, "s1", "u1", "可能的爱", 5, "user", "");
        List<Map<String, Object>> ur = results(userHit);
        check("role=user 只回用户消息",
                !ur.isEmpty() && "user".equals(ur.get(0).get("role")));

        TLMsg excluded = search(sm, "s1", "u1", "可能的爱", 5, "assistant", "r2");
        List<Map<String, Object>> er = results(excluded);
        boolean hasR2 = false;
        for (Map<String, Object> r : er) if ("r2".equals(r.get("roundId"))) hasR2 = true;
        check("excludeRoundId 排除当前轮", !hasR2);
    }

    private static void searchUserIsolationAndNoMatch() {
        FakeSessionManager sm = fake("s1", "u1");
        TLMsg other = search(sm, "s1", "u2", "可能的爱", 5, "assistant", "");
        check("他用户看不到本会话轮次", results(other).isEmpty());
        check("他用户查询 totalMatches=0", other.getIntParam("totalMatches", 0) == 0);

        TLMsg none = search(sm, "s1", "u1", "不存在的关键词", 5, "assistant", "");
        check("无命中 RESULT=true", none.parseBoolean(RESULT, false));
        check("无命中结果为空", results(none).isEmpty());
    }

    private static void readDefaultAndIndexed() {
        FakeSessionManager sm = fake("s1", "u1");
        TLMsg read = read(sm, "s1", "u1", "r1", -1, -1, 20000);
        check("read 成功", read.parseBoolean(RESULT, false));
        check("默认取最后一条 assistant", "assistant".equals(read.getStringParam("role", "")));
        check("默认取到原始长回答", read.getStringParam("content", "").contains("李沧东"));
        check("roundSeq 回传", read.getIntParam("roundSeq", -1) == 1);

        TLMsg user = read(sm, "s1", "u1", "", 1, 0, 20000);
        check("按 roundSeq+messageIndex 读用户消息",
                "user".equals(user.getStringParam("role", ""))
                        && user.getStringParam("content", "").contains("可能的爱"));
    }

    private static void readTruncationAndErrors() {
        FakeSessionManager sm = fake("s1", "u1");
        TLMsg shortRead = read(sm, "s1", "u1", "r1", -1, -1, 30);
        check("超长内容标记截断", shortRead.parseBoolean("truncated", false));
        check("截断结果带说明",
                shortRead.getStringParam("content", "").contains("已截断"));

        TLMsg missing = read(sm, "s1", "u1", "nope", -1, -1, 2000);
        check("轮次不存在报错", !missing.parseBoolean(RESULT, true));

        TLMsg badIndex = read(sm, "s1", "u1", "r1", -1, 99, 2000);
        check("messageIndex 越界报错", !badIndex.parseBoolean(RESULT, true));
    }

    // ======================== 数据与辅助 ========================

    private static FakeSessionManager fake(String sid, String uid) {
        List<Map<String, Object>> rounds = new ArrayList<>();
        rounds.add(round(sid, uid, 1, "r1", "《可能的爱》最近挺火，主要评价是什么",
                msg(TLConversationHistory.Role.user, "《可能的爱》最近挺火，主要评价是什么"),
                msg(TLConversationHistory.Role.assistant,
                        "《可能的爱》影评汇总：李沧东导演，豆瓣 8.7。"
                                + "以下是很长的原始分析正文。".repeat(80))));
        rounds.add(round(sid, uid, 2, "r2", "再来两首爱情诗",
                msg(TLConversationHistory.Role.user, "再来两首爱情诗"),
                msg(TLConversationHistory.Role.assistant,
                        "诗写好了。顺带提到可能的爱这个说法，但这是过程轮，不是原始影评。")));
        return new FakeSessionManager("fakeSessionManager", rounds);
    }

    private static Map<String, Object> round(String sid, String uid, int seq, String rid,
                                             String userMessage,
                                             TLConversationHistory... msgs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sessionId", sid);
        r.put("userId", uid);
        r.put("roundSeq", seq);
        r.put("roundId", rid);
        r.put("userMessage", userMessage);
        r.put("createdAt", 1794634842000L + seq * 1000L);
        r.put("messages", new ArrayList<>(List.of(msgs)));
        return r;
    }

    private static TLConversationHistory msg(TLConversationHistory.Role role, String content) {
        return new TLConversationHistory(role, content);
    }

    private static TLMsg search(FakeSessionManager sm, String sid, String uid, String query,
                                int limit, String role, String exclude) {
        return sm.checkMsgAction(null, sm.createMsg().setAction(SESSION_SEARCHHISTORY)
                .setParam("sessionId", sid)
                .setParam("userId", uid)
                .setParam("query", query)
                .setParam("limit", limit)
                .setParam("maxSnippetChars", 400)
                .setParam("role", role)
                .setParam("excludeRoundId", exclude));
    }

    private static TLMsg read(FakeSessionManager sm, String sid, String uid, String rid,
                              int roundSeq, int messageIndex, int maxChars) {
        return sm.checkMsgAction(null, sm.createMsg().setAction(SESSION_READHISTORYROUND)
                .setParam("sessionId", sid)
                .setParam("userId", uid)
                .setParam("roundId", rid)
                .setParam("roundSeq", roundSeq)
                .setParam("messageIndex", messageIndex)
                .setParam("maxChars", maxChars));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(TLMsg resp) {
        Object v = resp.getParam("results");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
