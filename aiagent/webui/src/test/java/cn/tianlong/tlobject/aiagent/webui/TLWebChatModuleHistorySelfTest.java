package cn.tianlong.tlobject.aiagent.webui;

import cn.tianlong.tlobject.aiagent.TLConversationHistory;
import cn.tianlong.tlobject.aiagent.TLToolCall;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

        // 5-8) 工具轮中间说明：toolRound + narration（老会话无 metadata 时按 toolCalls 合成）
        List<TLToolCall> calls = new ArrayList<>();
        Map<String, Object> targs = new LinkedHashMap<>();
        targs.put("query", "肺癌 中国");
        calls.add(new TLToolCall("tc9", "search", targs));
        TLConversationHistory toolRound = new TLConversationHistory(
                TLConversationHistory.Role.assistant, calls);
        Map<?, ?> trjson = (Map<?, ?>) toJsonable.invoke(module, toolRound);
        check("5 工具轮 assistant 带 toolRound", Boolean.TRUE.equals(trjson.get("toolRound")));
        check("6 工具轮空 content 合成 narration",
                String.valueOf(trjson.get("narration")).contains("正在搜索")
                        && String.valueOf(trjson.get("narration")).contains("肺癌"));

        TLConversationHistory withContent = new TLConversationHistory(
                TLConversationHistory.Role.assistant, calls);
        withContent.setContent("模型写的解说");
        Map<?, ?> wjson = (Map<?, ?>) toJsonable.invoke(module, withContent);
        check("7 narration 优先取 content", "模型写的解说".equals(wjson.get("narration")));

        TLConversationHistory plain = new TLConversationHistory(
                TLConversationHistory.Role.assistant, "普通回答");
        Map<?, ?> plainJson = (Map<?, ?>) toJsonable.invoke(module, plain);
        check("8 普通 assistant 无 toolRound", !plainJson.containsKey("toolRound"));

        System.out.println("[TLWebChatModuleHistorySelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
