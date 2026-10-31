package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 输出校验测试用技能：返回 RESULT=true + 非数字 total（"abc"）。
 * 用于验证 TLToolExecutor → outputValidator 的客观校验接线。
 */
public class TLBadOutputSkill extends TLBaseSkill {

    public TLBadOutputSkill() { super(); }
    public TLBadOutputSkill(String name) { super(name); }
    public TLBadOutputSkill(String name, TLObjectFactory factory) { super(name, factory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "测试用：返回非法 total 的工具输出";
    }

    @Override
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, "bad output")
                .setParam("total", "abc")
                .setParam("quantity", 1)
                .setParam("unitPrice", 2.0);
    }
}
