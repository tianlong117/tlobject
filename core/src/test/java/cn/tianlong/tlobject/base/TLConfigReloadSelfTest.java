package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 配置热加载基座自测（runnable main，无 JUnit——仓库惯例）。
 * 覆盖：TLModuleConfig.parseOk、基类 reloadConfig 的文件/XML 预检。
 */
public class TLConfigReloadSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        // 1) 合法 XML（含未知标签）：parseOk=true
        String good = writeTemp("good",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<moduleConfig><params><a value=\"1\"/></params>"
                + "<rules><rule tool=\"x\"/></rules></moduleConfig>\n");
        TLModuleConfig c1 = new TLModuleConfig(good, "");
        c1.parse(good);
        check("合法 XML parseOk=true", c1.isParseOk());

        // 2) 坏 XML：parse 仍返回非 null，但 parseOk=false
        // 注：kxml2 对"未闭合标签直到 EOF"不抛异常，这里用配对错误触发真实的解析异常
        String bad = writeTemp("bad", "<moduleConfig><rules><rule tool=\"x\"></rules></moduleConfig>");
        TLModuleConfig c2 = new TLModuleConfig(bad, "");
        TLModuleConfig parsedBad = c2.parse(bad);
        check("坏 XML parse 仍返回对象", parsedBad != null);
        check("坏 XML parseOk=false", !c2.isParseOk());
        check("坏 XML 有错误信息", c2.getParseError() != null && !c2.getParseError().isEmpty());

        // 2b) 截断 XML：kxml2 不抛异常（可能 parseOk 仍为 true），严格检查必须失败
        String truncated = writeTemp("truncated", "<moduleConfig><rules><rule tool=\"x\">");
        TLModuleConfig c4 = new TLModuleConfig(truncated, "");
        c4.parse(truncated);
        check("合法 XML 严格检查通过", c1.isWellFormed());
        check("截断 XML 严格检查失败", !c4.isWellFormed());
        check("配对错误 XML 严格检查失败", !c2.isWellFormed());

        // 3) 文件不存在：parse 返回 null
        TLModuleConfig c3 = new TLModuleConfig(good + ".missing", "");
        check("缺文件 parse 返回 null", c3.parse(good + ".missing") == null);

        // 2c) 空文件/仅声明/多根/根外文本：严格检查必须失败
        String empty = writeTemp("empty", "");
        String declOnly = writeTemp("declOnly", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        String multiRoot = writeTemp("multiRoot", "<moduleConfig/><other/>");
        String trailing = writeTemp("trailing", "<moduleConfig/>garbage");
        check("空文件严格检查失败", !new TLModuleConfig(empty, "").isWellFormed());
        check("仅声明文件严格检查失败", !new TLModuleConfig(declOnly, "").isWellFormed());
        check("多根文件严格检查失败", !new TLModuleConfig(multiRoot, "").isWellFormed());
        check("根外文本严格检查失败", !new TLModuleConfig(trailing, "").isWellFormed());

        // 3b) 缺文件：parseOk 也必须为 false，且 isWellFormed 不篡改 parse 状态
        TLModuleConfig c5 = new TLModuleConfig(good + ".missing2", "");
        check("缺文件 parseOk=false", c5.parse(good + ".missing2") == null && !c5.isParseOk()
                && c5.getParseError() != null && !c5.getParseError().isEmpty());
        check("缺文件严格检查失败", !c5.isWellFormed());
        boolean beforeOk = c2.isParseOk();
        String beforeError = c2.getParseError();
        c2.isWellFormed();
        check("严格检查不篡改 parse 状态", c2.isParseOk() == beforeOk
                && java.util.Objects.equals(c2.getParseError(), beforeError));

        // 3c) 同一实例失败后重新解析合法文件：状态复位
        c5.parse(good);
        check("重新解析后 parseOk 复位", c5.isParseOk());

        // 严格检查必须容忍 UTF-8 BOM（kxml2 会把它作为根前 TEXT 吐出）
        // 不能复用 good：它是临时文件的路径。另外 BOM 后不能跟 XML 声明——kxml2 会把
        // 声明当成非法 PI 直接抛异常（真实带 BOM 的配置如 EhCache_config.xml 也无声明）。
        String bomXml = "<moduleConfig><params><a value=\"1\"/></params></moduleConfig>";
        String bomFile = writeTempBytes("bom", bomBytes(bomXml));
        TLModuleConfig bomCfg = new TLModuleConfig(bomFile, "");
        bomCfg.parse(bomFile);
        check("BOM 文件 parseOk=true", bomCfg.isParseOk());
        check("BOM 文件严格检查通过", bomCfg.isWellFormed());

        // 4) 基类 reloadConfig 预检：坏 XML 不进入 configure
        ProbeModule badModule = new ProbeModule("probeBad", bad);
        TLMsg badR = badModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("坏 XML 重载失败", badR != null && !badR.parseBoolean("result", true)
                && badR.getStringParam("error", "").length() > 0);
        check("坏 XML 未执行 configure", badModule.configureCount == 0);

        // 5) 正常 XML：configure 执行，返回成功
        ProbeModule goodModule = new ProbeModule("probeGood", good);
        TLMsg goodR = goodModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("正常 XML 重载成功", goodR != null && goodR.parseBoolean("result", false));
        check("正常 XML 执行 configure", goodModule.configureCount == 1);

        // 6) 缺文件：失败且不 configure
        ProbeModule missModule = new ProbeModule("probeMiss", good + ".missing");
        TLMsg missR = missModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("缺文件重载失败", missR != null && !missR.parseBoolean("result", true)
                && missR.getStringParam("error", "").length() > 0);
        check("缺文件未执行 configure", missModule.configureCount == 0);

        // 7) 截断 XML（kxml2 不抛异常）：严格检查拦截，不进入 configure
        String trunc = writeTemp("truncReload", "<moduleConfig><rules><rule tool=\"x\">");
        ProbeModule truncModule = new ProbeModule("probeTrunc", trunc);
        TLMsg truncR = truncModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("截断 XML 重载失败", truncR != null && !truncR.parseBoolean("result", true)
                && truncR.getStringParam("error", "").length() > 0);
        check("截断 XML 未执行 configure", truncModule.configureCount == 0);

        // 8) include 配置：probe 必须带 factory，否则 include 解析 NPE
        String incDir = Files.createTempDirectory("cfgInclude").toFile().getAbsolutePath() + File.separator;
        File inc = new File(incDir + "inc.xml");
        Files.write(inc.toPath(), "<moduleConfig><params><b value=\"2\"/></params></moduleConfig>"
                .getBytes(StandardCharsets.UTF_8));
        String incMain = incDir + "main.xml";
        Files.write(new File(incMain).toPath(),
                "<moduleConfig><include file=\"inc.xml\"/></moduleConfig>".getBytes(StandardCharsets.UTF_8));
        ProbeModule incModule = new ProbeModule("probeInclude", incMain);
        incModule.setFactory(new TLObjectFactory("cfgTestFactory", incDir + "moduleFactory_config.xml", incDir));
        check("include 配置预检通过", incModule.probeConfigFile(incMain) == null);

        // 9) include 参数确实合入父配置
        TLModuleConfig incParsed = new TLModuleConfig(incMain, incDir);
        incParsed.setFactory(new TLObjectFactory("cfgMergeFactory", incDir + "moduleFactory_config.xml", incDir));
        incParsed.parse(incMain);
        check("include 参数已合入", incParsed.isParseOk()
                && "2".equals(incParsed.getParams().get("b")));

        // 10) 损坏 include：失败且不 configure
        String badIncDir = Files.createTempDirectory("cfgBadInclude").toFile().getAbsolutePath() + File.separator;
        Files.write(new File(badIncDir + "bad.xml").toPath(),
                "<moduleConfig><params>".getBytes(StandardCharsets.UTF_8));
        String badIncMain = badIncDir + "main.xml";
        Files.write(new File(badIncMain).toPath(),
                "<moduleConfig><include file=\"bad.xml\"/></moduleConfig>".getBytes(StandardCharsets.UTF_8));
        ProbeModule badIncModule = new ProbeModule("probeBadInclude", badIncMain);
        badIncModule.setFactory(new TLObjectFactory("cfgBadFactory", badIncDir + "moduleFactory_config.xml", badIncDir));
        TLMsg badIncR = badIncModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("损坏 include 重载失败", badIncR != null && !badIncR.parseBoolean("result", true));
        check("损坏 include 未执行 configure", badIncModule.configureCount == 0);
        check("损坏 include 有错误信息", badIncR != null
                && badIncR.getStringParam("error", "").length() > 0);

        // 11) 缺失 include：预检失败（使用独立临时目录，不破坏前面的 include 用例）
        String missIncDir = Files.createTempDirectory("cfgMissInclude").toFile().getAbsolutePath() + File.separator;
        String missIncMain = missIncDir + "main.xml";
        Files.write(new File(missIncMain).toPath(),
                "<moduleConfig><include file=\"absent.xml\"/></moduleConfig>".getBytes(StandardCharsets.UTF_8));
        ProbeModule missIncModule = new ProbeModule("probeMissInclude", missIncMain);
        missIncModule.setFactory(new TLObjectFactory("cfgMissFactory", missIncDir + "moduleFactory_config.xml", missIncDir));
        check("缺失 include 预检失败", missIncModule.probeConfigFile(missIncMain) != null);

        // 11b) 自动派生配置文件缺失：预检失败，不 configure（防止 validator fail-open）
        String noCfgDir = Files.createTempDirectory("cfgNoFile").toFile().getAbsolutePath() + File.separator;
        ProbeModule noCfgModule = new ProbeModule("probeNoCfg", null);
        noCfgModule.setFactory(new TLObjectFactory("cfgNoFileFactory", noCfgDir + "moduleFactory_config.xml", noCfgDir));
        TLMsg noCfgR = noCfgModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("缺失自动配置文件重载失败", noCfgR != null && !noCfgR.parseBoolean("result", true));
        check("缺失自动配置文件未执行 configure", noCfgModule.configureCount == 0);

        // 12) configure 抛异常：返回失败并带错误
        ThrowingModule throwModule = new ThrowingModule("probeThrow", good);
        TLMsg throwR = throwModule.reloadConfig(null, new TLMsg().setAction("reloadConfig"));
        check("configure 异常返回失败", throwR != null && !throwR.parseBoolean("result", true));
        check("configure 异常有错误信息", throwR != null
                && throwR.getStringParam("error", "").length() > 0);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static class ProbeModule extends TLBaseModule {
        int configureCount;
        ProbeModule(String name, String configFile) { super(name, configFile); }
        @Override protected void configure() { configureCount++; super.configure(); }
        @Override protected TLBaseModule init() { return this; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class ThrowingModule extends ProbeModule {
        ThrowingModule(String name, String configFile) { super(name, configFile); }
        @Override protected void configure() { throw new RuntimeException("boom"); }
    }

    private static String writeTemp(String prefix, String content) throws Exception {
        File f = File.createTempFile(prefix, ".xml");
        f.deleteOnExit();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f.getAbsolutePath();
    }

    private static String writeTempBytes(String prefix, byte[] content) throws Exception {
        File f = File.createTempFile(prefix, ".xml");
        f.deleteOnExit();
        Files.write(f.toPath(), content);
        return f.getAbsolutePath();
    }

    private static byte[] bomBytes(String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
