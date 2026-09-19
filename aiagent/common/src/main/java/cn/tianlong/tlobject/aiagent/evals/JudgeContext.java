package cn.tianlong.tlobject.aiagent.evals;

import cn.tianlong.tlobject.base.TLBaseModule;

/**
 * 评判上下文。
 *
 * 创建日期：2026/7/29
 * 作者:tianlong
 */
public class JudgeContext {
    public TLBaseModule evalsModule;
    public String judgeProviderName;
    /** 裁判调用用的推理模式（见 TLEvalsModule.judgeReasoningMode）；配错 provider 时可设 off 绕过 */
    public String judgeReasoningMode = "disabled";

    public JudgeContext(TLBaseModule evalsModule, String judgeProviderName) {
        this.evalsModule = evalsModule;
        this.judgeProviderName = judgeProviderName;
    }

    public TLBaseModule getEvalsModule() { return evalsModule; }
    public String getJudgeProviderName() { return judgeProviderName; }
}
