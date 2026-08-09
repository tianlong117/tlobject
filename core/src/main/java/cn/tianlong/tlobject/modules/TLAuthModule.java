package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.xmlpull.v1.XmlPullParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统级认证/授权模块 — 基于策略引擎的访问控制。
 * <p>
 * 作为 beforeMsgTable 钩子使用，是无侵入网关：通过返回 null，拒绝返回 MODULE_DONEXTMSG=false。
 * 不修改被检查的业务消息（dmsg）。
 * </p>
 *
 * <p><b>使用方式：</b></p>
 * <pre>
 * // 用户状态放在被检查的业务消息（dmsg）上，零外部依赖
 * TLMsg dmsg = createMsg()
 *     .setAction("someAction")
 *     .setDestination("targetModule")
 *     .setParam("userid", "zhangsan")
 *     .setParam("role", Arrays.asList("member"))
 *     .setParam("group", "159")
 *     .setParam("clientIP", "10.0.0.1");
 *
 * putMsg("auth", createMsg()
 *     .setAction("authInModule")
 *     .setSystemParam(DOWITHMSG, dmsg));
 * </pre>
 *
 * 作者: tianlong
 */
public class TLAuthModule extends TLBaseModule {

    // ======================== 自包含常量 ========================

    /** 模块名常量 */
    static final String M_DIRECTOUTNTERFACE = "directOutInterface";

    /** 输出接口常量 */
    static final String OUTINTERFACE_PUTCONTENTTOUSER = "putContentToUser";
    static final String CLIENT_P_CONTENT = "content";

    /** 认证动作 */
    static final String AUTH_AUTHINURLMAP = "authInUrlMap";
    static final String AUTH_AUTHINMODULE = "authInModule";
    static final String AUTH_AUTHTAG = "authTag";
    static final String AUTH_SETPOLICY = "setProliy";
    static final String AUTH_GETPOLICY = "getProliy";
    static final String AUTH_DELETEPOLICY = "deleteProliy";

    /** 认证参数 key */
    static final String AUTH_P_TAG = "tag";
    static final String AUTH_P_POLICY = "policy";
    static final String AUTH_P_POLICYNAME = "policyName";
    static final String AUTH_P_POLICYVALUE = "policyValue";
    static final String AUTH_P_DEFAULTSUPERUSER = "defaultSuperUser";
    static final String AUTH_P_DEFAULTSUPERROLE = "defaultSuperRole";
    /** 调用方通过消息传入的客户端 IP */
    static final String AUTH_P_CLIENTIP = "clientIP";

    /** 用户状态 */
    static final String USER_P_USERID = "userid";
    static final String USER_P_ROLE = "role";
    static final String USER_P_GROUP = "group";
    static final String USER_V_ROLE_GUEST = "guest";

    // ======================== 字段 ========================

    protected String msgNameForCheck = "domsg";
    protected String defaultPolicy;
    protected String defaultDenyMsg;
    protected String defaultSuperUser;
    protected String defaultSuperRole;
    protected ConcurrentHashMap<String, HashMap<String, String>> authModules;
    protected ConcurrentHashMap<String, HashMap<String, String>> policies;
    protected ConcurrentHashMap<String, HashMap<String, Object>> policiesSet = new ConcurrentHashMap<>();
    protected ConcurrentHashMap<String, HashMap<String, String>> authMsgids;
    /** BugFix: 改为 ConcurrentHashMap 保证线程安全 */
    protected ConcurrentHashMap<String, HashMap<String, String>> authTags;
    /** 审计模块名，未配置则审计关闭 */
    protected String auditModule;

    // ======================== 构造器 ========================

    public TLAuthModule() {
        super();
    }

    public TLAuthModule(String name) {
        super(name);
    }

    public TLAuthModule(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    // ======================== 生命周期 ========================

    @Override
    protected void setModuleParams() {
        if (params != null && params.get("fieldsInModule") != null) {
            Map configPolicies = ((myConfig) mconfig).getPolicies();
            if (configPolicies != null) {
                if (policies != null)
                    policies.putAll(configPolicies);
            }
        }
    }

    @Override
    protected TLBaseModule init() {
        makePolicy();
        return this;
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null && params.get("msgNameForCheck") != null)
            msgNameForCheck = params.get("msgNameForCheck");
        defaultPolicy = params.get("defaultPolicy");
        defaultDenyMsg = params.get("defaultDenyMsg");
        defaultSuperUser = params.get(AUTH_P_DEFAULTSUPERUSER);
        defaultSuperRole = params.get(AUTH_P_DEFAULTSUPERROLE);
        auditModule = params.get("auditModule");
    }

