package cn.tianlong.tlobject.aiagent;

/** 一轮的身份字段；会话/轮次/用户隔离都从这里取。 */
record TurnIdentity(String sessionId, String rootSessionId, String roundId,
                    String userId, String agentName) {
}
