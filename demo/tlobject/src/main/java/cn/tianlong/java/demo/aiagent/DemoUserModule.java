package cn.tianlong.java.demo.aiagent;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.*;

/**
 * Demo 用户信息模块 —— 从 XML params 读取用户-角色-分组映射。
 *
 * <p>契约：action="getUserInfo"，入参 userId，出参 userid/role/group</p>
 *
 * <p>配置示例：</p>
 * <pre>
 * &lt;params&gt;
 *     &lt;param name="users"  value="default:member;admin:member,admin;guest:guest" /&gt;
 *     &lt;param name="groups" value="admin:0" /&gt;
 * &lt;/params&gt;
 * </pre>
 * users 格式：userId:role1,role2;userId:role1
 * groups 格式：userId:group;userId:group
 */
public class DemoUserModule extends TLBaseModule {

    private final Map<String, UserInfo> store = new HashMap<>();

    public DemoUserModule() { super(); }
    public DemoUserModule(String name) { super(name); }
    public DemoUserModule(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected TLBaseModule init() {
        parseConfig();
        if (!store.containsKey("default"))
            store.put("default", new UserInfo("default", Arrays.asList("member"), null));
        return this;
    }

    private void parseConfig() {
        String usersConf = params != null ? params.get("users") : null;
        String groupsConf = params != null ? params.get("groups") : null;
        Map<String, String> groupMap = new HashMap<>();
        if (groupsConf != null) {
            for (String entry : groupsConf.split(";")) {
                int c = entry.indexOf(":");
                if (c > 0) groupMap.put(entry.substring(0, c).trim(), entry.substring(c + 1).trim());
            }
        }
        if (usersConf != null) {
            for (String entry : usersConf.split(";")) {
                int c = entry.indexOf(":");
                if (c < 0) continue;
                String uid = entry.substring(0, c).trim();
                String rolesStr = entry.substring(c + 1).trim();
                List<String> roles = rolesStr.isEmpty() ? new ArrayList<>()
                        : Arrays.asList(rolesStr.split(","));
                store.put(uid, new UserInfo(uid, roles, groupMap.get(uid)));
            }
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if ("getUserInfo".equals(msg.getAction())) {
            String userId = (String) msg.getParam("userId");
            if (userId == null) userId = "default";
            UserInfo u = store.getOrDefault(userId, store.get("default"));
            if (u == null) return null;
            return createMsg()
                    .setParam("userid", u.userId)
                    .setParam("role", u.roles)
                    .setParam("group", u.group);
        }
        return null;
    }

    private static class UserInfo {
        final String userId;
        final List<String> roles;
        final String group;
        UserInfo(String userId, List<String> roles, String group) {
            this.userId = userId; this.roles = roles; this.group = group;
        }
    }
}