    // ======================== 策略编译 ========================

    protected void makePolicy() {
        if (policies == null)
            return;
        for (String policyName : policies.keySet()) {
            addParentPolicy(policyName);
        }
        for (String policyName : policies.keySet()) {
            HashMap<String, String> policy = policies.get(policyName);
            HashMap<String, Object> newMap = policyToSet(policy);
            policiesSet.put(policyName, newMap);
        }
    }

    private HashMap<String, Object> policyToSet(HashMap<String, String> policy) {
        HashMap<String, Object> newMap = new HashMap<>();
        for (String key : policy.keySet()) {
            String value = policy.get(key).trim();
            if (value == null || value.isEmpty())
                continue;
            if (key.equals("role") || key.equals("user") || key.equals("group")) {
                String[] valueArray = TLDataUtils.splitStrToArray(value, ";");
                HashSet<String> set = new HashSet<>(Arrays.asList(valueArray));
                newMap.put(key, set);
            } else if (key.equals("ip")) {
                String[] ipPolicy = value.split(";");
                newMap.put(key, ipPolicy);
            } else
                newMap.put(key, value);
        }
        return newMap;
    }

    protected HashMap<String, String> addParentPolicy(String policyName) {
        HashMap<String, String> policy = policies.get(policyName);
        String parentName = policy.get("parent");
        if (parentName == null || parentName.isEmpty())
            return policy;
        HashMap<String, String> parentPolicy = addParentPolicy(parentName);
        if (parentPolicy == null || parentPolicy.isEmpty())
            return policy;
        HashMap<String, String> tempMap = new HashMap<>();
        tempMap.putAll(parentPolicy);
        tempMap.putAll(policy);
        policy.putAll(tempMap);
        policy.remove("parent");
        return policy;
    }

