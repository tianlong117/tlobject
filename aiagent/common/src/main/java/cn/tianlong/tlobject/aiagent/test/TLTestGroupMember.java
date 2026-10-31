package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.IAgentCapable;
import cn.tianlong.tlobject.aiagent.TLAiAgentParamString;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDataUtils;

/**
 * 组进度测试用假成员：不经 LLM，按 params 返回成功/失败/监理 retry JSON。
 * params: fail=true / failText=失败响应文本 / sleepMs=n / retryOnce=true / retryMember=名字 / finalText=文本。
 */
public class TLTestGroupMember extends TLBaseModule implements IAgentCapable, TLAiAgentParamString {

    private boolean fail;
    private String failText;
    private long sleepMs;
    private boolean retryOnce;
    private String retryMember;
    private String finalText;
    private int calls;

    public TLTestGroupMember() { super(); }
    public TLTestGroupMember(String name) { super(name); }
    public TLTestGroupMember(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected void setModuleParams() {
        if (params == null) return;
        fail = TLDataUtils.parseBoolean(params.get("fail"), false);
        failText = params.get("failText");
        sleepMs = params.get("sleepMs") != null ? Long.parseLong(params.get("sleepMs")) : 0L;
        retryOnce = TLDataUtils.parseBoolean(params.get("retryOnce"), false);
        retryMember = params.get("retryMember");
        finalText = params.get("finalText");
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if (!AGENT_CHAT.equals(msg.getAction())) return null;
        if (sleepMs > 0) {
            try { Thread.sleep(sleepMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        int call;
        synchronized (this) { call = ++calls; }
        if (fail)
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_RESPONSE, failText != null ? failText : "mock failure");
        if (retryOnce && call == 1 && retryMember != null)
            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_RESPONSE,
                            "{\"retry\":{\"" + retryMember + "\":\"请补充\"}}");
        String text = finalText != null ? finalText : (getName() + " ok");
        return createMsg().setParam(RESULT, true).setParam(AI_P_RESPONSE, text);
    }
}
