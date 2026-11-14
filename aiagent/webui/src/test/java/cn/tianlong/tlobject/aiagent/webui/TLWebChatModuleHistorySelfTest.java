package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLConversationHistory;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 会话历史 JSON 自测（runnable main，无 JUnit / 无 web 容器）。
 *
 * <p>守：历史回放的工具消息必须带 {@code name}——前端据此识别 {@code view_image}
 * 输入图（不进下载框）；此前 toJsonable 丢 name，重登回放会把分析过的图片误判成生成文件。
 */
public class TLWebChatModuleHistorySelfTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        TLWebChatModule module = new TLWebChatModule("webChatHistorySelfTest");
        Method toJsonable = TLWebChatModule.class.getDeclaredMethod("toJsonable", Object.class);
        toJsonable.setAccessible(true);

        TLConversationHistory tool = new TLConversationHistory("tc1", "view_image",
                "{\"image_path\":\"a.png\",\"image_for_model\":true}");
        Map<?, ?> tjson = (Map<?, ?>) toJsonable.invoke(module, tool);
        check("1 工具消息 JSON 带 name", "view_image".equals(tjson.get("name")));
        check("2 role/content 保持", "tool".equals(tjson.get("role"))
                && String.valueOf(tjson.get("content")).contains("image_path"));

        TLConversationHistory user = new TLConversationHistory(TLConversationHistory.Role.user, "hi");
        Map<?, ?> ujson = (Map<?, ?>) toJsonable.invoke(module, user);
        check("3 普通消息不带空 name", !ujson.containsKey("name"));

        TLConversationHistory plainTool = new TLConversationHistory("tc2", null, "out");
        Map<?, ?> pjson = (Map<?, ?>) toJsonable.invoke(module, plainTool);
        check("4 无名工具消息不写 name", !pjson.containsKey("name"));

        System.out.println("[TLWebChatModuleHistorySelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
