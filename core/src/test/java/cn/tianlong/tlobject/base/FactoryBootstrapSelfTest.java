package cn.tianlong.tlobject.base;

import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.modules.TLControlCenter;
import cn.tianlong.tlobject.modules.TLModuleRegistry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * 工厂代执行启动序列自测（runnable main，无 JUnit——仓库惯例）。
 * bootstrapModules 以工厂为发送者执行 getModule：
 * 直接创建的模块保持裸 familyName；经其他模块转发的才带父前缀。
 */
public class FactoryBootstrapSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("factoryBootstrap").toFile();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<moduleConfig>\n"
                + "  <params><ifLog value=\"false\"/></params>\n"
                + "  <commonParams><ifLog value=\"false\"/></commonParams>\n"
                + "  <modules>\n"
                + "    <module name=\"bootstrapChild\" classfile=\"cn.tianlong.tlobject.modules.TLModuleRegistry\"/>\n"
                + "    <module name=\"relayChild\" classfile=\"cn.tianlong.tlobject.modules.TLModuleRegistry\"/>\n"
                + "    <module name=\"relay\" classfile=\"cn.tianlong.tlobject.base.FactoryBootstrapSelfTest$RelayModule\"/>\n"
                + "    <module name=\"controlCenter\"\n"
                + "            classfile=\"cn.tianlong.tlobject.modules.TLControlCenter\"\n"
                + "            singleton=\"true\"\n"
                + "            configfile=\"controlCenter_config.xml\"/>\n"
                + "    <module name=\"appChild\" classfile=\"cn.tianlong.tlobject.modules.TLModuleRegistry\"/>\n"
                + "  </modules>\n"
                + "</moduleConfig>\n";
        Files.write(new File(dir, "moduleFactory_config.xml").toPath(),
                xml.getBytes(StandardCharsets.UTF_8));

        // 控制中心配置：启动序列向工厂发 getModule（不直接建模块），
        // 由 TLControlCenter.runStartMsg 委托工厂代执行。
        String ccXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<moduleConfig>\n"
                + "    <modules>\n"
                + "        <module name=\"moduleRegistry\"\n"
                + "                classfile=\"cn.tianlong.tlobject.modules.TLModuleRegistry\"/>\n"
                + "    </modules>\n"
                + "    <startMsg>\n"
                + "        <msg action=\"getModule\" destination=\"moduleFactory\" moduleName=\"appChild\"/>\n"
                + "    </startMsg>\n"
                + "</moduleConfig>\n";
        Files.write(new File(dir, "controlCenter_config.xml").toPath(),
                ccXml.getBytes(StandardCharsets.UTF_8));

        TLObjectFactory factory = TLObjectFactory.getInstance(dir.getAbsolutePath(),
                "moduleFactory_config.xml");
        factory.startFactory(null, null);
        factory.boot();

        ArrayList<TLMsg> list = new ArrayList<>();
        list.add(new TLMsg().setAction("getModule").setDestination("moduleFactory")
                .setParam("moduleName", "bootstrapChild"));
        list.add(new TLMsg().setAction("getModule").setDestination("moduleFactory")
                .setParam("moduleName", "relay"));
        TLMsg result = factory.getMsg(null, new TLMsg()
                .setAction("bootstrapModules").setParam("msgList", list));
        check("bootstrapModules 返回成功", result != null && result.parseBoolean("result", false));

        TLMsg badList = factory.getMsg(null, new TLMsg()
                .setAction("bootstrapModules").setParam("msgList", "notAList"));
        check("非法 msgList 返回失败", badList != null
                && !badList.parseBoolean("result", true));

        TLBaseModule direct = existing(factory, "bootstrapChild");
        check("直接创建模块已实例化", direct != null);
        check("直接创建模块 familyName 无前缀",
                direct != null && "bootstrapChild".equals(direct.getFamilyName()));

        TLBaseModule relay = existing(factory, "relay");
        check("中继模块已实例化", relay != null);
        check("中继模块 familyName 无前缀",
                relay != null && "relay".equals(relay.getFamilyName()));

        TLMsg made = relay == null ? null
                : factory.putMsg(relay, new TLMsg().setAction("makeChild"));
        Object relayChild = made == null ? null : made.getParam("instance");
        check("中继创建子模块", relayChild instanceof TLBaseModule);
        check("中继子模块带父前缀",
                relayChild instanceof TLBaseModule
                        && "relay:relayChild".equals(((TLBaseModule) relayChild).getFamilyName()));

        // 经真实工厂实例化控制中心：触发其 runStartMsg（委托工厂执行 <startMsg>）。
        // 若控制中心改回本地 doMsgList，appChild 会带 "controlCenter:" 前缀，此处失败。
        TLMsg ccCreated = factory.getMsg(null, new TLMsg().setAction("getModule")
                .setDestination("moduleFactory").setParam("moduleName", "controlCenter"));
        check("控制中心创建成功", ccCreated != null
                && ccCreated.getParam("instance") instanceof TLControlCenter);

        TLBaseModule appChild = existing(factory, "appChild");
        check("控制中心启动序列已执行", appChild != null);
        check("控制中心启动的模块 familyName 无前缀",
                appChild != null && "appChild".equals(appChild.getFamilyName()));

        factory.destroyModule();
        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static TLBaseModule existing(TLObjectFactory factory, String name) {
        TLMsg got = factory.getMsg(null, new TLMsg()
                .setAction("getModuleInFactory").setParam("moduleName", name));
        Object instance = got == null ? null : got.getParam("instance");
        return instance instanceof TLBaseModule ? (TLBaseModule) instance : null;
    }

    public static class RelayModule extends TLBaseModule {
        public RelayModule(String name, TLObjectFactory factory) { super(name, factory); }

        @Override protected TLBaseModule init() { return this; }

        @Override protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
            if ("makeChild".equals(msg.getAction())) {
                Object child = getModuleFromFactory("relayChild");
                return createMsg().setParam("instance", child);
            }
            return null;
        }

        @Override public void putLog(String content, LogLevel logLevel) { }
        @Override public void putLog(String content, LogLevel logLevel, String tag) { }
    }

    private static void check(String desc, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + desc); }
        else { failed++; System.out.println("[FAIL] " + desc); }
    }
}
