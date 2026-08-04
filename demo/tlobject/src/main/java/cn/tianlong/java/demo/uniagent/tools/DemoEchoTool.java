package cn.tianlong.java.demo.uniagent.tools;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.uniagent.*;

import java.util.*;

/**
 * Demo 工具：回显工具。接收任意文本输入并原样返回。
 * 用于验证 uniagent 的工具调用链路是否正常。
 */
public class DemoEchoTool extends TLBaseModule implements UniAgentParamString {

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String action = msg.getAction();
        if (action == null) return null;

        switch (action) {
            case AGENT_GETTOOLDEFS: return getToolDefs(fromWho, msg);
            case SKILL_EXECUTE:      return doExecute(fromWho, msg);
            case SKILL_VALIDATE:     return createMsg().setParam(RESULT, true); // always valid
            default: return null;
        }
    }

    @SuppressWarnings("unchecked")
    protected TLMsg getToolDefs(Object fromWho, TLMsg msg) {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> textProp = new LinkedHashMap<>();
        textProp.put("type", "string");
        textProp.put("description", "要回显的文本内容");
        textProp.put("required", true);
        props.put("text", textProp);

        TLFunctionDefinition def = TLFunctionDefinition.create(
                name, // tool name = module name
                "回显工具——将输入的文本原样返回。可用于测试工具调用链路。",
                props
        );

        List<TLFunctionDefinition> defs = new ArrayList<>();
        defs.add(def);
        return createMsg().setParam(AI_P_FUNCTIONDEFS, defs);
    }

    @SuppressWarnings("unchecked")
    protected TLMsg doExecute(Object fromWho, TLMsg msg) {
        Object input = msg.getParam(AI_P_SKILLINPUT);
        String text;

        if (input instanceof Map) {
            text = String.valueOf(((Map) input).get("text"));
        } else {
            text = String.valueOf(input);
        }

        putLog("EchoTool executed with: " + text, LogLevel.INFO);
        return createMsg().setParam(AI_P_SKILLOUTPUT, "[Echo] " + text);
    }
}
