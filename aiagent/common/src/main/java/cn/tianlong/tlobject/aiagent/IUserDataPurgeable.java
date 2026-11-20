package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

/**
 * 用户数据级联清理能力。
 *
 * <p>由持有用户运行态或数据的模块实现（TLAiAgent / TLScheduleTaskSkill / TLBaseMemory 及其子类）。
 * 编排器（TLUserDataPurger）通过控制中心注册表发现所有实现实例，按 phase 投递同一请求：
 * {@code phase=tasks|runtime|memory}。不支持的 phase 返回 {@code RESULT=true, skipped=true}。
 * 实现必须幂等：重复调用不报错，只返回本次实际清理计数。</p>
 */
public interface IUserDataPurgeable {
    TLMsg purgeUserData(TLMsg request);
}
