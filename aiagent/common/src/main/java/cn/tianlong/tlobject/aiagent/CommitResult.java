package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

/** commit 的结果：给调用方的返回消息 + 护栏后的最终文本（用于发终帧）。 */
final class CommitResult {
    final TLMsg returnMsg;
    final String finalText;

    CommitResult(TLMsg returnMsg, String finalText) {
        this.returnMsg = returnMsg;
        this.finalText = finalText;
    }
}
