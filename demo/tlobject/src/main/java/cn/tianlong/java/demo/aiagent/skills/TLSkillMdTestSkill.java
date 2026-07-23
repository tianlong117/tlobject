package cn.tianlong.java.demo.aiagent.skills;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试 skillmd 自动发现——从 {configDir}skillmd/{skillName}.md 加载 skill 描述。
 * 函数名: skillmd_test
 * 功能: 简单 echo，验证框架自动从配置目录 skillmd/ 文件夹加载 md 文件。
 *
 * 输入参数:
 *   - message: 要回显的消息文本（必填）
 *
 * 输出: "SkillMdTest: {message}"
 *
 * 创建日期：2026/7/23
 * 作者:tianlong
 */
public class TLSkillMdTestSkill extends TLBaseSkill {

    public TLSkillMdTestSkill() {
        super();
    }

    public TLSkillMdTestSkill(String name) {
        super(name);
    }

    public TLSkillMdTestSkill(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();

        if (skillName == null || skillName.isEmpty()) {
            skillName = "skillmd_test";
        }
        // 不设置 skillDescription —— 全部由 loadSkillMd() 从 skillmd/ 自动发现注入

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> messageProp = new LinkedHashMap<>();
            messageProp.put("type", "string");
            messageProp.put("description", "要回显的消息文本");
            messageProp.put("required", true);
            parameterSchema.put("message", messageProp);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        String message = (String) input.getOrDefault("message", "");

        if (message.isEmpty() && msg.containsParam("message")) {
            message = msg.getStringParam("message", "");
        }

        if (message.isEmpty()) {
            return createMsg()
                    .setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: 'message' parameter is required for skillmd_test");
        }

        String result = "SkillMdTest: " + message;
        putLog("TLSkillMdTestSkill executed: message=" + message, LogLevel.DEBUG);

        return createMsg()
                .setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, result)
                .setParam("echoMessage", message);
    }
}
