package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 测试用 Sleep Skill：按指定时长阻塞后返回。
 * 用于验证 TLToolExecutor 单任务超时、批次超时机制。
 *
 * <h3>输入参数</h3>
 * <ul>
 *   <li>duration — 睡眠时长（毫秒），默认 100</li>
 * </ul>
 *
 * 创建日期：2026/8/12
 * 作者：tianlong
 */
public class TLSleepSkill extends TLBaseModule implements TLAiAgentParamString {

    public TLSleepSkill() { super(); }
    public TLSleepSkill(String name) { super(name); }
    public TLSleepSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case SKILL_EXECUTE:
                returnMsg = doSleep(fromWho, msg);
                break;
            default:
                returnMsg = null;
        }
        return returnMsg;
    }

    private TLMsg doSleep(Object fromWho, TLMsg msg) {
        int duration = msg.getIntParam("duration", 100);
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "sleep interrupted after " + duration + "ms");
        }
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, "slept " + duration + "ms");
    }
}
