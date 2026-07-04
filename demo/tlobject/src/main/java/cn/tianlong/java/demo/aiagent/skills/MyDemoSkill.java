package cn.tianlong.java.demo.aiagent.skills;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义演示Skill——简单的Echo功能。
 * 函数名: demo_echo
 * 描述: 回显输入的消息，用于测试运行时Skill注册功能。
 *
 * 输入参数:
 *   - message: 要回显的消息文本（必填）
 *
 * 输出: "Echo: {message}"
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class MyDemoSkill extends TLBaseSkill {

    public MyDemoSkill() {
        super();
    }

    public MyDemoSkill(String name) {
        super(name);
    }

    public MyDemoSkill(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        // 确保正确的skill标识
        if (skillName == null || skillName.isEmpty()) {
            skillName = "demo_echo";
        }
        if (skillDescription == null || skillDescription.isEmpty()) {
            skillDescription = "Echo back the input message. "
                    + "Use this function when you need to repeat or echo text back. "
                    + "Used for testing skill registration.";
        }

        // 定义参数schema
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> messageProp = new LinkedHashMap<>();
            messageProp.put("type", "string");
            messageProp.put("description", "The message text to echo back");
            messageProp.put("required", true);
            parameterSchema.put("message", messageProp);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        // 从skillInput中提取参数
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        String message = (String) input.getOrDefault("message", "");

        // 兼容直接从msg参数提取
        if (message.isEmpty() && msg.containsParam("message")) {
            message = msg.getStringParam("message", "");
        }

        if (message.isEmpty()) {
            return createMsg()
                    .setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: 'message' parameter is required for demo_echo");
        }

        String echoResult = "Echo: " + message;
        putLog("MyDemoSkill executed: message=" + message, LogLevel.DEBUG);

        return createMsg()
                .setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, echoResult)
                .setParam("echoMessage", message);
    }
}
