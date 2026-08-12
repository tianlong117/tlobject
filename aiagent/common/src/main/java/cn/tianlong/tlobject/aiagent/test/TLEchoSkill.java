package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 测试用 Echo Skill：将输入原样返回。
 * 用于验证 TLToolExecutor 单次/并行工具执行。
 *
 * <h3>输入参数</h3>
 * <ul>
 *   <li>skillInput / message — 要回显的文本（默认 "echo"）</li>
 * </ul>
 *
 * 创建日期：2026/8/12
 * 作者：tianlong
 */
public class TLEchoSkill extends TLBaseModule implements TLAiAgentParamString {

    public TLEchoSkill() { super(); }
    public TLEchoSkill(String name) { super(name); }
    public TLEchoSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case SKILL_EXECUTE:
                returnMsg = doEcho(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    private TLMsg doEcho(Object fromWho, TLMsg msg) {
        String input = msg.getStringParam(AI_P_SKILLINPUT, null);
        if (input == null || input.isEmpty()) {
            input = msg.getStringParam("message", "echo");
        }
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, "ECHO: " + input);
    }
}
