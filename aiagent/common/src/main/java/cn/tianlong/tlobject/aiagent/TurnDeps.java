package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLObjectFactory;

/** 一轮执行所需的服务引用；全部由 Agent 注入，Turn 不创建模块。 */
final class TurnDeps {

    final TLObjectFactory factory;
    /** 工具进度透传目标（父 Agent） */
    final IObject parentAgent;
    final IObject llmProvider;
    final IObject toolManager;
    final IObject toolExecutor;
    final TurnViewBuilder viewBuilder;
    final TurnCommitter committer;
    final TurnObserver observer;
    final AttachmentPipeline attachments;
    final ToolResultAppender appender;
    final StreamHandleRegistry streams;

    TurnDeps(TLObjectFactory factory, IObject parentAgent,
             IObject llmProvider, IObject toolManager, IObject toolExecutor,
             TurnViewBuilder viewBuilder, TurnCommitter committer, TurnObserver observer,
             AttachmentPipeline attachments, ToolResultAppender appender,
             StreamHandleRegistry streams) {
        this.factory = factory;
        this.parentAgent = parentAgent;
        this.llmProvider = llmProvider;
        this.toolManager = toolManager;
        this.toolExecutor = toolExecutor;
        this.viewBuilder = viewBuilder;
        this.committer = committer;
        this.observer = observer;
        this.attachments = attachments;
        this.appender = appender;
        this.streams = streams;
    }
}
