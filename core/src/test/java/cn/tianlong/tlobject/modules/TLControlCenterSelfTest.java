package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.List;
import java.util.Map;

/**
 * 控制中心自测（runnable main，无 JUnit——仓库惯例）。
 * 覆盖：注册门面、reload 白名单（名称/familyName/通配/空）、目标校验、
 * 执行失败透传、allowed/success 审计、配置 fail-fast、审计环形。
 */
public class TLControlCenterSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
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
        check("通配符放行", r2b.parseBoolean("result", false));
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
