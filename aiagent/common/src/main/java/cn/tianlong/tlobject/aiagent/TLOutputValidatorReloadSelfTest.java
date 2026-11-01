package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TLOutputValidator 运行期规则重载自测（runnable main）。
 * 覆盖：成功替换、坏 XML 保留旧规则、缺失配置文件保留旧规则、空 rules、参数复位、并发校验。
 */
public class TLOutputValidatorReloadSelfTest implements TLAiAgentParamString {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        String file = writeTemp("ruleA", config("<rule tool=\"calculate_price\" checks=\"total&lt;100\"/>"));
        TestValidator v = new TestValidator("ovReloadTest", file);

        TLMsg r1 = v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("合法规则重载成功", r1 != null && r1.parseBoolean("result", false));
        check("规则数为 1", v.rules.size() == 1);
        check("新规则生效（200 被拒）", !valid(v, 200.0));

        writeFile(file, "<moduleConfig><rules><rule tool=\"calculate_price\"");
        TLMsg r2 = v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("坏 XML 重载失败", r2 != null && !r2.parseBoolean("result", true));
        check("坏 XML 后旧规则仍生效", !valid(v, 200.0));

        writeFile(file, config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        TLMsg r3 = v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("新规则重载成功", r3 != null && r3.parseBoolean("result", false));
        check("新规则生效（200 通过）", valid(v, 200.0));

        // 缺失配置文件：预检失败，必须保留旧规则（防 fail-open）
        File temp = new File(file);
        temp.delete();
        TLMsg missR = v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("缺失配置文件重载失败", missR != null && !missR.parseBoolean("result", true));
        check("缺失配置文件后旧规则仍生效", valid(v, 200.0));

        writeFile(file, "<moduleConfig><rules/></moduleConfig>");
        TLMsg r4 = v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("空规则重载成功", r4 != null && r4.parseBoolean("result", false));
        check("空规则条数为 0", "0".equals(String.valueOf(r4.getParam("rules"))));
        check("空规则全部放行", valid(v, 200.0));

        writeFile(file, "<moduleConfig><params><failClosedOnError value=\"true\"/></params>"
                + "<rules><rule tool=\"calculate_price\" checks=\"total:number\"/></rules></moduleConfig>");
        v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("failClosedOnError 读取为 true", v.failClosedOnError);

        writeFile(file, config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("删除配置后 failClosedOnError 复位 false", !v.failClosedOnError);
        check("合并 params 已移除 failClosedOnError", !v.hasMergedParam("failClosedOnError"));
        v.externalSetParam("harmlessKey", "x");
        check("外部 setParam 不复活已删除的 failClosedOnError", !v.failClosedOnError);

        // 13) configure 成功后 reConfig 抛异常：不得换入新规则
        String throwFile = writeTemp("throwReload", config("<rule tool=\"calculate_price\" checks=\"total&lt;100\"/>"));
        ThrowingReloadValidator tv = new ThrowingReloadValidator("ovThrowReload", throwFile);
        tv.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        tv.failOnReConfig = true;
        writeFile(throwFile, config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        TLMsg tr = tv.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("reConfig 异常重载失败", tr != null && !tr.parseBoolean("result", true));
        check("reConfig 异常保留旧规则", !valid(tv, 200.0));
        tv.failOnReConfig = false;
        writeFile(throwFile, config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        TLMsg tr2 = tv.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("恢复后重载成功", tr2 != null && tr2.parseBoolean("result", false));
        check("恢复后新规则生效", valid(tv, 200.0));

        // 15) 快照语义：重载换引用、旧快照不变、新快照不可变
        String snapFile = writeTemp("snapshot", config("<rule tool=\"calculate_price\" checks=\"total&lt;100\"/>"));
        TestValidator sv = new TestValidator("ovSnapshot", snapFile);
        sv.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        List<TLOutputValidator.Rule> before = sv.rules;
        writeFile(snapFile, config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        TLMsg snapR = sv.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("快照重载成功", snapR != null && snapR.parseBoolean("result", false));
        check("重载后快照引用已替换", before != sv.rules);
        check("旧快照未被原地修改", before.size() == 1 && before.get(0).checks.contains("total<100"));
        boolean immutable = false;
        try { sv.rules.add(new TLOutputValidator.Rule()); }
        catch (UnsupportedOperationException e) { immutable = true; }
        check("新快照不可变", immutable);

        // 16) 无配置文件：失败且保留旧规则
        TestValidator noCfg = new TestValidator("ovNoCfg", null);
        noCfg.rules.add(new TLOutputValidator.Rule());
        TLMsg noCfgR = noCfg.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("无配置文件重载失败", noCfgR != null && !noCfgR.parseBoolean("result", true));
        check("无配置文件保留旧规则", noCfg.rules.size() == 1);

        // 17) 外部 failClosedOnError（start params）：未被配置文件清理
        String extFile = writeTemp("extFailClosed", config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        HashMap<String, String> startParams = new HashMap<>();
        startParams.put("failClosedOnError", "true");
        TestValidator ext = new TestValidator("ovExtFailClosed", extFile);
        ext.start(null, startParams);
        check("外部 failClosedOnError 生效", ext.failClosedOnError);
        check("外部 failClosedOnError 未被清理", ext.hasMergedParam("failClosedOnError"));

        // 17b) 外部值与配置文件同时提供 failClosedOnError；配置删除后回退到外部值
        String bothFile = writeTemp("bothFailClosed",
                "<moduleConfig><params><failClosedOnError value=\"true\"/></params>"
                + "<rules><rule tool=\"calculate_price\" checks=\"total:number\"/></rules></moduleConfig>");
        HashMap<String, String> bothStart = new HashMap<>();
        bothStart.put("failClosedOnError", "true");
        TestValidator both = new TestValidator("ovBothFailClosed", bothFile);
        both.start(null, bothStart);
        check("外部+配置 failClosed 初始 true", both.failClosedOnError);
        writeFile(bothFile, config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        TLMsg bothR = both.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("删除配置键后回退外部 failClosed", bothR != null && bothR.parseBoolean("result", false)
                && both.failClosedOnError);

        // 18) 失败重载恢复 failClosedOnError 与旧规则
        String fcFile = writeTemp("failClosedRestore",
                "<moduleConfig><params><failClosedOnError value=\"true\"/></params>"
                + "<rules><rule tool=\"calculate_price\" checks=\"total&lt;100\"/></rules></moduleConfig>");
        ThrowingReloadValidator fcv = new ThrowingReloadValidator("ovFailClosedRestore", fcFile);
        fcv.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("初始 failClosedOnError=true", fcv.failClosedOnError);
        fcv.failOnReConfig = true;
        writeFile(fcFile, config("<rule tool=\"calculate_price\" checks=\"total:number\"/>"));
        TLMsg fcR = fcv.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("失败重载返回失败", fcR != null && !fcR.parseBoolean("result", true));
        check("失败重载保持 failClosedOnError", fcv.failClosedOnError);
        check("失败重载保留旧规则", !valid(fcv, 200.0));

        String a = config("<rule tool=\"calculate_price\" checks=\"total&lt;100\"/>");
        String b = config("<rule tool=\"calculate_price\" checks=\"total:number\"/>");
        writeFile(file, a);
        v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 500; n++)
                        v.matchRule("calculate_price", "priceCalc", "testAgent");
                } catch (Throwable ex) {
                    error.compareAndSet(null, ex);
                }
            });
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        start.countDown();
        for (int i = 0; i < 30; i++) {
            writeFile(file, i % 2 == 0 ? b : a);
            v.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        }
        for (Thread t : threads) t.join(5000);
        boolean allDone = true;
        for (Thread t : threads) if (t.isAlive()) allDone = false;
        check("并发线程全部结束", allDone);
        check("并发校验+重载无异常", error.get() == null);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static class TestValidator extends TLOutputValidator {
        TestValidator(String name, String configFile) { super(name, configFile); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
        @Override public void putLog(Exception e, LogLevel logLevel, String tag) { }

        boolean hasMergedParam(String key) { return params != null && params.containsKey(key); }

        void externalSetParam(String key, String value) {
            if (params == null) params = new java.util.HashMap<>();
            params.put(key, value);
            setModuleParams();
        }
    }

    static class ThrowingReloadValidator extends TestValidator {
        boolean failOnReConfig;
        ThrowingReloadValidator(String name, String configFile) { super(name, configFile); }
        @Override protected void reConfig() {
            if (failOnReConfig) throw new RuntimeException("reConfig boom");
        }
    }

    private static boolean valid(TestValidator v, double total) {
        return v.validateRequest(request("calculate_price", total)).parseBoolean("valid", false);
    }

    private static TLMsg request(String functionName, double total) {
        TLMsg result = new TLMsg().setParam("total", total)
                .setParam("quantity", 2).setParam("unitPrice", total / 2);
        return new TLMsg().setAction(OUTPUT_VALIDATE_ACTION)
                .setParam("functionName", functionName)
                .setParam("moduleName", "priceCalc")
                .setParam("agentName", "testAgent")
                .setParam("result", result);
    }

    private static String config(String rules) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<moduleConfig><rules>" + rules + "</rules></moduleConfig>\n";
    }

    private static String writeTemp(String prefix, String content) throws Exception {
        File f = File.createTempFile(prefix, ".xml");
        f.deleteOnExit();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f.getAbsolutePath();
    }

    private static void writeFile(String path, String content) throws Exception {
        Files.write(new File(path).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
