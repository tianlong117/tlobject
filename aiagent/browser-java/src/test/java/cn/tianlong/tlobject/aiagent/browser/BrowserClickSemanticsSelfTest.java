package cn.tianlong.tlobject.aiagent.browser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 浏览器点击语义自测（headless 本机 file:// 页面，无网络）。
 *
 * <p>守 2026-11-14 事故契约：
 * ① 普通链接点击必须 pageChanged=true 并导航；
 * ② target=_blank 必须接管新标签（tabOpened=true），不能停在旧页面还回 ok:true；
 * ③ 无变化点击连续 3 次必须硬失败（旧实现每次都 ok:true → 模型连点 8 次死链）。
 *
 * <p>直接运行（需本机已安装 Playwright 浏览器）：
 * <pre>
 *   mvn -q -pl aiagent/browser-java -am test-compile
 *   java -Dfile.encoding=UTF-8 -cp "D:/.../aiagent/browser-java/target/test-classes;D:/.../target/classes;&lt;deps&gt;" \
 *        cn.tianlong.tlobject.aiagent.browser.BrowserClickSemanticsSelfTest
 * </pre>
 * ⚠ classpath 必须用绝对路径：相对路径会让 Playwright DriverJar 只解出 node.exe、
 * 漏掉 driver/package（表现为 node 找不到 package/cli.js）。
 */
public class BrowserClickSemanticsSelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("tl-browser-click-test");
        Path a = dir.resolve("a.html");
        Path b = dir.resolve("b.html");
        Files.write(b, "<html><body><h1>page b</h1></body></html>".getBytes(StandardCharsets.UTF_8));
        Files.write(a, ("<html><body>"
                + "<a id=\"normal\" href=\"b.html\">go</a>"
                + "<a id=\"blank\" href=\"b.html\" target=\"_blank\">blank</a>"
                + "<span id=\"dead\">dead</span>"
                + "</body></html>").getBytes(StandardCharsets.UTF_8));
        String urlA = a.toUri().toString();
        String urlB = b.toUri().toString();

        JavaBrowserEngine.Config cfg = new JavaBrowserEngine.Config();
        cfg.headless = true;                 // ephemeral 形态（userDataDir/cdp 都为空）
        cfg.maxTextChars = 2000;
        JavaBrowserEngine engine = new JavaBrowserEngine(cfg);
        try {
            Map<String, Object> navA = engine.execute(
                    input("action", "navigate", "url", urlA), 60000);
            check("navigate 到 A", Boolean.TRUE.equals(navA.get("ok")));
            if (!Boolean.TRUE.equals(navA.get("ok"))) System.out.println("navigate result: " + navA);

            Map<String, Object> normal = engine.execute(
                    input("action", "click", "selector", "#normal"), 60000);
            check("普通链接 pageChanged=true", Boolean.TRUE.equals(normal.get("pageChanged")));
            check("普通链接真的导航到 B", String.valueOf(normal.get("url")).endsWith("b.html"));

            engine.execute(input("action", "navigate", "url", urlA), 60000);
            Map<String, Object> blank = engine.execute(
                    input("action", "click", "selector", "#blank"), 60000);
            check("target=_blank tabOpened=true", Boolean.TRUE.equals(blank.get("tabOpened")));
            check("target=_blank 接管新标签并读 B", String.valueOf(blank.get("url")).endsWith("b.html"));
            check("target=_blank pageChanged=true", Boolean.TRUE.equals(blank.get("pageChanged")));

            engine.execute(input("action", "navigate", "url", urlA), 60000);
            Map<String, Object> dead1 = engine.execute(
                    input("action", "click", "selector", "#dead"), 60000);
            Map<String, Object> dead2 = engine.execute(
                    input("action", "click", "selector", "#dead"), 60000);
            Map<String, Object> dead3 = engine.execute(
                    input("action", "click", "selector", "#dead"), 60000);
            check("无变化点击 1：ok=true + pageChanged=false",
                    Boolean.TRUE.equals(dead1.get("ok")) && Boolean.FALSE.equals(dead1.get("pageChanged")));
            check("无变化点击 1：带 warning", dead1.get("warning") != null);
            check("无变化点击 2：仍 ok=true", Boolean.TRUE.equals(dead2.get("ok")));
            check("无变化点击 3：硬失败 ok=false",
                    Boolean.FALSE.equals(dead3.get("ok"))
                            && String.valueOf(dead3.get("error")).contains("repeated click"));
        } finally {
            engine.shutdown();
            try {
                Files.deleteIfExists(a);
                Files.deleteIfExists(b);
                Files.deleteIfExists(dir);
            } catch (Exception ignored) { }
        }
        System.out.println("\n[BrowserClickSemanticsSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static Map<String, Object> input(String... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
