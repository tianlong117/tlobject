package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.TLAgentGroup;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.HashMap;

/** /test 用组：暴露 protected 的 chat/registerMember，测试直接构造，无需配置文件。 */
public class TLTestAgentGroup extends TLAgentGroup {

    public TLTestAgentGroup(String name, TLObjectFactory factory) {
        super(name, factory);
    }

    public TLMsg testChat(TLMsg msg) {
        return chat(null, msg);
    }

    public TLMsg testRegister(String memberName, HashMap<String, String> cfg) {
        return registerMember(null, createMsg().setAction(AGENT_REGISTERAGENT)
                .setParam(AI_P_AGENTNAME, memberName)
                .setParam(AI_P_AGENTCONFIG, cfg));
    }
}
