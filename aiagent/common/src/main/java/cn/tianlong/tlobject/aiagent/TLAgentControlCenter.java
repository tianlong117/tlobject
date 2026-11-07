package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.TLControlCenter;

/**
 * AI Agent 应用的控制中心：core 通用控制机制之上的 AI 策略层。
 *
 * 通用机制（启动编排、注册门面、reload 门禁、审计）在 core 的 {@link TLControlCenter}；
 * 本类承载 AI 应用特有的控制策略：用户分级/命令授权、服务暂停语义、agent 健康与编排、
 * 审计事件推送等。一期先原样继承；后续策略覆写
 * {@code isReloadAllowed}/{@code audit}/{@code reloadModuleConfig} 等 protected 钩子，
 * 避免把应用策略塞回 core。
 */
public class TLAgentControlCenter extends TLControlCenter {

    public TLAgentControlCenter() {
        super();
    }

    public TLAgentControlCenter(String name) {
        super(name);
    }

    public TLAgentControlCenter(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }
}
