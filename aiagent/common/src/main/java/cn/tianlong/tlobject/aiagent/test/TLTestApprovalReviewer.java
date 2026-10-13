package cn.tianlong.tlobject.aiagent.test;

import cn.tianlong.tlobject.aiagent.approval.IApprovalReviewer;
import cn.tianlong.tlobject.aiagent.approval.TLApprovalModule;
import cn.tianlong.tlobject.aiagent.approval.TLApprovalRequest;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用审查人：默认不阻塞、立即返回 pending（异步语义），并记录收到的全部请求供场景断言。
 * {@code setBlockMode(true)} 切换为阻塞模式：委托 {@link TLApprovalModule#waitForDecision}
 * 复刻生产 ConsoleReviewer 的阻塞 latch 路径（场景 19 唤醒 / 20 自超时才有覆盖）。
 * 由 aitest 的 approvalGate_config.xml 以 reviewerClass 指定；
 * 生产配置（ConsoleReviewer）下审批场景自动跳过（见 TLAgentTestModule）。
 *
 * 创建日期：2026/10/12
 * 作者：tianlong
 */
public class TLTestApprovalReviewer implements IApprovalReviewer {

    private static final List<TLApprovalRequest> received =
            Collections.synchronizedList(new ArrayList<TLApprovalRequest>());

    /** 复位：清记录并保证不残留阻塞模式（场景异常中断时 finally 可能没跑到，后续场景会误走阻塞路径） */
    public static void reset() { received.clear(); blockMode = false; }
    public static int count() { return received.size(); }

    public static List<TLApprovalRequest> snapshot() {
        synchronized (received) { return new ArrayList<>(received); }
    }

    /** 所属审批模块（owner 注入后 blockMode 才有意义） */
    private TLApprovalModule owner;
    public void setOwner(TLApprovalModule owner) { this.owner = owner; }

    /** 阻塞模式：true 时委托 owner.waitForDecision（复刻生产 ConsoleReviewer 的阻塞路径） */
    private static volatile boolean blockMode = false;
    public static void setBlockMode(boolean on) { blockMode = on; }

    @Override
    public TLMsg requestApproval(TLApprovalRequest request) {
        received.add(request);
        if (blockMode && owner != null) {
            return owner.waitForDecision(request);
        }
        return new TLMsg().setParam("_approvalState", TLApprovalRequest.PENDING);
    }
}
