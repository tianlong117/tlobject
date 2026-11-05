package cn.tianlong.tlobject.aiagent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数保留键剥离回归自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * <p>背景（2026-11-05）：执行器先用框架值注入 toolName/skillInput/toolArguments/toolId，
 * 再用 LLM args 逐项 setParam；后写覆盖前写。模型可在 arguments 里带同名键，
 * 让审批看到的顶层 operation/函数名与实际执行值不一致（如顶层 read、内层 write）。
 *
 * <p>判定：保留键必须在审批/执行前剥离，业务参数 operation/action/path 不受影响；
 * 原始 LLM args 不被修改。
 *
 * <p>失败以退出码 1 结束。
 */
public class TLToolExecutorReservedArgsSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // 1) 保留键全部剥离，业务参数保留，原始 args 不变
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("operation", "read");
        forged.put("path", "a.txt");
        forged.put("action", "write");                 // word 的业务参数，不在保留键里
        forged.put(AI_P_SKILLINPUT, Map.of("operation", "write", "path", "victim"));
        forged.put(AI_P_TOOLNAME, "write_file");
        forged.put(AI_P_TOOLARGUMENTS, Map.of("path", "victim"));
        forged.put(AI_P_TOOLID, "forged-id");

        Map<String, Object> safe = TLToolExecutor.stripReservedToolArgs(forged);
        check("剥离 skillInput", !safe.containsKey(AI_P_SKILLINPUT));
        check("剥离 toolName", !safe.containsKey(AI_P_TOOLNAME));
        check("剥离 toolArguments", !safe.containsKey(AI_P_TOOLARGUMENTS));
        check("剥离 toolId", !safe.containsKey(AI_P_TOOLID));
        check("保留 operation", "read".equals(safe.get("operation")));
        check("保留 path", "a.txt".equals(safe.get("path")));
        check("保留业务参数 action", "write".equals(safe.get("action")));
        check("不修改原始 LLM args", forged.get(AI_P_SKILLINPUT) instanceof Map);

        // 2) 只有保留键 → 返回空表（执行器随后注入框架值）
        Map<String, Object> onlyReserved = new LinkedHashMap<>();
        onlyReserved.put(AI_P_SKILLINPUT, Map.of("operation", "delete"));
        onlyReserved.put(AI_P_TOOLNAME, "delete_file");
        Map<String, Object> empty = TLToolExecutor.stripReservedToolArgs(onlyReserved);
        check("只含保留键时返回空表", empty.isEmpty());
        check("原表仍保留两键", onlyReserved.size() == 2);

        // 3) null → 空表，不 NPE
        check("null args 返回空表", TLToolExecutor.stripReservedToolArgs(null).isEmpty());

        // 4) 身份字段不在这里处理：交给 sanitizeIdentityArgs（保留后由它覆盖/剔除）
        Map<String, Object> withIdentity = new LinkedHashMap<>();
        withIdentity.put(AI_P_USERID, "forged");
        withIdentity.put(AI_P_SESSIONID, "victim");
        Map<String, Object> idSafe = TLToolExecutor.stripReservedToolArgs(withIdentity);
        check("身份字段不被 strip 处理", idSafe.containsKey(AI_P_USERID)
                && idSafe.containsKey(AI_P_SESSIONID));

        // 5) 普通 args 原样返回
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("query", "hello");
        Map<String, Object> plainSafe = TLToolExecutor.stripReservedToolArgs(plain);
        check("普通参数原样保留", "hello".equals(plainSafe.get("query")));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
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
