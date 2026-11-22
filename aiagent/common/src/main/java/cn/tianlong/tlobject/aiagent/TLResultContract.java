package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

/**
 * 工具/子 Agent 边界的结构化结果契约。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@code RESULT} 是成功标志；缺失时按成功处理（兼容不设 RESULT 的旧技能）。</li>
 *   <li>失败时边界消息带 {@code errorCode}：子结果已有显式码优先，否则用调用方给的
 *       fallback（agent_error / group_error / workflow_error / tool_error）。</li>
 *   <li>LLM 可见的 {@code skillOutput/aiResponse} 文本不改写——结构化字段只供框架
 *       编排、排障与 trace 使用。</li>
 * </ul>
 *
 * <p>本类只做无副作用的消息字段拷贝，便于单元测试与三处边界（Agent / Group / Workflow）
 * 共用同一语义，避免各处手写判断漂移。</p>
 */
public final class TLResultContract implements TLAiAgentParamString {

    private TLResultContract() {}

    /** 子结果是否成功：null=false；缺 RESULT=true（旧技能兼容）。 */
    public static boolean succeeded(TLMsg result) {
        return result != null && result.parseBoolean(RESULT, true);
    }

    /**
     * 把子结果的成功状态透传到边界返回消息：设置 RESULT；
     * 失败时带 errorCode（子结果显式码优先，否则 fallbackCode）。
     */
    public static TLMsg apply(TLMsg child, TLMsg ret, String fallbackErrorCode) {
        boolean ok = succeeded(child);
        ret.setParam(RESULT, ok);
        String code = errorCode(child, "");
        if (!ok) {
            ret.setParam(AI_P_ERRORCODE,
                    code.isEmpty() ? safe(fallbackErrorCode) : code);
        } else if (!code.isEmpty()) {
            ret.setParam(AI_P_ERRORCODE, code);
        }
        return ret;
    }

    /** 取结构化错误码；result 为空/未带时返回 fallback（null 归一为空串）。 */
    public static String errorCode(TLMsg result, String fallback) {
        String code = result != null ? result.getStringParam(AI_P_ERRORCODE, "") : "";
        return code == null || code.isEmpty() ? safe(fallback) : code;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
