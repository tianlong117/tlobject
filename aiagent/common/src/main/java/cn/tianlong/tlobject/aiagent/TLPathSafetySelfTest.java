package cn.tianlong.tlobject.aiagent;

import java.io.File;

/**
 * 路径片段净化自测（runnable main，无 JUnit——仓库惯例）。
 * 失败退出码 1。
 */
public class TLPathSafetySelfTest {

    private static int passed = 0, failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }

    private static String safe(String raw) {
        return TLPathSafety.safeSegment(raw);
    }

    public static void main(String[] args) {
        check("普通 ID 原样保留", "alice".equals(safe("alice")));
        check("中间点保留（john.doe）", "john.doe".equals(safe("john.doe")));
        check("正斜杠替换", "a_b".equals(safe("a/b")));
        check("反斜杠替换", "a_b".equals(safe("a\\b")));
        check("盘符/冒号替换", "C__x".equals(safe("C:\\x")));
        check("点号段回落 default", "default".equals(safe(".")));
        check("双点段回落 default", "default".equals(safe("..")));
        check("空串回落 default", "default".equals(safe("")));
        check("null 回落 default", "default".equals(safe(null)));
        check("尾部点替换（Windows 会吞）", "user_".equals(safe("user.")));
        check("首尾空格 trim", "user".equals(safe("  user  ")));
        check("Windows 设备名加前缀", "_CON".equals(safe("CON")));
        check("设备名大小写不敏感", "_com1".equals(safe("com1")));
        String ctrl = "a" + (char) 1 + "b";
        check("控制字符替换", "a_b".equals(safe(ctrl)));
        check("自定义默认值", "unknown".equals(TLPathSafety.safeSegment("..", "unknown")));

        // 穿越语义验证：data/<safe("..")> 必须仍在 data 下
        File data = new File("data").getAbsoluteFile();
        File resolved = new File(data, safe("..")).getAbsoluteFile();
        check("'..' 净化后不会跳出 data", data.equals(resolved.getParentFile()));

        System.out.println("\n== " + passed + " passed, " + failed + " failed ==");
        System.exit(failed == 0 ? 0 : 1);
    }
}
