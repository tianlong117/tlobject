package cn.tianlong.java.demo.uniagent.tools;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.uniagent.*;

import java.util.*;

/**
 * Demo 工具：简单计算器。支持加减乘除运算。
 */
public class DemoCalculatorTool extends TLBaseModule implements UniAgentParamString {

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
            case SKILL_VALIDATE:     return createMsg().setParam(RESULT, true);
            default: return null;
        }
    }

    @SuppressWarnings("unchecked")
    protected TLMsg getToolDefs(Object fromWho, TLMsg msg) {
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> opProp = new LinkedHashMap<>();
        opProp.put("type", "string");
        opProp.put("description", "运算类型: add, subtract, multiply, divide");
        opProp.put("enum", Arrays.asList("add", "subtract", "multiply", "divide"));
        opProp.put("required", true);
        props.put("operation", opProp);

        Map<String, Object> aProp = new LinkedHashMap<>();
        aProp.put("type", "number");
        aProp.put("description", "第一个操作数");
        aProp.put("required", true);
        props.put("a", aProp);

        Map<String, Object> bProp = new LinkedHashMap<>();
        bProp.put("type", "number");
        bProp.put("description", "第二个操作数");
        bProp.put("required", true);
        props.put("b", bProp);

        TLFunctionDefinition def = TLFunctionDefinition.create(
                name,
                "简单计算器——执行基础数学运算 (add/subtract/multiply/divide)。",
                props
        );

        List<TLFunctionDefinition> defs = new ArrayList<>();
        defs.add(def);
        return createMsg().setParam(AI_P_FUNCTIONDEFS, defs);
    }

    @SuppressWarnings("unchecked")
    protected TLMsg doExecute(Object fromWho, TLMsg msg) {
        Object input = msg.getParam(AI_P_SKILLINPUT);
        Map<String, Object> args;

        if (input instanceof Map) {
            args = (Map<String, Object>) input;
        } else {
            return createMsg().setParam(AI_P_SKILLOUTPUT, "Error: expected JSON object with operation/a/b");
        }

        String op = String.valueOf(args.getOrDefault("operation", ""));
        double a = toDouble(args.get("a"));
        double b = toDouble(args.get("b"));
        double result;

        switch (op) {
            case "add":      result = a + b; break;
            case "subtract": result = a - b; break;
            case "multiply": result = a * b; break;
            case "divide":
                if (b == 0) return createMsg().setParam(AI_P_SKILLOUTPUT, "Error: division by zero");
                result = a / b;
                break;
            default:
                return createMsg().setParam(AI_P_SKILLOUTPUT, "Error: unknown operation '" + op + "'");
        }

        String output = String.format("%.2f %s %.2f = %.4f", a,
                op.equals("add") ? "+" : op.equals("subtract") ? "-" :
                op.equals("multiply") ? "×" : "÷", b, result);

        putLog("CalculatorTool: " + output, LogLevel.INFO);
        return createMsg().setParam(AI_P_SKILLOUTPUT, output);
    }

    private double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) return Double.parseDouble((String) v);
        return 0;
    }
}
