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

        // 控制中心委托：成功
        TestService delegated = new TestService();
        delegated.setControlCenter("controlCenter");
        delegated.ccResponse = new TLMsg().setParam("result", true)
                .setParam("message", "配置已重载: fromCC")
                .setParam("data", java.util.Map.of("module", "fromCC"));
        TLMsg rc1 = delegated.checkMsgAction(null,
                cmd("fromCC").setParam("userId", "console_user"));
        check("委托控制中心成功", rc1.parseBoolean("success", false));
        check("委托返回消息", rc1.getStringParam("message", "").contains("fromCC"));
        check("委托消息 action", delegated.ccRequest != null
                && "reloadModuleConfig".equals(delegated.ccRequest.getAction()));
        check("委托消息 name", delegated.ccRequest != null
                && "fromCC".equals(delegated.ccRequest.getStringParam("name", "")));
        check("委托消息 userId", delegated.ccRequest != null
                && "console_user".equals(delegated.ccRequest.getStringParam("userId", "")));

        // 控制中心委托：拒绝
        TestService denied = new TestService();
        denied.setControlCenter("controlCenter");
        denied.ccResponse = new TLMsg().setParam("result", false)
                .setParam("error", "模块未在可重载白名单中");
        TLMsg rc2 = denied.checkMsgAction(null, cmd("anyModule"));
        check("委托拒绝透传", !rc2.parseBoolean("success", true));
        check("委托拒绝原因", rc2.getStringParam("error", "").contains("白名单"));

        TestService nullResp = new TestService();
        nullResp.setControlCenter("controlCenter");
        nullResp.ccResponse = null;
        TLMsg rc3 = nullResp.checkMsgAction(null, cmd("anyModule"));
        check("控制中心无响应返回失败", !rc3.parseBoolean("success", true)
                && rc3.getStringParam("error", "").contains("未返回结果"));

        // audit 命令：委托控制中心 auditList 并格式化输出
        TestService auditSvc = new TestService();
        auditSvc.setControlCenter("controlCenter");
        java.util.List<java.util.Map<String, Object>> auditRecords = new java.util.ArrayList<>();
        java.util.Map<String, Object> rec = new java.util.LinkedHashMap<>();
        rec.put("time", 1L);
        rec.put("userId", "console_user");
        rec.put("action", "reloadModuleConfig");
        rec.put("target", "outputValidator");
        rec.put("allowed", true);
        rec.put("success", true);
        rec.put("result", "配置已重载");
        auditRecords.add(rec);
        auditSvc.ccResponse = new TLMsg().setParam("result", auditRecords);
        TLMsg auditResp = auditSvc.checkMsgAction(null,
                new TLMsg().setAction("audit").setParam("limit", 10));
        check("audit 命令成功", auditResp.parseBoolean("success", false));
        check("audit 输出含目标", auditResp.getStringParam("message", "").contains("outputValidator"));
        check("audit 输出含 allowed/success", auditResp.getStringParam("message", "")
                .contains("allowed=true") && auditResp.getStringParam("message", "").contains("success=true"));
        check("audit 请求 action", auditSvc.ccRequest != null
                && "auditList".equals(auditSvc.ccRequest.getAction()));

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }

    static class TestService extends TLAgentService {
        TLBaseModule target;
        TLMsg ccResponse;
        TLMsg ccRequest;
        TestService() { super("reloadConfigTest"); }
        void setControlCenter(String name) {
            if (params == null) params = new java.util.HashMap<>();
            params.put("controlCenterName", name);
        }
        @Override protected TLBaseModule resolveReloadTarget(String name) { return target; }
        @Override protected TLMsg putToControlCenter(String controlCenter, TLMsg msg) {
            ccRequest = msg;
            return ccResponse;
        }
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
