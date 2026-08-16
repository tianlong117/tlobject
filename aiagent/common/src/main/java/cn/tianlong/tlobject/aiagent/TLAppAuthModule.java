package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.util.*;

/**
 * 工具授权桥接模块（框架级）—— 在 toolExecBatch 执行前检查每个工具的授权，
 * 拒绝的从 tasks 列表移除；执行后补回 denied 结果。
 *
 * <p>通过 before/afterMsgTable 挂钩到 TLToolExecutor.toolExecBatch：</p>
 * <pre>
 * &lt;beforeMsgTable&gt;
 *     &lt;action name="toolExecBatch"&gt;
 *         &lt;msg action="authForBatch" destination="appAuthModule" useInputMsg="true"/&gt;
 *     &lt;/action&gt;
 * &lt;/beforeMsgTable&gt;
 * &lt;afterMsgTable&gt;
 *     &lt;action name="toolExecBatch"&gt;
 *         &lt;msg action="fillDenied" destination="appAuthModule" useInputMsg="true"/&gt;
 *     &lt;/action&gt;
 * &lt;/afterMsgTable&gt;
 * </pre>
 *
 * <p>参数：</p>
 * <ul>
 *   <li>userModule — 用户信息模块名（契约：action=getUserInfo, 入参 userId, 出参 userid/role/group）</li>
 *   <li>authModule  — 授权模块名，默认 "auth"</li>
 * </ul>
 */
public class TLAppAuthModule extends TLBaseModule {

    private String userModule;
    private String authModule = "auth";

    public TLAppAuthModule() { super(); }
    public TLAppAuthModule(String name) { super(name); }
    public TLAppAuthModule(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null) {
            userModule = params.get("userModule");
            if (params.get("authModule") != null)
                authModule = params.get("authModule");
        }
    }

    @Override
    protected TLBaseModule init() { return this; }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        switch (msg.getAction()) {
            case "authForBatch":
                return doAuthForBatch(fromWho, msg);
            case "fillDenied":
                return doFillDenied(fromWho, msg);
            default:
                return null;
        }
    }

    // ======================== before 钩子：过滤未授权工具 ========================

    @SuppressWarnings("unchecked")
    private TLMsg doAuthForBatch(Object fromWho, TLMsg msg) {
        TLMsg originalMsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
        if (originalMsg == null) return null;
        List<TLToolExecutor.ToolTask> tasks = (List<TLToolExecutor.ToolTask>) originalMsg.getListParam("tasks", null);
        if (tasks == null || tasks.isEmpty())
            return null;

        List<Map<String, String>> deniedList = new ArrayList<>();

        // userId 从原消息（TODOOLEXECUTE）读——消息流里一直带会话/用户 id，一批任务同一用户，
        // 不必存放在 Task 上（executeTools 也从同一消息取 userId）
        String userId = String.valueOf(originalMsg.getSystemParam("userId", "default"));

        Iterator<TLToolExecutor.ToolTask> it = tasks.iterator();
        while (it.hasNext()) {
            TLToolExecutor.ToolTask task = it.next();
            // 内建工具（无目标模块）跳过授权
            if (task.module == null || task.moduleName == null || task.moduleName.isEmpty())
                continue;

            // 1. 获取用户信息
            TLMsg userInfo = resolveUserInfo(userId);
            if (userInfo == null) {
                putLog("authForBatch: cannot resolve user info for " + userId + ", skip auth", LogLevel.WARN, "appAuth");
                continue;
            }

            // 2. 调 TLAuthModule 做授权（去掉 _msgId_: 前缀）
            String checkAction = task.action;
            if (checkAction != null && checkAction.startsWith("_msgId_:"))
                checkAction = checkAction.substring("_msgId_:".length());
            TLMsg authMsg = createMsg()
                    .setAction("authForModule")
                    .setParam("checkModule", task.moduleName)
                    .setParam("checkAction", checkAction)
                    .setParam("userid", userInfo.getParam("userid"))
                    .setParam("role", userInfo.getParam("role"))
                    .setParam("group", userInfo.getParam("group"));

            TLMsg authResult = putMsg(authModule, authMsg);

            // 3. 拒绝 → 移除
            if (authResult != null && Boolean.FALSE.equals(authResult.getSystemParam("donextMsg"))) {
                putLog("authForBatch: DENIED module=" + task.moduleName + " action=" + task.action
                        + " userId=" + userId
                        + " reason=" + authResult.getParam("denyType") + "/" + authResult.getParam("denyValue"),
                        LogLevel.WARN, "appAuth");
                Map<String, String> denied = new HashMap<>();
                denied.put("toolCallId", task.toolCallId);
                denied.put("denyType", (String) authResult.getParam("denyType"));
                denied.put("denyValue", (String) authResult.getParam("denyValue"));
                deniedList.add(denied);
                it.remove();
            } else {
                putLog("authForBatch: PASS module=" + task.moduleName + " action=" + task.action
                        + " userId=" + userId, LogLevel.DEBUG, "appAuth");
            }
        }

        // 暂存到原始 msg 系统参数，供 after 钩子读取
        if (!deniedList.isEmpty()) {
            originalMsg.setSystemParam("_deniedTasks", deniedList);
            putLog("authForBatch: denied " + deniedList.size() + " tool(s)", LogLevel.INFO, "appAuth");
        }
        return null;
    }

    /** 调 userModule 获取用户角色/分组 */
    private TLMsg resolveUserInfo(String userId) {
        if (userModule == null || userModule.isEmpty())
            return null;
        TLMsg result = putMsg(userModule, createMsg().setAction("getUserInfo").setParam("userId", userId));
        if (result == null) return null;
        // 确保有 userid 字段
        if (result.getParam("userid") == null)
            result.setParam("userid", userId);
        return result;
    }

    // ======================== after 钩子：补 denied 结果 ========================

    @SuppressWarnings("unchecked")
    private TLMsg doFillDenied(Object fromWho, TLMsg msg) {
        // executeTools 的返回值在 PRERESULT 里
        TLMsg execResult = (TLMsg) msg.getSystemParam(PRERESULT);
        if (execResult == null)
            return null;

        // 从原始 msg（DOWITHMSG）读取 before 钩子暂存的拒绝列表
        TLMsg originalMsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
        List<Map<String, String>> deniedList = originalMsg != null
                ? (List<Map<String, String>>) originalMsg.getSystemParam("_deniedTasks") : null;
        if (deniedList == null || deniedList.isEmpty())
            return execResult;  // 无拒绝，返回原始结果

        // 追加 denied 结果（重建为可修改列表）
        List<TLToolExecutor.ToolResult> results = (List<TLToolExecutor.ToolResult>) execResult.getListParam("results", null);
        List<TLToolExecutor.ToolResult> mutableResults = results != null
                ? new ArrayList<>(results) : new ArrayList<>();
        execResult.setParam("results", mutableResults);

        for (Map<String, String> denied : deniedList) {
            TLToolExecutor.ToolResult tr = new TLToolExecutor.ToolResult(
                    denied.get("toolCallId"),
                    "Tool denied: " + denied.get("denyType") + " - " + denied.get("denyValue"));
            tr.state = "denied";
            tr.stateExtra = denied.get("denyType") + ": " + denied.get("denyValue");
            mutableResults.add(tr);
        }

        putLog("fillDenied: added " + deniedList.size() + " denied result(s), total=" + mutableResults.size(), LogLevel.INFO, "appAuth");
        return execResult;
    }
}
