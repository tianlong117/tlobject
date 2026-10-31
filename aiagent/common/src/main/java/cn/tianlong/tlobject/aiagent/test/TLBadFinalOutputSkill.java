package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 直出（final）输出校验测试技能：RESULT=true + AI_P_FINALANSWER=true + 非数字 total。
 * 用于验证 final 结果也会被 outputValidator 校验，且失败时仍按直出短路、不调 LLM。
 */
public class TLBadFinalOutputSkill extends TLBaseSkill {

    public TLBadFinalOutputSkill() { super(); }
    public TLBadFinalOutputSkill(String name) { super(name); }
    public TLBadFinalOutputSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "测试用：直出且返回非法 total";
    }

    @Override
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, "bad final output")
                .setParam(AI_P_FINALANSWER, true)
                .setParam("total", "abc")
                .setParam("quantity", 1)
                .setParam("unitPrice", 2.0);
    }
}
