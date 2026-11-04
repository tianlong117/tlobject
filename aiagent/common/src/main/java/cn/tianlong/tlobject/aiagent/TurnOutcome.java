package cn.tianlong.tlobject.aiagent;

/**
 * 一轮的显式终态，替代 runChatLoop 里的 9 个 boolean 组合。
 * 每个 break 点构造一个 outcome，交 TurnCommitter 统一收尾。
 */
sealed interface TurnOutcome {

    String ABORTED_TEXT = "⏹ 已中断";

    /** 一轮 token 用量快照 */
    record Tokens(int prompt, int completion, int total,
                  int cacheCreate, int cacheHit, int cacheMiss) {
    }

    record Completed(String response, int iterations, Tokens tokens,
                     String reasoning, int finalAssistantIdx) implements TurnOutcome {
    }

    record Aborted(String reason, boolean byUser, Tokens tokens, int iterations)
            implements TurnOutcome {
    }

    record Rejected(String reason, String response, int iterations, Tokens tokens,
                    int finalAssistantIdx) implements TurnOutcome {
    }

    record PendingApproval(String approvalId, String pendingToolCallId, String response,
                           int iterations, Tokens tokens) implements TurnOutcome {
    }

    record Clarified(String question, String response, int iterations, Tokens tokens,
                     int finalAssistantIdx) implements TurnOutcome {
    }

    record Timeout(String response, int iterations, Tokens tokens) implements TurnOutcome {
    }

    record Truncated(String response, int iterations, Tokens tokens) implements TurnOutcome {
    }

    record DirectAnswer(String response, int iterations, Tokens tokens,
                        int finalAssistantIdx) implements TurnOutcome {
    }

    record Failed(String error) implements TurnOutcome {
    }

    record EarlyReturn(String response, boolean asFinal) implements TurnOutcome {
    }

    /** 面向调用方/UI 的终答文本（不含 guard 处理）。 */
    default String response() {
        if (this instanceof Completed c) return c.response();
        if (this instanceof Aborted) return ABORTED_TEXT;
        if (this instanceof Rejected r) return r.response();
        if (this instanceof PendingApproval p) return p.response();
        if (this instanceof Clarified c) return c.response();
        if (this instanceof Timeout t) return t.response();
        if (this instanceof Truncated t) return t.response();
        if (this instanceof DirectAnswer d) return d.response();
        if (this instanceof EarlyReturn e) return e.response();
        if (this instanceof Failed f) return f.error();
        return "";
    }

    /** token 快照；无 LLM 调用的终态返回 null。 */
    default Tokens tokens() {
        if (this instanceof Completed c) return c.tokens();
        if (this instanceof Aborted a) return a.tokens();
        if (this instanceof Rejected r) return r.tokens();
        if (this instanceof PendingApproval p) return p.tokens();
        if (this instanceof Clarified c) return c.tokens();
        if (this instanceof Timeout t) return t.tokens();
        if (this instanceof Truncated t) return t.tokens();
        if (this instanceof DirectAnswer d) return d.tokens();
        return null;
    }
}
