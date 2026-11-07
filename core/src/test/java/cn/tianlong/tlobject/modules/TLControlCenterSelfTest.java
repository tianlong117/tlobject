package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制中心自测（runnable main，无 JUnit——仓库惯例）。
 * 覆盖：注册门面、reload 白名单（名称/familyName/通配/空）、目标校验、
 * 执行失败透传、allowed/success 审计、配置 fail-fast、审计环形。
 */
public class TLControlCenterSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        TestControlCenter cc = new TestControlCenter();
        TestRegistry registry = new TestRegistry("moduleRegistry");
        cc.registryModule = registry;
        cc.reloadableModules = "allowed;cc:allowed;bad";

        TestRegistry initRegistry = new TestRegistry("moduleRegistry");
        InitTestControlCenter initCc = new InitTestControlCenter(initRegistry);
        initCc.setParam("reloadableModules", "x;y");
        initCc.setParam("auditLimit", "-3");
        TLBaseModule initResult = initCc.init();
        check("未显式配置时允许空 startMsg", initResult == initCc);
        check("init 读取白名单", "x;y".equals(initCc.reloadableModules));
        check("负数 auditLimit 回退默认", initCc.auditLimit == 200);

        InitTestControlCenter wildcardInit = new InitTestControlCenter(initRegistry);
        wildcardInit.setParam("reloadableModules", " * ");
        check("通配符 init 正常启动", wildcardInit.init() == wildcardInit);
        check("通配符按全部禁止处理", "".equals(wildcardInit.reloadableModules));

        InitTestControlCenter viewWildcard = new InitTestControlCenter(initRegistry);
        viewWildcard.setParam("viewableModules", "*");
        check("查看白名单通配符 init 正常启动", viewWildcard.init() == viewWildcard);
        check("查看白名单通配符按全部禁止", "".equals(viewWildcard.viewableModules));

        FailFastControlCenter noConfigStart = new FailFastControlCenter(initRegistry);
        noConfigStart.setConfigState(true, null, false);
        check("显式配置但加载失败拒绝启动", noConfigStart.init() == null);

        FailFastControlCenter emptyStart = new FailFastControlCenter(initRegistry);
        emptyStart.setConfigState(true, "x.xml", true);
        check("空启动序列拒绝启动", emptyStart.init() == null);

        FailFastControlCenter validConfig = new FailFastControlCenter(initRegistry);
        validConfig.setConfigState(true, "x.xml", false);
        check("配置完整允许启动", validConfig.init() != null);

        FakeModule allowed = new FakeModule("allowed", "allowed.xml",
                new TLMsg().setParam("result", true).setParam("rules", 2).setParam("file", "a.xml"));
        TLMsg reg = cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "allowed").setParam("instance", allowed));
        check("注册返回成功", reg != null && reg.parseBoolean("result", false));

        TLMsg got = cc.checkMsgAction(null, new TLMsg().setAction("get").setParam("key", "allowed"));
        check("按家族名查询实例", got != null && got.getParam("instance") == allowed);

        TLMsg list = cc.checkMsgAction(null, new TLMsg().setAction("list"));
        check("列表返回一项", list != null && list.getParam("result") instanceof List
                && ((List<?>) list.getParam("result")).size() == 1);

        TLMsg r1 = cc.checkMsgAction(null, reloadCmd("allowed", "u1"));
        check("白名单内重载成功", r1.parseBoolean("result", false));
        check("重载结果带 rules", String.valueOf(r1.getParam("data")).contains("rules=2"));

        FakeModule denied = new FakeModule("denied", "denied.xml",
                new TLMsg().setParam("result", true));
        cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "denied").setParam("instance", denied));
        TLMsg r2 = cc.checkMsgAction(null, reloadCmd("denied", "u1"));
        check("白名单外拒绝", !r2.parseBoolean("result", true));
        check("拒绝原因含白名单", r2.getStringParam("error", "").contains("白名单"));

        cc.reloadableModules = "*";
        TLMsg r2b = cc.checkMsgAction(null, reloadCmd("denied", "u1"));
        check("通配符不再放行", !r2b.parseBoolean("result", true));
        cc.reloadableModules = "allowed;cc:allowed;bad";

        FamilyNameModule family = new FamilyNameModule("family", "family.xml",
                new TLMsg().setParam("result", true)).withFamily("cc:family");
        cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "family").setParam("instance", family));
        cc.reloadableModules = "cc:family";
        TLMsg rFamily = cc.checkMsgAction(null, reloadCmd("family", "u5"));
        check("白名单按 familyName 命中", rFamily.parseBoolean("result", false));
        cc.reloadableModules = "allowed;cc:allowed;bad";

        cc.reloadableModules = "";
        TLMsg rEmpty = cc.checkMsgAction(null, reloadCmd("allowed", "u6"));
        check("空白名单全部拒绝", !rEmpty.parseBoolean("result", true));
        cc.reloadableModules = "allowed;cc:allowed;bad";

        TLMsg r3 = cc.checkMsgAction(null, reloadCmd("missing", "u1"));
        check("目标不存在返回失败", !r3.parseBoolean("result", true));
        check("不存在原因", r3.getStringParam("error", "").contains("不存在"));

        FakeModule noConfig = new FakeModule("noConfig", null, new TLMsg().setParam("result", true));
        cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "noConfig").setParam("instance", noConfig));
        TLMsg r4 = cc.checkMsgAction(null, reloadCmd("noConfig", "u1"));
        check("无配置文件拒绝", !r4.parseBoolean("result", true));
        check("无配置文件原因", r4.getStringParam("error", "").contains("配置文件"));

        // ---- reloadableList（无参 /reloadconfig 的数据源）----
        cc.reloadableModules = " allowed ; ; noConfig ; allowed ; ghost ";
        TLMsg rl = cc.checkMsgAction(null, new TLMsg().setAction("reloadableList"));
        check("查询返回成功", rl != null && rl.parseBoolean("result", false));
        List<?> rlWhitelist = (List<?>) rl.getParam("whitelist");
        check("白名单解析去空去重保序", rlWhitelist != null && rlWhitelist.size() == 3
                && "allowed".equals(rlWhitelist.get(0))
                && "noConfig".equals(rlWhitelist.get(1))
                && "ghost".equals(rlWhitelist.get(2)));
        check("名单模式 mode=list", "list".equals(rl.getStringParam("mode", "")));
        List<?> rlModules = (List<?>) rl.getParam("data");
        check("条目数与白名单一致", rlModules != null && rlModules.size() == 3);
        Map<?, ?> mAllowed = (Map<?, ?>) rlModules.get(0);
        Map<?, ?> mNoConfig = (Map<?, ?>) rlModules.get(1);
        Map<?, ?> mGhost = (Map<?, ?>) rlModules.get(2);
        check("已实例化+有配置可重载", Boolean.TRUE.equals(mAllowed.get("reloadable"))
                && Boolean.TRUE.equals(mAllowed.get("running")));
        check("无配置文件不可重载", Boolean.FALSE.equals(mNoConfig.get("reloadable"))
                && Boolean.TRUE.equals(mNoConfig.get("running"))
                && String.valueOf(mNoConfig.get("reason")).contains("配置文件"));
        check("未运行不可重载", Boolean.FALSE.equals(mGhost.get("reloadable"))
                && Boolean.FALSE.equals(mGhost.get("running"))
                && String.valueOf(mGhost.get("reason")).contains("未运行"));

        cc.reloadableModules = "family";
        TLMsg rlFamily = cc.checkMsgAction(null, new TLMsg().setAction("reloadableList"));
        Object mFamilyObj = ((List<?>) rlFamily.getParam("data")).get(0);
        Map<?, ?> mFamily = (Map<?, ?>) mFamilyObj;
        check("别名条目保留原文并给出 familyName",
                "family".equals(mFamily.get("name")) && "cc:family".equals(mFamily.get("familyName")));

        List<?> auditBefore = (List<?>) cc.checkMsgAction(null,
                new TLMsg().setAction("auditList").setParam("limit", 999)).getParam("result");
        cc.checkMsgAction(null, new TLMsg().setAction("reloadableList"));
        List<?> auditAfter = (List<?>) cc.checkMsgAction(null,
                new TLMsg().setAction("auditList").setParam("limit", 999)).getParam("result");
        check("列表查询不写审计", auditBefore.size() == auditAfter.size());

        cc.reloadableModules = "";
        TLMsg rlNone = cc.checkMsgAction(null, new TLMsg().setAction("reloadableList"));
        check("空白名单查询成功", rlNone.parseBoolean("result", false));
        check("空白名单 mode=none", "none".equals(rlNone.getStringParam("mode", "")));
        check("空白名单条目为空", ((List<?>) rlNone.getParam("data")).isEmpty());
        cc.reloadableModules = "allowed;cc:allowed;bad";

        FakeModule bad = new FakeModule("bad", "bad.xml",
                new TLMsg().setParam("result", false).setParam("error", "boom"));
        cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "bad").setParam("instance", bad));
        TLMsg r5 = cc.checkMsgAction(null, reloadCmd("bad", "u1"));
        check("模块失败透传", !r5.parseBoolean("result", true)
                && r5.getStringParam("error", "").contains("boom"));

        TLMsg audit = cc.checkMsgAction(null, new TLMsg().setAction("auditList").setParam("limit", 20));
        List<?> records = (List<?>) audit.getParam("result");
        check("审计有记录", records != null && records.size() >= 5);
        boolean deniedRecorded = false, allowedOk = false, badFailed = false;
        for (Object o : records) {
            Map<?, ?> m = (Map<?, ?>) o;
            if ("denied".equals(m.get("target")) && Boolean.FALSE.equals(m.get("allowed"))
                    && Boolean.FALSE.equals(m.get("success")))
                deniedRecorded = true;
            if ("allowed".equals(m.get("target")) && Boolean.TRUE.equals(m.get("allowed"))
                    && Boolean.TRUE.equals(m.get("success")))
                allowedOk = true;
            if ("bad".equals(m.get("target")) && Boolean.TRUE.equals(m.get("allowed"))
                    && Boolean.FALSE.equals(m.get("success")))
                badFailed = true;
        }
        check("拒绝操作被审计（allowed=false success=false）", deniedRecorded);
        check("成功审计 allowed=true success=true", allowedOk);
        check("白名单内执行失败 allowed=true success=false", badFailed);

        check("审计最新在前", records.size() > 0
                && "bad".equals(((Map<?, ?>) records.get(0)).get("target")));

        // ---- configView / paramsView（受 viewableModules 白名单）----
        File cfgFile = File.createTempFile("ccview", ".xml");
        String cfgText = "<moduleConfig><params><a value=\"1\"/></params></moduleConfig>";
        Files.write(cfgFile.toPath(), cfgText.getBytes(StandardCharsets.UTF_8));
        FakeModule viewMod = new FakeModule("viewMod", cfgFile.getAbsolutePath(), new TLMsg());
        viewMod.setTestParams(Map.of("alpha", "1", "beta", "2"));
        cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "viewMod").setParam("instance", viewMod));

        cc.viewableModules = "";
        TLMsg deniedEmpty = cc.checkMsgAction(null, new TLMsg().setAction("configView")
                .setParam("name", "viewMod").setParam("userId", "u7"));
        check("查看白名单为空拒绝", !deniedEmpty.parseBoolean("result", true));

        cc.viewableModules = "viewMod";
        TLMsg cv = cc.checkMsgAction(null, new TLMsg().setAction("configView")
                .setParam("name", "viewMod").setParam("userId", "u7"));
        check("配置文件查看成功", cv != null && cv.parseBoolean("result", false));
        Map<?, ?> cvData = (Map<?, ?>) cv.getParam("data");
        check("查看返回配置路径", cvData != null
                && cfgFile.getAbsolutePath().equals(cvData.get("configFile")));
        check("查看返回原文", cvData != null && cfgText.equals(cvData.get("content")));
        check("查看返回 sha256/大小", cvData != null
                && String.valueOf(cvData.get("sha256")).length() == 64
                && Long.parseLong(String.valueOf(cvData.get("size")))
                == cfgText.getBytes(StandardCharsets.UTF_8).length);
        check("无重载记录时不带 lastReload", cvData != null && !cvData.containsKey("lastReload"));

        TLMsg pv = cc.checkMsgAction(null, new TLMsg().setAction("paramsView")
                .setParam("name", "viewMod").setParam("userId", "u7"));
        check("运行参数查看成功", pv != null && pv.parseBoolean("result", false));
        Map<?, ?> pvData = (Map<?, ?>) pv.getParam("data");
        Map<?, ?> viewParams = pvData == null ? null : (Map<?, ?>) pvData.get("params");
        check("运行参数内容完整", viewParams != null && "1".equals(viewParams.get("alpha"))
                && "2".equals(viewParams.get("beta")));

        cc.viewableModules = "other";
        TLMsg cvDenied = cc.checkMsgAction(null, new TLMsg().setAction("configView")
                .setParam("name", "viewMod").setParam("userId", "u8"));
        check("白名单外禁止查看", !cvDenied.parseBoolean("result", true)
                && cvDenied.getStringParam("error", "").contains("白名单"));
        cc.viewableModules = "viewMod";

        FakeModule noCfgView = new FakeModule("noCfgView", null, null);
        noCfgView.setTestParams(Map.of("x", "y"));
        cc.checkMsgAction(null, new TLMsg().setAction("register")
                .setParam("key", "noCfgView").setParam("instance", noCfgView));
        cc.viewableModules = "viewMod;noCfgView";
        TLMsg noCfgCv = cc.checkMsgAction(null, new TLMsg().setAction("configView")
                .setParam("name", "noCfgView").setParam("userId", "u9"));
        check("无配置文件查看失败", !noCfgCv.parseBoolean("result", true)
                && noCfgCv.getStringParam("error", "").contains("配置文件"));
        TLMsg noCfgPv = cc.checkMsgAction(null, new TLMsg().setAction("paramsView")
                .setParam("name", "noCfgView").setParam("userId", "u9"));
        check("无配置文件仍可看运行参数", noCfgPv.parseBoolean("result", false));

        // 回归：工厂解析出的 Windows 路径可能带前导 "/"（/D:/...）——必须规范化后再 Paths.get
        String winAbs = cfgFile.getAbsolutePath();
        if (winAbs.matches("^[A-Za-z]:.*")) {
            FakeModule slashMod = new FakeModule("slashMod", "/" + winAbs, new TLMsg());
            cc.checkMsgAction(null, new TLMsg().setAction("register")
                    .setParam("key", "slashMod").setParam("instance", slashMod));
            cc.viewableModules = "viewMod;noCfgView;slashMod";
            TLMsg slashCv = cc.checkMsgAction(null, new TLMsg().setAction("configView")
                    .setParam("name", "slashMod").setParam("userId", "u11"));
            check("前导斜杠 Windows 路径可读", slashCv.parseBoolean("result", false));
            Map<?, ?> slashData = (Map<?, ?>) slashCv.getParam("data");
            check("前导斜杠路径已规范化", slashData != null
                    && cfgText.equals(slashData.get("content"))
                    && !String.valueOf(slashData.get("configFile")).startsWith("/"));
            cc.viewableModules = "viewMod";
        }

        // 配置重载后白名单刷新（setModuleParams 路径）：追加的模块立即生效
        TestControlCenter whitelistReload = new TestControlCenter();
        whitelistReload.setTestParams(Map.of("viewableModules", "a;b",
                "reloadableModules", "x"));
        check("重载后查看白名单生效", whitelistReload.isViewAllowed("a", null)
                && whitelistReload.isViewAllowed("b", null)
                && !whitelistReload.isViewAllowed("c", null));
        whitelistReload.setTestParams(Map.of("viewableModules", "a;b;c",
                "reloadableModules", "x;y"));
        check("重载追加白名单立即生效", whitelistReload.isViewAllowed("c", null)
                && whitelistReload.reloadableModules.contains("y"));

        // 白名单以配置文件为权威：删掉的条目重载后必须失效（不沿用合并 params 的旧值）
        File wlFile = File.createTempFile("ccwhitelist", ".xml");
        Files.write(wlFile.toPath(), ("<moduleConfig><params>"
                + "<viewableModules value=\"a;b\"/><reloadableModules value=\"x\"/>"
                + "</params></moduleConfig>").getBytes(StandardCharsets.UTF_8));
        TestControlCenter wlCc = new TestControlCenter();
        wlCc.useConfigFile(wlFile.getAbsolutePath());
        wlCc.refreshModuleParams();
        check("白名单文件解析生效", wlCc.isViewAllowed("a", null) && wlCc.isViewAllowed("b", null));
        Files.write(wlFile.toPath(), ("<moduleConfig><params>"
                + "<viewableModules value=\"a\"/><reloadableModules value=\"x\"/>"
                + "</params></moduleConfig>").getBytes(StandardCharsets.UTF_8));
        wlCc.refreshModuleParams();
        check("白名单删除项重载后失效", wlCc.isViewAllowed("a", null)
                && !wlCc.isViewAllowed("b", null));
        wlFile.delete();

        // viewable 标记进 reloadableList
        cc.reloadableModules = "viewMod;noCfgView;allowed";
        cc.viewableModules = "viewMod";
        TLMsg rl2 = cc.checkMsgAction(null, new TLMsg().setAction("reloadableList"));
        List<?> rl2Rows = (List<?>) rl2.getParam("data");
        boolean viewableFlag = false, notViewableFlag = false;
        for (Object o : rl2Rows) {
            Map<?, ?> m = (Map<?, ?>) o;
            if ("viewMod".equals(m.get("name")) && Boolean.TRUE.equals(m.get("viewable")))
                viewableFlag = true;
            if ("allowed".equals(m.get("name")) && Boolean.FALSE.equals(m.get("viewable")))
                notViewableFlag = true;
        }
        check("reloadableList 带 viewable=true", viewableFlag);
        check("reloadableList 带 viewable=false", notViewableFlag);
        cc.reloadableModules = "allowed;cc:allowed;bad";

        // 查看操作写审计（成功 + 拒绝）
        TLMsg auditView = cc.checkMsgAction(null, new TLMsg().setAction("auditList").setParam("limit", 50));
        List<?> viewRecords = (List<?>) auditView.getParam("result");
        boolean viewAllowed = false, viewDenied = false;
        for (Object o : viewRecords) {
            Map<?, ?> m = (Map<?, ?>) o;
            if ("configView".equals(m.get("action")) && Boolean.TRUE.equals(m.get("allowed")))
                viewAllowed = true;
            if ("configView".equals(m.get("action")) && Boolean.FALSE.equals(m.get("allowed")))
                viewDenied = true;
        }
        check("查看成功被审计", viewAllowed);
        check("查看拒绝被审计", viewDenied);

        // 查看附带最近重载记录
        cc.reloadableModules = "viewMod";
        cc.viewableModules = "viewMod";
        cc.checkMsgAction(null, reloadCmd("viewMod", "u10"));
        TLMsg cv2 = cc.checkMsgAction(null, new TLMsg().setAction("configView")
                .setParam("name", "viewMod").setParam("userId", "u10"));
        Map<?, ?> cv2Data = (Map<?, ?>) cv2.getParam("data");
        check("查看附带最近重载记录", cv2Data != null && cv2Data.get("lastReload") instanceof Map
                && Boolean.TRUE.equals(((Map<?, ?>) cv2Data.get("lastReload")).get("success")));
        cc.reloadableModules = "allowed;cc:allowed;bad";
        cfgFile.delete();

        TLMsg r6 = cc.checkMsgAction(null, new TLMsg().setAction("reloadModuleConfig"));
        check("缺少 name 返回失败", !r6.parseBoolean("result", true));
        check("缺少 name 原因", r6.getStringParam("error", "").contains("缺少参数"));

        TLMsg rAlias = cc.checkMsgAction(null, new TLMsg().setAction("reloadModuleConfig")
                .setParam("module", "allowed").setParam("userId", "u4"));
        check("module 别名可用", rAlias.parseBoolean("result", false));

        cc.auditLimit = 2;
        cc.checkMsgAction(null, reloadCmd("allowed", "u2"));
        cc.checkMsgAction(null, reloadCmd("allowed", "u3"));
        TLMsg audit2 = cc.checkMsgAction(null, new TLMsg().setAction("auditList").setParam("limit", 20));
        List<?> bounded = (List<?>) audit2.getParam("result");
        check("审计环形有界", bounded != null && bounded.size() == 2);
        cc.auditLimit = 200;

        TLMsg unreg = cc.checkMsgAction(null, new TLMsg().setAction("unregister")
                .setParam("key", "allowed"));
        check("注销返回成功", unreg != null && unreg.parseBoolean("result", false));
        TLMsg got2 = cc.checkMsgAction(null, new TLMsg().setAction("get").setParam("key", "allowed"));
        check("注销后查询为空", got2 == null || got2.getParam("instance") == null);

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    private static TLMsg reloadCmd(String name, String userId) {
        return new TLMsg().setAction("reloadModuleConfig")
                .setParam("name", name).setParam("userId", userId);
    }

    static class TestControlCenter extends TLControlCenter {
        TestControlCenter() { super("controlCenterSelfTest"); }
        void setTestParams(Map<String, String> p) {
            this.params = new HashMap<>(p);
            setModuleParams();
        }
        void useConfigFile(String f) { this.configFile = f; }
        void refreshModuleParams() { setModuleParams(); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class InitTestControlCenter extends TLControlCenter {
        private final TLModuleRegistry stubRegistry;

        InitTestControlCenter(TLModuleRegistry stubRegistry) {
            super("initTest");
            this.stubRegistry = stubRegistry;
        }

        /** params 声明在 base 包，同包测试类无法直接赋值，用 setter 注入。 */
        void setParam(String key, String value) {
            if (params == null) params = new java.util.HashMap<>();
            params.put(key, value);
        }

        @Override protected Object getMyModule(String moduleName) { return stubRegistry; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class FailFastControlCenter extends TLControlCenter {
        private final TLModuleRegistry stubRegistry;

        FailFastControlCenter(TLModuleRegistry stubRegistry) {
            super("failFastTest");
            this.stubRegistry = stubRegistry;
        }

        /** configFile/startMsgTable 声明在 base 包，通过子类内部赋值。 */
        void setConfigState(boolean requested, String configFile, boolean emptyStart) {
            this.configRequested = requested;
            this.configFile = configFile;
            this.startMsgTable = new java.util.ArrayList<>();
            if (!emptyStart) this.startMsgTable.add(new TLMsg().setAction("getModule"));
        }

        @Override protected Object getMyModule(String moduleName) { return stubRegistry; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class TestRegistry extends TLModuleRegistry {
        TestRegistry(String name) { super(name); }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class FamilyNameModule extends FakeModule {
        FamilyNameModule(String name, String configFile, TLMsg reloadResponse) {
            super(name, configFile, reloadResponse);
        }

        FamilyNameModule withFamily(String familyName) {
            this.familyName = familyName;
            return this;
        }
    }

    static class FakeModule extends TLBaseModule {
        private final TLMsg reloadResponse;

        FakeModule(String name, String configFile, TLMsg reloadResponse) {
            super(name, configFile);
            this.reloadResponse = reloadResponse;
        }

        void setTestParams(Map<String, String> p) {
            this.params = new HashMap<>(p);
        }

        @Override protected TLMsg reloadConfig(Object fromWho, TLMsg msg) { return reloadResponse; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override protected TLBaseModule init() { return this; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    private static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + desc); }
        else { failed++; System.out.println("[FAIL] " + desc); }
    }
}
