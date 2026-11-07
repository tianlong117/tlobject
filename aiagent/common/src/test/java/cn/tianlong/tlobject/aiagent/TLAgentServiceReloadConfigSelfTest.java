package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

        // reloadableModules：无控制中心
        TestService noCc = new TestService();
        TLMsg rlNoCc = noCc.checkMsgAction(null, new TLMsg().setAction("reloadableModules"));
        check("可重载查询无控制中心返回失败", !rlNoCc.parseBoolean("success", true));
        check("可重载查询无控制中心原因", rlNoCc.getStringParam("error", "").contains("控制中心未配置"));

        // reloadableModules：委托成功，data 包装为 {mode, whitelist, modules}
        TestService rlSvc = new TestService();
        rlSvc.setControlCenter("controlCenter");
        List<Map<String, Object>> modules = new ArrayList<>();
        Map<String, Object> mm = new LinkedHashMap<>();
        mm.put("name", "outputValidator");
        mm.put("familyName", "");
        mm.put("running", true);
        mm.put("configFile", "x.xml");
        mm.put("reloadable", true);
        mm.put("reason", "");
        modules.add(mm);
        List<String> wl = List.of("outputValidator");
        rlSvc.ccResponse = new TLMsg().setParam("result", true)
                .setParam("mode", "list").setParam("whitelist", wl).setParam("data", modules);
        TLMsg rlOk = rlSvc.checkMsgAction(null, new TLMsg().setAction("reloadableModules"));
        check("可重载查询成功", rlOk.parseBoolean("success", false));
        check("可重载查询 action", rlSvc.ccRequest != null
                && "reloadableList".equals(rlSvc.ccRequest.getAction()));
        check("可重载查询消息含模块名", rlOk.getStringParam("message", "").contains("outputValidator"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rlData = (Map<String, Object>) rlOk.getParam("data");
        check("可重载 data 含 mode", rlData != null && "list".equals(rlData.get("mode")));
        check("可重载 data 含 modules", rlData != null && rlData.get("modules") instanceof List);

        rlSvc.ccResponse = new TLMsg().setParam("result", true)
                .setParam("mode", "none").setParam("whitelist", List.of()).setParam("data", List.of());
        TLMsg rlNone = rlSvc.checkMsgAction(null, new TLMsg().setAction("reloadableModules"));
        check("空白名单查询成功", rlNone.parseBoolean("success", false));
        check("空白名单提示", rlNone.getStringParam("message", "").contains("没有可重载配置的模块"));

        rlSvc.ccResponse = new TLMsg().setParam("result", false).setParam("error", "boom-list");
        TLMsg rlFail = rlSvc.checkMsgAction(null, new TLMsg().setAction("reloadableModules"));
        check("可重载查询失败透传", !rlFail.parseBoolean("success", true)
                && rlFail.getStringParam("error", "").contains("boom-list"));

        rlSvc.ccResponse = null;
        TLMsg rlNull = rlSvc.checkMsgAction(null, new TLMsg().setAction("reloadableModules"));
        check("可重载查询无响应返回失败", !rlNull.parseBoolean("success", true)
                && rlNull.getStringParam("error", "").contains("未响应"));

        rlSvc.ccResponse = new TLMsg().setParam("result", true)
                .setParam("mode", "list").setParam("whitelist", List.of()).setParam("data", "bad");
        TLMsg rlBad = rlSvc.checkMsgAction(null, new TLMsg().setAction("reloadableModules"));
        check("可重载查询格式错误返回失败", !rlBad.parseBoolean("success", true)
                && rlBad.getStringParam("error", "").contains("格式错误"));

        // configView / paramsView：无控制中心
        TestService noCcView = new TestService();
        TLMsg cvNoCc = noCcView.checkMsgAction(null,
                new TLMsg().setAction("configView").setParam("name", "mod"));
        check("查看配置无控制中心返回失败", !cvNoCc.parseBoolean("success", true)
                && cvNoCc.getStringParam("error", "").contains("控制中心未配置"));

        // configView：委托成功、文案含元信息与最近重载
        TestService viewSvc = new TestService();
        viewSvc.setControlCenter("controlCenter");
        Map<String, Object> lastReload = new LinkedHashMap<>();
        lastReload.put("time", 1794000000000L);
        lastReload.put("allowed", true);
        lastReload.put("success", true);
        lastReload.put("result", "配置已重载: mod");
        Map<String, Object> cfgData = new LinkedHashMap<>();
        cfgData.put("module", "mod");
        cfgData.put("familyName", "");
        cfgData.put("configFile", "x.xml");
        cfgData.put("size", 10L);
        cfgData.put("modifiedAt", 1794000000000L);
        cfgData.put("sha256", "abcdef");
        cfgData.put("content", "<moduleConfig/>");
        cfgData.put("truncated", false);
        cfgData.put("lastReload", lastReload);
        viewSvc.ccResponse = new TLMsg().setParam("result", true).setParam("data", cfgData);
        TLMsg cvOk = viewSvc.checkMsgAction(null,
                new TLMsg().setAction("configView").setParam("name", "mod"));
        check("查看配置成功", cvOk.parseBoolean("success", false));
        check("查看配置委托 action", viewSvc.ccRequest != null
                && "configView".equals(viewSvc.ccRequest.getAction()));
        String cvMsg = cvOk.getStringParam("message", "");
        check("查看配置文案含路径/哈希/内容",
                cvMsg.contains("x.xml") && cvMsg.contains("abcdef")
                        && cvMsg.contains("<moduleConfig/>"));
        check("查看配置文案含最近重载", cvMsg.contains("配置已重载: mod"));
        check("查看配置 data 透传", cvOk.getParam("data") == cfgData);

        // paramsView：按键排序
        Map<String, Object> paramsData = new LinkedHashMap<>();
        paramsData.put("module", "mod");
        paramsData.put("familyName", "");
        Map<String, Object> ps = new LinkedHashMap<>();
        ps.put("b", "2");
        ps.put("a", "1");
        paramsData.put("params", ps);
        viewSvc.ccResponse = new TLMsg().setParam("result", true).setParam("data", paramsData);
        TLMsg pvOk = viewSvc.checkMsgAction(null,
                new TLMsg().setAction("paramsView").setParam("name", "mod"));
        check("查看参数成功", pvOk.parseBoolean("success", false));
        check("查看参数委托 action", viewSvc.ccRequest != null
                && "paramsView".equals(viewSvc.ccRequest.getAction()));
        String pvMsg = pvOk.getStringParam("message", "");
        check("查看参数按键排序", pvMsg.indexOf("a = 1") >= 0
                && pvMsg.indexOf("a = 1") < pvMsg.indexOf("b = 2"));

        // 缺少 name
        TLMsg cvNoName = viewSvc.checkMsgAction(null, new TLMsg().setAction("configView"));
        check("查看缺少 name 返回失败", !cvNoName.parseBoolean("success", true)
                && cvNoName.getStringParam("error", "").contains("用法"));

        // 控制中心失败 / 无响应 / 格式错误
        viewSvc.ccResponse = new TLMsg().setParam("result", false).setParam("error", "view denied");
        TLMsg cvDenied = viewSvc.checkMsgAction(null,
                new TLMsg().setAction("configView").setParam("name", "mod"));
        check("查看失败透传", !cvDenied.parseBoolean("success", true)
                && cvDenied.getStringParam("error", "").contains("view denied"));

        viewSvc.ccResponse = null;
        TLMsg cvNull = viewSvc.checkMsgAction(null,
                new TLMsg().setAction("configView").setParam("name", "mod"));
        check("查看无响应返回失败", !cvNull.parseBoolean("success", true)
                && cvNull.getStringParam("error", "").contains("未响应"));

        viewSvc.ccResponse = new TLMsg().setParam("result", true).setParam("data", "bad");
        TLMsg cvBad = viewSvc.checkMsgAction(null,
                new TLMsg().setAction("configView").setParam("name", "mod"));
        check("查看格式错误返回失败", !cvBad.parseBoolean("success", true)
                && cvBad.getStringParam("error", "").contains("格式错误"));

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
            setModuleParams();
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
