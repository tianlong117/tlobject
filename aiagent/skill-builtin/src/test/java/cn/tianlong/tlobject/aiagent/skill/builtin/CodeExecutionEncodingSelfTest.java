package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * code_execution 输出编码自测（runnable main，无 JUnit）。
 *
 * <p>背景（2026-10-31 真机）：Windows 下 Python 管道 stdout 默认 GBK(cp936)，而技能按
 * JVM 默认 UTF-8 解码，脚本打印的中文全变成 U+FFFD。模型看到的工具结果是乱码，
 * 误以为执行失败而反复重跑（codeAgent 烧满 10 轮迭代）。
 *
 * <p>覆盖：① 脚本源码内的中文 + stdout 中文；② GBK 文章按 encoding='gbk' 读取后
 * 经 UTF-8 stdout 回传——证明修复只改 stdio，不会把 GBK 文件转坏。
 *
 * <p>运行：java -cp "skill-builtin/target/classes;common/target/classes;core/target/classes;
 * ...依赖..." cn.tianlong.tlobject.aiagent.skill.builtin.CodeExecutionEncodingSelfTest
 * 失败以退出码 1 结束。
 */
public class CodeExecutionEncodingSelfTest implements TLAiAgentParamString {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        // ① 脚本源码内中文 + print 中文
        String out1 = runPython("print('中文输出测试 ABC')");
        check("脚本中文 + stdout 中文（无 U+FFFD）",
                out1.contains("中文输出测试 ABC") && !out1.contains("�"), out1);

        // ② GBK 文章：脚本按 gbk 读文件，stdout 由技能固定 UTF-8——内容不能被转坏
        Path gbkFile = Files.createTempFile("tl_ce_gbk_", ".txt");
        try {
            Files.write(gbkFile, "中文文章内容：第一段。".getBytes("GBK"));
            String path = gbkFile.toString().replace('\\', '/');
            String out2 = runPython("print('读取:', open('" + path + "', 'r', encoding='gbk').read())");
            check("GBK 文章按 gbk 读取（无 U+FFFD）",
                    out2.contains("中文文章内容") && !out2.contains("�"), out2);
        } finally {
            Files.deleteIfExists(gbkFile);
        }

        System.out.println("========================================");
        System.out.println("CodeExecutionEncodingSelfTest: passed=" + passed + ", failed=" + failed);
        System.out.println("RESULT: " + (failed == 0 ? "PASS" : "FAIL"));
        if (failed > 0) System.exit(1);
    }

    private static String runPython(String code) {
        TestSkill skill = new TestSkill();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("language", "python");
        input.put("code", code);
        TLMsg r = skill.execute(null, skill.createMsg().setParam(AI_P_SKILLINPUT, input));
        String out = r == null ? "" : r.getStringParam(AI_P_SKILLOUTPUT, "");
        System.out.println("[output] " + out.replace("\r", "\\r").replace("\n", "\\n"));
        return out;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + " -> " + detail);
        }
    }

    /** 屏蔽日志依赖：技能未走工厂初始化，putLog 访问 log 配置会 NPE */
    static class TestSkill extends TLCodeExecutionSkill {
        TestSkill() { super("codeExecEncodingSelfTest"); }
        @Override public void putLog(String content, LogLevel logLevel) { /* 自测静默 */ }
    }
}
