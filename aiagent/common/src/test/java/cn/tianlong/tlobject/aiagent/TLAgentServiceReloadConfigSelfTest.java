package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.Map;

/**
 * reloadModuleConfig 命令层结果映射自测（runnable main）。
 * 覆盖：未运行、无配置文件、成功带 rules、模块失败、模块返回 null。
 */
public class TLAgentServiceReloadConfigSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        TestService service = new TestService();

        service.target = null;
        TLMsg r1 = service.checkMsgAction(null, cmd("notRunning"));
        check("未运行模块返回失败", !r1.parseBoolean("success", true));
        check("错误信息包含模块名", r1.getStringParam("error", "").contains("notRunning"));

        service.target = new FakeModule("outputValidator", "dummy.xml",
                new TLMsg().setParam("result", true).setParam("rules", 3).setParam("file", "x.xml"));
        TLMsg r2 = service.checkMsgAction(null, cmd("outputValidator"));
        check("成功返回 success", r2.parseBoolean("success", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r2.getParam("data");
        check("返回 rules", data != null && "3".equals(String.valueOf(data.get("rules"))));

        service.target = new FakeModule("bad", "dummy.xml",
                new TLMsg().setParam("result", false).setParam("error", "boom"));
        TLMsg r3 = service.checkMsgAction(null, cmd("bad"));
        check("模块失败返回失败", !r3.parseBoolean("success", true));
        check("透传错误", r3.getStringParam("error", "").contains("boom"));

        service.target = new FakeModule("nullResult", "dummy.xml", null);
        TLMsg r4 = service.checkMsgAction(null, cmd("nullResult"));
        check("null 结果按已发送处理", r4.parseBoolean("success", false));

        service.target = new FakeModule("noConfig", null, null);
        TLMsg r5 = service.checkMsgAction(null, cmd("noConfig"));
        check("无配置文件模块返回失败", !r5.parseBoolean("success", true));

        // 缺少 name 参数
        TLMsg r6 = service.checkMsgAction(null, new TLMsg().setAction("reloadModuleConfig"));
        check("缺少 name 返回失败", !r6.parseBoolean("success", true));
        check("缺少 name 错误信息", r6.getStringParam("error", "").contains("缺少参数"));

        // 模块抛异常
        service.target = new ThrowingFakeModule("throwing", "dummy.xml");
        TLMsg r7 = service.checkMsgAction(null, cmd("throwing"));
        check("模块异常返回失败", !r7.parseBoolean("success", true));
        check("模块异常错误信息", r7.getStringParam("error", "").contains("异常"));

        // 非 null 但无 RESULT：兼容为成功
        service.target = new FakeModule("noResult", "dummy.xml",
                new TLMsg().setParam("anything", "x"));
        TLMsg r8 = service.checkMsgAction(null, cmd("noResult"));
        check("无 RESULT 按成功处理", r8.parseBoolean("success", false));

        // 成功时 file 与 message 也映射
        service.target = new FakeModule("withFile", "dummy.xml",
                new TLMsg().setParam("result", true).setParam("rules", 2).setParam("file", "a.xml"));
        TLMsg r9 = service.checkMsgAction(null, cmd("withFile"));
        @SuppressWarnings("unchecked")
        Map<String, Object> d9 = (Map<String, Object>) r9.getParam("data");
        check("成功映射 file", d9 != null && "a.xml".equals(String.valueOf(d9.get("file"))));
        check("成功消息含 rules", r9.getStringParam("message", "").contains("rules=2"));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static class TestService extends TLAgentService {
        TLBaseModule target;
        TestService() { super("reloadConfigTest"); }
        @Override protected TLBaseModule resolveReloadTarget(String name) { return target; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class FakeModule extends TLBaseModule {
        private final TLMsg response;
        FakeModule(String name, String configFile, TLMsg response) {
            super(name, configFile);
            this.response = response;
        }
        @Override protected TLMsg reloadConfig(Object fromWho, TLMsg msg) { return response; }
        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) { return null; }
        @Override protected TLBaseModule init() { return this; }
        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    static class ThrowingFakeModule extends FakeModule {
        ThrowingFakeModule(String name, String configFile) { super(name, configFile, null); }
        @Override protected TLMsg reloadConfig(Object fromWho, TLMsg msg) {
            throw new RuntimeException("boom");
        }
    }

    private static TLMsg cmd(String name) {
        return new TLMsg().setAction("reloadModuleConfig").setParam("name", name);
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