    // ======================== 配置加载 ========================

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        Map configAuthModules = config.getAuthModules();
        if (configAuthModules != null) {
            authModules = new ConcurrentHashMap<>();
            authModules.putAll(configAuthModules);
        }
        Map configPolicies = config.getPolicies();
        if (configPolicies != null) {
            policies = new ConcurrentHashMap<>();
            policies.putAll(configPolicies);
        }
        Map configAuthMsgids = config.getAuthMsgids();
        if (configAuthMsgids != null) {
            authMsgids = new ConcurrentHashMap<>();
            authMsgids.putAll(configAuthMsgids);
        }
        // BugFix: authTags 防御性拷贝到 ConcurrentHashMap
        Map configAuthTags = config.getAuthTags();
        if (configAuthTags != null) {
            authTags = new ConcurrentHashMap<>();
            authTags.putAll(configAuthTags);
        } else {
            authTags = null;
        }
        return config;
    }

    protected HashMap<String, Object> getPolicies(String policy) {
        if (policy == null || policiesSet == null)
            return null;
        return policiesSet.get(policy);
    }

    // ======================== 消息分发 ========================

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case AUTH_AUTHINURLMAP:
                returnMsg = authInUrlMap(fromWho, msg);
                break;
            case AUTH_AUTHINMODULE:
                returnMsg = authInModule(fromWho, msg);
                break;
            case AUTH_AUTHTAG:
                returnMsg = authTag(fromWho, msg);
                break;
            case AUTH_SETPOLICY:
                setProliy(fromWho, msg);
                break;
            case AUTH_DELETEPOLICY:
                deleteProliy(fromWho, msg);
                break;
            case AUTH_GETPOLICY:
                returnMsg = getProliy(fromWho, msg);
                break;
            case "addProliy":
                addProliy(fromWho, msg);
                break;
            case "deny":
                deny(fromWho, msg);
                break;
            default:
        }
        return returnMsg;
    }

    // ======================== 运行时策略管理 ========================

    private void deny(Object fromWho, TLMsg msg) {
        String content = "no auth";
        TLMsg emsg = createMsg().setAction(OUTINTERFACE_PUTCONTENTTOUSER).setParam(CLIENT_P_CONTENT, content);
        putMsg(M_DIRECTOUTNTERFACE, emsg);
    }

    private void deleteProliy(Object fromWho, TLMsg msg) {
        String policyName = (String) msg.getParam(AUTH_P_POLICYNAME);
        if (policyName == null || policyName.isEmpty())
            return;
        policies.remove(policyName);
        policiesSet.remove(policyName);
    }

    /** BugFix: 返回防御性拷贝，避免暴露内部可变状态 */
    private TLMsg getProliy(Object fromWho, TLMsg msg) {
        String policyName = (String) msg.getParam(AUTH_P_POLICYNAME);
        if (policyName == null || policyName.isEmpty()) {
            if (policies != null)
                return createMsg().addMap(new HashMap<>(policies));
            return createMsg().addMap(new HashMap<>());
        }
        HashMap<String, String> policy = policies.get(policyName);
        if (policy != null)
            return createMsg().addMap(new HashMap<>(policy));
        return createMsg().addMap(new HashMap<>(policies));
    }

    /** BugFix: 添加 instanceof 类型检查 */
    private void setProliy(Object fromWho, TLMsg msg) {
        String policyName = (String) msg.getParam(AUTH_P_POLICYNAME);
        if (policyName == null || policyName.isEmpty())
            return;
        Object policyValue = msg.getParam(AUTH_P_POLICYVALUE);
        if (!(policyValue instanceof HashMap)) {
            putLog("setProliy: policyValue is not a HashMap, got "
                    + (policyValue != null ? policyValue.getClass().getName() : "null"), LogLevel.WARN, "auth");
            return;
        }
        HashMap<String, String> policy = (HashMap<String, String>) policyValue;
        policies.put(policyName, policy);
        HashMap<String, Object> newMap = policyToSet(policy);
        policiesSet.put(policyName, newMap);
    }

    private void addProliy(Object fromWho, TLMsg msg) {
        String policyName = (String) msg.getParam(AUTH_P_POLICYNAME);
        if (policyName == null || policyName.isEmpty())
            return;
        Object policyUnit = msg.getParam(AUTH_P_POLICYVALUE);
        if (policyUnit instanceof Map) {
            HashMap<String, String> policy = policies.get(policyName);
            if (policy == null)
                policies.put(policyName, (HashMap<String, String>) policyUnit);
            else
                policy.putAll((Map<? extends String, ? extends String>) policyUnit);
            policy = policies.get(policyName);
            HashMap<String, Object> newMap = policyToSet(policy);
            policiesSet.put(policyName, newMap);
        }
    }

    // ======================== 认证入口 ========================

    /**
     * 基于 tag 的认证检查。policy 可从消息中直接传入，或通过 tag 名查找。
     */
    protected TLMsg authTag(Object fromWho, TLMsg msg) {
        String policyName = null;
        HashMap<String, Object> policy = (HashMap<String, Object>) msg.getParam(AUTH_P_POLICY);
        if (policy == null) {
            policyName = (String) msg.getParam(AUTH_P_POLICYNAME);
            if (policyName == null || policyName.isEmpty()) {
                String tag = (String) msg.getParam(AUTH_P_TAG);
                policyName = getPolicyNameByTag(tag);
            }
            policy = getPolicies(policyName);
        }
        if (policy == null) {
            sendAudit("deny", new String[]{"policy", "no policy"},
                    null, "authTag", null, policyName, msg);
            if (auditModule == null || auditModule.isEmpty()) {
                StringBuilder logBuffer = new StringBuilder().append("没有对应认证策略:")
                        .append(AUTH_P_POLICYNAME).append((String) msg.getParam(AUTH_P_POLICYNAME))
                        .append(AUTH_P_TAG).append((String) msg.getParam(AUTH_P_TAG));
                putLog(logBuffer.toString(), LogLevel.ERROR, "auth");
            }
            return createMsg()
                    .setSystemParam(MODULE_DONEXTMSG, false)
                    .setParam("resultFrom", "auth")
                    .setParam("denyType", "policy")
                    .setParam("denyValue", "no policy");
        }
        if (policy.isEmpty()) {
            sendAudit("pass", null, null, "authTag", null, policyName, msg);
            return null;
        }
        String[] policyResult = checkPolicy(policy, msg);
        if (policyResult != null) {
            sendAudit("deny", policyResult, null, "authTag", null, policyName, msg);
            if (auditModule == null || auditModule.isEmpty()) {
                String checkModule = ((IObject) fromWho).getName();
                StringBuilder logBuffer = new StringBuilder().append("模块: ").append(checkModule)
                        .append(AUTH_P_POLICYNAME).append((String) msg.getParam(AUTH_P_POLICYNAME))
                        .append(AUTH_P_TAG).append((String) msg.getParam(AUTH_P_TAG));
                logBuffer.append("访问拒绝");
                putLog(logBuffer.toString(), LogLevel.DEBUG, "auth");
            }
            String denyMsgId = getDenyMsg(null, null, (String) msg.getParam(AUTH_P_TAG), policy);
            if (denyMsgId != null && !denyMsgId.isEmpty())
                putMsg(this, createMsg().setMsgId(denyMsgId).setParam("type", policyResult[0])
                        .setParam("value", policyResult[1]));
            return createMsg()
                    .setSystemParam(MODULE_DONEXTMSG, false)
                    .setParam("resultFrom", "auth")
                    .setParam("denyType", policyResult[0])
                    .setParam("denyValue", policyResult.length > 1 ? policyResult[1] : "");
        }
        sendAudit("pass", null, null, "authTag", null, policyName, msg);
        return null;
    }

    /**
     * 模块级认证（beforeMsgTable 钩子）。
     * 被检查模块 = 调用方名称；被检查消息 = DOWITHMSG。
     * 用户状态从被检查的业务消息 dmsg 中获取。
     */
    protected TLMsg authInModule(Object fromWho, TLMsg msg) {
        TLMsg dmsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
        if (dmsg == null)
            return createMsg().setSystemParam(MODULE_DONEXTMSG, false);
        String checkModule = ((IObject) fromWho).getName();
        return authForMsg(checkModule, dmsg);
    }

    /**
     * URL 映射级认证（beforeMsgTable 钩子）。
     * 被检查模块 = dmsg.getDestination()；被检查消息 = DOWITHMSG → msgNameForCheck。
     * 用户状态从被检查的业务消息 dmsg 中获取。
     *
     * BugFix: 添加 doWithmsg null 守卫
     */
    protected TLMsg authInUrlMap(Object fromWho, TLMsg msg) {
        TLMsg doWithmsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
        if (doWithmsg == null)
            return createMsg().setSystemParam(MODULE_DONEXTMSG, false);
        TLMsg dmsg = (TLMsg) doWithmsg.getParam(msgNameForCheck);
        if (dmsg == null)
            return createMsg().setSystemParam(MODULE_DONEXTMSG, false);
        String checkModule = dmsg.getDestination();
        return authForMsg(checkModule, dmsg);
    }

    /**
     * 从业务消息中提取策略查找信息和用户状态。
     *
     * @param checkModule 被检查的模块名
     * @param dmsg        被检查的业务消息（携带 action/msgid 用于策略查找，以及 userid/role/group/clientIP 用户状态）
     */
    protected TLMsg authForMsg(String checkModule, TLMsg dmsg) {
        String checkAction = dmsg.getAction();
        String checkMsgid = dmsg.getMsgId();
        return auth(checkModule, checkAction, checkMsgid, dmsg);
    }

    /**
     * 核心认证逻辑：查找策略 → 评估策略。
     *
     * @param checkModule 被检查模块名
     * @param checkAction 被检查动作
     * @param checkMsgid  被检查消息 ID
     * @param dmsg        被检查的业务消息（携带用户状态）
     */
    protected TLMsg auth(String checkModule, String checkAction, String checkMsgid, TLMsg dmsg) {
        String policyName;
        if (checkModule != null && !checkModule.isEmpty())
            policyName = getPolicyNameByModule(checkModule, checkAction);
        else {
            if (checkMsgid != null && !checkMsgid.isEmpty())
                policyName = getPolicyNameByMsgid(checkMsgid);
            else
                policyName = defaultPolicy;
        }
        HashMap<String, Object> policy = getPolicies(policyName);
        if (policy == null) {
            sendAudit("deny", new String[]{"policy", "no policy"},
                    checkModule, checkAction, checkMsgid, policyName, dmsg);
            if (auditModule == null || auditModule.isEmpty()) {
                StringBuilder logBuffer = new StringBuilder();
                logBuffer.append("没有对应认证策略:模块: ").append(checkModule).append("动作:").append(checkAction)
                        .append("认证策略:").append(policyName);
                putLog(logBuffer.toString(), LogLevel.ERROR, "auth");
            }
            return createMsg()
                    .setSystemParam(MODULE_DONEXTMSG, false)
                    .setParam("resultFrom", "auth")
                    .setParam("denyType", "policy")
                    .setParam("denyValue", "no policy");
        }
        if (policy.isEmpty()) {
            sendAudit("pass", null, checkModule, checkAction, checkMsgid, policyName, dmsg);
            return null;
        }
        String[] policyResult = checkPolicy(policy, dmsg);
        if (policyResult != null) {
            sendAudit("deny", policyResult, checkModule, checkAction, checkMsgid, policyName, dmsg);
            if (auditModule == null || auditModule.isEmpty()) {
                StringBuilder logBuffer = new StringBuilder().append("模块: ").append(checkModule).append("动作:")
                        .append(checkAction).append("访问拒绝");
                putLog(logBuffer.toString(), LogLevel.WARN, "auth");
            }
            String denyMsgId = getDenyMsg(checkModule, checkMsgid, null, policy);
            if (denyMsgId != null && !denyMsgId.isEmpty())
                putMsg(this, createMsg().setMsgId(denyMsgId).setParam("type", policyResult[0])
                        .setParam("value", policyResult[1]));
            return createMsg()
                    .setSystemParam(MODULE_DONEXTMSG, false)
                    .setParam("resultFrom", "auth")
                    .setParam("denyType", policyResult[0])
                    .setParam("denyValue", policyResult.length > 1 ? policyResult[1] : "");
        }
        sendAudit("pass", null, checkModule, checkAction, checkMsgid, policyName, dmsg);
        return null;
    }

    // ======================== 用户状态解析 ========================

    /**
     * 从被检查的业务消息中解析用户状态（userid, role, group, tokenExpire）。
     * 仅从消息参数中直接读取，不依赖外部模块。
     *
     * @param stateMsg 携带用户状态的消息（authInModule/authInUrlMap 为 dmsg，authTag 为 msg）
     * @return 包含用户状态的 TLMsg，或 null
     */
    protected TLMsg resolveUserState(TLMsg stateMsg) {
        if (stateMsg != null && (stateMsg.getParam(USER_P_USERID) != null
                || stateMsg.getParam(USER_P_ROLE) != null)) {
            return stateMsg;
        }
        return null;
    }

    // ======================== 策略名解析 ========================

    protected String getPolicyNameByMsgid(String checkMsgid) {
        String msgPolicy = "MSGID:" + checkMsgid;
        Map policyMap = getPolicies(msgPolicy);
        if (policyMap != null)
            return msgPolicy;
        if (authMsgids == null || authMsgids.isEmpty())
            return defaultPolicy;
        HashMap<String, String> moduleHash = authMsgids.get(checkMsgid);
        if (moduleHash == null)
            return defaultPolicy;
        String policy = moduleHash.get("policy");
        if (policy == null || policy.isEmpty())
            policy = defaultPolicy;
        return policy;
    }

    protected String getPolicyNameByTag(String tag) {
        String tagPolicy = "TAG:" + tag;
        Map policyMap = getPolicies(tagPolicy);
        if (policyMap != null)
            return tagPolicy;
        if (authTags == null || authTags.isEmpty())
            return defaultPolicy;
        HashMap<String, String> moduleHash = authTags.get(tag);
        if (moduleHash == null)
            return defaultPolicy;
        String policy = moduleHash.get("policy");
        if (policy == null || policy.isEmpty())
            policy = defaultPolicy;
        return policy;
    }

    protected String getPolicyNameByModule(String checkModule, String checkAction) {
        String actionPolicy = checkModule + ":" + checkAction;
        Map policyMap = getPolicies(actionPolicy);
        if (policyMap != null)
            return actionPolicy;
        if (authModules == null || authModules.isEmpty())
            return defaultPolicy;
        HashMap<String, String> moduleHash = authModules.get(checkModule);
        if (moduleHash == null)
            return defaultPolicy;
        String policy = moduleHash.get(checkAction);
        if (policy == null || policy.isEmpty()) {
            policy = moduleHash.get("defaultPolicy");
            if (policy == null)
                policy = defaultPolicy;
        }
        return policy;
    }

    protected String getDenyMsg(String checkModule, String checkMsgid, String tag, HashMap<String, Object> policy) {
        String denyMsgId = (String) policy.get("denyMsg");
        if (denyMsgId != null && !denyMsgId.isEmpty())
            return denyMsgId;
        if (checkModule != null && authModules != null) {
            HashMap<String, String> moduleHash = authModules.get(checkModule);
            if (moduleHash != null)
                denyMsgId = moduleHash.get("denyMsg");
        } else if (checkMsgid != null && authMsgids != null) {
            HashMap<String, String> msgidHash = authMsgids.get(checkMsgid);
            if (msgidHash != null)
                denyMsgId = msgidHash.get("denyMsg");
        } else if (tag != null && authTags != null) {
            HashMap<String, String> msgidHash = authTags.get(tag);
            if (msgidHash != null)
                denyMsgId = msgidHash.get("denyMsg");
        }
        if (denyMsgId == null || denyMsgId.isEmpty())
            denyMsgId = defaultDenyMsg;
        return denyMsgId;
    }

    // ======================== 策略评估引擎 ========================

    /**
     * 策略评估核心。按顺序检查各规则，任一拒绝即返回拒绝原因。
     * <p>
     * 检查顺序（BugFix: 超级用户提前）：
     * sameAs → superUser → superRole → ip → user → role → group → refusedUserMsg
     * </p>
     *
     * @param policy  编译后的策略 Map
     * @param stateMsg 携带用户状态的消息（dmsg 或 authTag 的 msg）
     * @return null = 通过；String[]{type, value} = 拒绝原因
     */
    protected String[] checkPolicy(HashMap<String, Object> policy, TLMsg stateMsg) {
        // 1. sameAs 递归
        String sameAsPolicyName = (String) policy.get("sameAs");
        if (sameAsPolicyName != null && !sameAsPolicyName.isEmpty()) {
            HashMap<String, Object> sameAsPolicy = getPolicies(sameAsPolicyName);
            if (sameAsPolicy == null)
                return new String[]{"policy", "no exit"};
            if (sameAsPolicy.isEmpty())
                return null;
            return checkPolicy(sameAsPolicy, stateMsg);
        }

        // BugFix: 延迟加载 — 只在需要时才获取用户状态
        TLMsg userStateMsg = null;

        // 2. 超级用户绕过（BugFix: 提前到 IP/Token 之前）
        if (defaultSuperUser != null) {
            userStateMsg = resolveUserState(stateMsg);
            if (userStateMsg != null && userStateMsg.getParam(USER_P_USERID) != null) {
                String userid = (String) userStateMsg.getParam(USER_P_USERID);
                if (userid.equals(defaultSuperUser))
                    return null;
            }
        }

        // 3. 超级角色绕过
        if (defaultSuperRole != null) {
            if (userStateMsg == null)
                userStateMsg = resolveUserState(stateMsg);
            if (userStateMsg != null && userStateMsg.getParam(USER_P_ROLE) != null) {
                List userRoles = (List) userStateMsg.getParam(USER_P_ROLE);
                if (userRoles != null && userRoles.contains(defaultSuperRole))
                    return null;
            }
        }

        // 4. IP 检查
        String[] ipPolicy = (String[]) policy.get("ip");
        if (ipPolicy != null && ipPolicy.length > 0) {
            boolean ipAllPass = false;
            for (int i = 0; i < ipPolicy.length; i++) {
                if (ipPolicy[i].trim().equals("*")) {
                    ipAllPass = true;
                    break;
                }
            }
            if (!ipAllPass) {
                String policyResult = checkIP(ipPolicy, stateMsg);
                if (policyResult != null)
                    return new String[]{"ip", policyResult};
            }
        }

        // 5. 用户检查（支持 !userid 否定）
        HashSet<String> userPolicy = (HashSet<String>) policy.get("user");
        if (userPolicy != null && !userPolicy.isEmpty()) {
            if (userStateMsg == null)
                userStateMsg = resolveUserState(stateMsg);
            String userid = null;
            if (userStateMsg != null && userStateMsg.getParam(USER_P_USERID) != null)
                userid = (String) userStateMsg.getParam(USER_P_USERID);
            if (userid == null)
                return new String[]{"user", ""};
            // 先检查否定: !userid 命中则拒绝
            if (userPolicy.contains("!" + userid))
                return new String[]{"user", userid};
            // 正向匹配
            if (userPolicy.contains(userid))
                return null;
            if (!userPolicy.contains("*"))
                return new String[]{"user", userid};
        }

        // 6. 角色检查（支持 !role 否定）
        HashSet<String> rolePolicy = (HashSet<String>) policy.get("role");
        if (rolePolicy != null && !rolePolicy.isEmpty()) {
            if (userStateMsg == null)
                userStateMsg = resolveUserState(stateMsg);
            List userRoles = null;
            if (userStateMsg != null && userStateMsg.getParam(USER_P_ROLE) != null)
                userRoles = (List) userStateMsg.getParam(USER_P_ROLE);
            if (userRoles == null) {
                if (rolePolicy.contains(USER_V_ROLE_GUEST) && !rolePolicy.contains("!" + USER_V_ROLE_GUEST))
                    return null;
                else
                    return new String[]{"role", USER_V_ROLE_GUEST};
            }
            // 先检查否定: !role 命中则拒绝
            for (int i = 0; i < userRoles.size(); i++) {
                if (rolePolicy.contains("!" + userRoles.get(i)))
                    return new String[]{"role", (String) userRoles.get(i)};
            }
            // 正向匹配
            boolean result = false;
            for (int i = 0; i < userRoles.size(); i++) {
                if (rolePolicy.contains(userRoles.get(i))) {
                    result = true;
                    break;
                }
            }
            if (!result)
                return new String[]{"role", userRoles.toString()};
        }

        // 7. 分组检查（支持 !group 否定）
        HashSet<String> groupPolicy = (HashSet<String>) policy.get("group");
        if (groupPolicy != null && !groupPolicy.isEmpty()) {
            if (userStateMsg == null)
                userStateMsg = resolveUserState(stateMsg);
            String userGroup = null;
            if (userStateMsg != null)
                userGroup = (String) userStateMsg.getParam(USER_P_GROUP);
            if (userGroup == null)
                return new String[]{"group", ""};
            // 先检查否定: !group 命中则拒绝
            if (groupPolicy.contains("!" + userGroup))
                return new String[]{"group", userGroup};
            // 正向匹配
            if (!groupPolicy.contains("*") && !groupPolicy.contains(userGroup))
                return new String[]{"group", userGroup};
        }

        // 8. 拒绝用户检查
        String refusedUserMsg = (String) policy.get("refusedUserMsg");
        if (refusedUserMsg != null && !refusedUserMsg.isEmpty()) {
            if (userStateMsg == null)
                userStateMsg = resolveUserState(stateMsg);
            String userid = null;
            if (userStateMsg != null && userStateMsg.getParam(USER_P_USERID) != null)
                userid = (String) userStateMsg.getParam(USER_P_USERID);
            if (userid == null)
                return new String[]{"user", ""};
            String policyResult = checkRefusedUser(refusedUserMsg, userid);
            if (policyResult != null)
                return new String[]{"user", policyResult};
        }

        return null;
    }

    // ======================== 审计 ========================

    /**
     * 发送审计事件到配置的审计模块。审计关闭或未配置 auditModule 时跳过。
     */
    protected void sendAudit(String result, String[] denyInfo,
                             String checkModule, String checkAction, String checkMsgid,
                             String policyName, TLMsg stateMsg) {
        if (auditModule == null || auditModule.isEmpty())
            return;
        TLMsg auditMsg = createMsg()
                .setAction("authAudit")
                .setParam("result", result)
                .setParam("checkModule", checkModule)
                .setParam("checkAction", checkAction)
                .setParam("checkMsgid", checkMsgid)
                .setParam("policyName", policyName)
                .setParam("timestamp", System.currentTimeMillis());
        if (denyInfo != null) {
            auditMsg.setParam("denyType", denyInfo[0]);
            auditMsg.setParam("denyValue", denyInfo[1]);
        }
        if (stateMsg != null) {
            Object v = stateMsg.getParam(USER_P_USERID);
            if (v != null) auditMsg.setParam(USER_P_USERID, v);
            v = stateMsg.getParam(USER_P_ROLE);
            if (v != null) auditMsg.setParam(USER_P_ROLE, v);
            v = stateMsg.getParam(USER_P_GROUP);
            if (v != null) auditMsg.setParam(USER_P_GROUP, v);
            v = stateMsg.getParam(AUTH_P_CLIENTIP);
            if (v != null) auditMsg.setParam(AUTH_P_CLIENTIP, v);
        }
        putMsg(auditModule, auditMsg);
    }

    // ======================== 辅助检查 ========================

    protected String checkRefusedUser(String refusedUserMsg, String userid) {
        TLMsg rmsg = createMsg().setMsgId(refusedUserMsg).setParam("userid", userid);
        TLMsg resultMsg = getMsg(this, rmsg);
        if (resultMsg != null)
            return userid;
        else
            return null;
    }

    /**
     * IP 白名单检查。
     * <p>
     * 优先从 stateMsg 中获取 clientIP；若未提供，尝试通过反射从 servletRequest 模块
     * 获取（兼容 Web 环境，无编译期 Servlet API 依赖）。
     * </p>
     *
     * @param iptables IP 策略数组（支持 * 前缀通配）
     * @param stateMsg 携带用户状态的消息（可能携带 clientIP 参数）
     * @return null = 通过；否则返回实际 IP 字符串
     */
    protected String checkIP(String[] iptables, TLMsg stateMsg) {
        String ip = null;

        // 优先：从消息中直接获取 clientIP
        if (stateMsg != null) {
            ip = (String) stateMsg.getParam(AUTH_P_CLIENTIP);
        }

        // 回退：通过反射从 servletRequest 模块获取（兼容 Web 环境）
        if (ip == null) {
            try {
                String threadName = Thread.currentThread().getName();
                Map<String, Object> requestMap = (Map<String, Object>) getModuleInFactory("servletRequest");
                if (requestMap != null) {
                    Object request = requestMap.get(threadName);
                    if (request != null) {
                        ip = (String) request.getClass().getMethod("getRemoteAddr").invoke(request);
                    }
                }
            } catch (Exception ignored) {
                // 非 Web 环境或无 servletRequest 模块，忽略
            }
        }

        if (ip == null || ip.isEmpty())
            return "no ip";

        // IP 匹配逻辑
        for (int i = 0; i < iptables.length; i++) {
            int index = iptables[i].trim().indexOf("*");
            if (index < 0) {
                if (iptables[i].trim().equals(ip))
                    return null;
            } else {
                String subiptable = index > 0 ? iptables[i].substring(0, index) : "";
                String subip = index > 0 && ip.length() >= index ? ip.substring(0, index) : "";
                if (subiptable.equals(subip))
                    return null;
            }
        }
        return ip;
    }

    // ======================== 配置内部类 ========================

    public static class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> authModules;
        protected HashMap<String, HashMap<String, String>> policies;
        protected HashMap<String, HashMap<String, String>> authMsgids;
        protected HashMap<String, HashMap<String, String>> authTags;

        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
        }

        public myConfig() {
        }

        public HashMap getAuthModules() {
            return authModules;
        }

        public HashMap getPolicies() {
            return policies;
        }

        public HashMap getAuthMsgids() {
            return authMsgids;
        }

        public HashMap getAuthTags() {
            return authTags;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("authModules")) {
                    authModules = getHashMap(xpp, "authModules", "module");
                }
                if (xpp.getName().equals("authMsgids")) {
                    authMsgids = getHashMap(xpp, "authMsgids", "msgid");
                }
                if (xpp.getName().equals("authTags")) {
                    authTags = getHashMap(xpp, "authTags", "tag");
                }
                if (xpp.getName().equals("policies")) {
                    policies = getHashMap(xpp, "policies", "policy");
                }
            } catch (Throwable t) {
                putLog("TLAuthModule config parse error:" + t.toString(), LogLevel.WARN);
            }
        }
    }
}
