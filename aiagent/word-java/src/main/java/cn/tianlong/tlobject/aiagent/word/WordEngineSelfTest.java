package cn.tianlong.tlobject.aiagent.word;

/**
 * word 引擎自测（runnable main，无 JUnit——与仓库自测惯例一致）。
 *
 * 运行：
 *   /d/maven/bin/mvn -q -pl aiagent/word-java dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   java -cp "aiagent/word-java/target/classes;$(cat aiagent/word-java/cp.txt)" \
 *        cn.tianlong.tlobject.aiagent.word.WordEngineSelfTest
 * 失败以退出码 1 结束。
 */
public class WordEngineSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        check("骨架可实例化", true);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("  [PASS] " + desc); }
        else    { failed++; System.out.println("  [FAIL] " + desc); }
    }
}
