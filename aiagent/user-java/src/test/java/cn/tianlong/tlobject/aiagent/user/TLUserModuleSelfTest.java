package cn.tianlong.tlobject.aiagent.user;

import cn.tianlong.tlobject.aiagent.user.TLUserModule.UserRecord;
import cn.tianlong.tlobject.base.TLMsg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户模块自测（runnable main，无 JUnit）。
 * 通过覆盖存储方法的 InMemory 子类测业务逻辑，不依赖真实数据库。
 * 失败退出码 1。
 */
public class TLUserModuleSelfTest {
    static int pass = 0, fail = 0;

    /** 合法 1×1 PNG（头像魔数校验需要真实图片字节）。 */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    public static void main(String[] args) {
        testUserIdValidation();
        testPasswordHash();
        testParams();
        testSqlShapes();
        testAuthFlows();
        testProfileFlows();
        testAdminFlows();
        testRegisterSettings();
        testPasswordValidation();
        System.out.println("[TLUserModuleSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void testUserIdValidation() {
        check("1 合法登录名 ab", TLUserModule.validateUserId("ab") == null);
        check("2 合法登录名 user_1", TLUserModule.validateUserId("user_1") == null);
        check("3 合法登录名 A-b", TLUserModule.validateUserId("A-b") == null);
        check("4 太短拒绝", TLUserModule.validateUserId("a") != null);
        check("5 空格拒绝", TLUserModule.validateUserId("a b") != null);
        check("6 中文拒绝", TLUserModule.validateUserId("用户") != null);
        check("7 路径分隔符拒绝", TLUserModule.validateUserId("a/b") != null);
        check("8 超长拒绝", TLUserModule.validateUserId("a".repeat(33)) != null);
        check("18 系统保留名拒绝", TLUserModule.validateUserId("CON") != null);
    }

    static void testPasswordHash() {
        String h1 = TLUserModule.hashPassword("secret123", 1000);
        String h2 = TLUserModule.hashPassword("secret123", 1000);
        check("9 正确密码通过", TLUserModule.verifyPassword("secret123", h1));
        check("10 错误密码拒绝", !TLUserModule.verifyPassword("secret124", h1));
        check("11 随机盐两次哈希不同", !h1.equals(h2));
        check("12 畸形哈希拒绝", !TLUserModule.verifyPassword("secret123", "bad-format"));
        check("13 空哈希拒绝", !TLUserModule.verifyPassword("secret123", ""));
    }

    static void testParams() {
        TLUserModule m = new TLUserModule("userSelfTest");
        Map<String, String> p = new HashMap<>();
        p.put("defaultRole", "visitor");
        p.put("adminRoles", "admin,root");
        p.put("minPasswordLength", "8");
        p.put("pbkdf2Iterations", "1000");
        m.applyParams(p);
        check("14 默认角色参数", "visitor".equals(m.defaultRole));
        check("15 管理员角色多值", m.adminRoles.size() == 2 && m.adminRoles.contains("root"));
        check("16 密码长度参数", m.minPasswordLength == 8);
        check("17 迭代次数参数", m.pbkdf2Iterations == 1000);

        Map<String, String> p2 = new HashMap<>();
        p2.put("adminRoles", ",");
        m.applyParams(p2);
        check("19 adminRoles 空配置回落默认", m.adminRoles.contains("admin"));
    }

    static class SqlCaptureModule extends TLUserModule {
        String lastAction;
        String lastSql;
        LinkedHashMap<String, Object> lastParams;

        SqlCaptureModule() { super("userSqlCapture"); }

        @Override
        @SuppressWarnings("unchecked")
        protected TLMsg sendToTable(TLMsg msg) {
            lastAction = msg.getAction();
            lastSql = msg.getStringParam("sql", "");
            lastParams = (LinkedHashMap<String, Object>) msg.getParam("params", LinkedHashMap.class);
            return createMsg().setParam("result", true).setParam("dbresult", new ArrayList<>());
        }
    }

    static void testSqlShapes() {
        SqlCaptureModule m = new SqlCaptureModule();
        Map<String, String> p = new HashMap<>();
        p.put("pbkdf2Iterations", "1000");
        m.applyParams(p);

        UserRecord light = new UserRecord();
        light.userId = "u1";
        light.updatedAt = 1L;
        check("21 轻记录更新不写头像", m.updateRecord(light)
                && m.lastSql != null && !m.lastSql.contains("avatar"));

        UserRecord full = new UserRecord();
        full.userId = "u1";
        full.updatedAt = 1L;
        full.avatar = "data:image/png;base64,AAAA";
        full.avatarLoaded = true;
        check("22 完整记录更新写头像", m.updateRecord(full)
                && m.lastSql != null && m.lastSql.contains("avatar=?"));

        m.listPage("50%_测试", -1, 10, 0);
        check("23 LIKE 中性转义", m.lastSql != null && m.lastSql.contains("escape '!'")
                && "%50!%!_测试%".equals(String.valueOf(m.lastParams.get("k1"))));

        m.updateRecord(full);
        check("80 updateRecord 不写密码哈希", m.lastSql != null && !m.lastSql.contains("password_hash"));
    }

    // ===== 测试用内存实现 =====
    static class InMemoryUserModule extends TLUserModule {
        final Map<String, UserRecord> rows = new LinkedHashMap<>();
        InMemoryUserModule() { super("userSelfTest"); }

        @Override protected UserRecord findById(String userId) {
            UserRecord r = rows.get(userId);
            if (r == null) return null;
            UserRecord light = new UserRecord();
            light.userId = r.userId;
            light.roles = r.roles;
            light.group = r.group;
            light.status = r.status;
            light.hasAvatar = false;
            return light;   // 模拟真实轻量投影：无 passwordHash/bio/email/avatar
        }
        @Override protected UserRecord findByIdFull(String userId) {
            UserRecord r = rows.get(userId);
            return r == null ? null : copy(r);
        }
        @Override protected List<UserRecord> findEnabled() {
            List<UserRecord> out = new ArrayList<>();
            for (UserRecord r : rows.values()) if (r.status == 1) out.add(copy(r));
            return out;
        }
        @Override protected List<UserRecord> listPage(String keyword, int status, int pageSize, int offset) {
            List<UserRecord> all = new ArrayList<>();
            String kw = keyword == null ? "" : keyword.trim().toLowerCase();
            for (UserRecord r : rows.values()) {
                if (status >= 0 && r.status != status) continue;
                if (!kw.isEmpty() && !(r.userId.toLowerCase().contains(kw)
                        || r.nickname.toLowerCase().contains(kw)
                        || r.email.toLowerCase().contains(kw))) continue;
                all.add(copy(r));
            }
            all.sort(java.util.Comparator.comparingLong((UserRecord r) -> r.createdAt)
                    .reversed().thenComparing(r -> r.userId));
            int from = Math.min(offset, all.size());
            int to = Math.min(from + pageSize, all.size());
            return new ArrayList<>(all.subList(from, to));
        }
        @Override protected int countFiltered(String keyword, int status) {
            return listPage(keyword, status, Integer.MAX_VALUE, 0).size();
        }
        @Override protected int countAll() { return rows.size(); }
        @Override protected boolean insertRecord(UserRecord r) {
            if (rows.containsKey(r.userId)) return false;
            UserRecord stored = copy(r);
            stored.avatarLoaded = true;
            stored.hasAvatar = stored.avatar != null && !stored.avatar.isEmpty();
            rows.put(r.userId, stored);
            return true;
        }
        @Override protected boolean updateRecord(UserRecord r) {
            UserRecord old = rows.get(r.userId);
            if (old == null) return false;
            UserRecord n = copy(r);
            n.passwordHash = old.passwordHash;   // 真实 updateRecord 不写密码
            if (!r.avatarLoaded) {
                n.avatar = old.avatar;
                n.hasAvatar = old.hasAvatar;
            }
            n.avatarLoaded = true;
            rows.put(r.userId, n);
            return true;
        }
        @Override protected boolean updateLogin(String userId, long at, String ip) {
            UserRecord r = rows.get(userId);
            if (r == null) return false;
            r.lastLoginAt = at;
            r.lastLoginIp = ip;
            r.updatedAt = at;
            return true;
        }
        @Override protected boolean updatePassword(String userId, String hash) {
            UserRecord r = rows.get(userId);
            if (r == null) return false;
            r.passwordHash = hash;
            r.updatedAt = System.currentTimeMillis();
            return true;
        }
        @Override protected boolean deleteRecord(String userId) { return rows.remove(userId) != null; }

        // ===== 注册设置（ai_settings）桩：内存 map 模拟 DB =====
        final Map<String, String> settings = new LinkedHashMap<>();
        boolean settingsAvailable = true;
        boolean settingsPersistOk = true;

        @Override protected boolean settingsStorageAvailable() { return settingsAvailable; }

        @Override protected boolean reloadRegisterSettings() {
            if (!settingsAvailable) return false;
            boolean allow = allowSelfRegister;
            boolean need = registerNeedApproval;
            String a = settings.get("allowSelfRegister");
            if (a != null) allow = Boolean.parseBoolean(a);
            String n = settings.get("registerNeedApproval");
            if (n != null) need = Boolean.parseBoolean(n);
            boolean persisted = !settings.isEmpty();
            applyRegisterSettings(allow, need, persisted ? 1L : 0L, persisted ? "admin" : "", persisted);
            return true;
        }

        @Override protected boolean persistSetting(String key, String value, String operator) {
            if (!settingsAvailable || !settingsPersistOk) return false;
            settings.put(key, value);
            return true;
        }
    }

    static UserRecord copy(UserRecord r) {
        UserRecord x = new UserRecord();
        x.userId = r.userId; x.nickname = r.nickname; x.passwordHash = r.passwordHash;
        x.roles = r.roles; x.group = r.group; x.bio = r.bio; x.email = r.email;
        x.avatar = r.avatar; x.lastLoginIp = r.lastLoginIp; x.status = r.status;
        x.createdAt = r.createdAt; x.updatedAt = r.updatedAt; x.lastLoginAt = r.lastLoginAt;
        x.hasAvatar = r.hasAvatar; x.avatarLoaded = r.avatarLoaded;
        return x;
    }

    static InMemoryUserModule seeded() {
        InMemoryUserModule m = new InMemoryUserModule();
        Map<String, String> p = new HashMap<>();
        p.put("pbkdf2Iterations", "1000");
        p.put("bootstrapUsers", "admin:member,admin:pw123456;tianlong:member,guest:pw123456;console_user:guest:");
        p.put("bootstrapGroups", "admin:0");
        m.applyParams(p);
        m.bootstrapIfEmpty();
        return m;
    }

    static TLMsg call(TLUserModule m, String action, Object... kv) {
        TLMsg msg = m.createMsg().setAction(action);
        for (int i = 0; i + 1 < kv.length; i += 2) msg.setParam(String.valueOf(kv[i]), kv[i + 1]);
        return m.getMsg("test", msg);
    }

    static void testAuthFlows() {
        InMemoryUserModule m = seeded();
        TLMsg ok = call(m, "login", "userId", "admin", "password", "pw123456", "clientIP", "127.0.0.1");
        check("24 登录成功", ok.parseBoolean("success", false));
        check("25 登录返回昵称", "admin".equals(ok.getStringParam("nickname", "")));
        check("26 登录返回管理员", ok.parseBoolean("isAdmin", false));
        check("27 登录角色列表", ok.getListParam("roles", new ArrayList<>()).contains("admin"));
        check("28 登录更新最后登录", m.rows.get("admin").lastLoginAt > 0
                && "127.0.0.1".equals(m.rows.get("admin").lastLoginIp));

        TLMsg bad = call(m, "login", "userId", "admin", "password", "wrong");
        check("29 错误密码拒绝", !bad.parseBoolean("success", true)
                && "wrong_password".equals(bad.getStringParam("errorCode", "")));
        TLMsg missing = call(m, "login", "userId", "nobody", "password", "x");
        check("30 未知用户拒绝", "user_not_found".equals(missing.getStringParam("errorCode", "")));
        TLMsg nopwd = call(m, "login", "userId", "console_user", "password", "x");
        check("31 无密码账号拒绝", "empty_password".equals(nopwd.getStringParam("errorCode", "")));

        TLMsg info = call(m, "getUserInfo", "userId", "admin");
        check("32 getUserInfo 契约 userid", "admin".equals(info.getParam("userid")));
        check("33 getUserInfo 契约 role 列表", ((List<?>) info.getParam("role")).contains("member"));
        check("34 getUserInfo 契约 group", "0".equals(info.getParam("group")));
        TLMsg unknownInfo = call(m, "getUserInfo", "userId", "ghost");
        check("35 未知用户回落 guest", ((List<?>) unknownInfo.getParam("role")).equals(List.of("guest"))
                && "default".equals(unknownInfo.getParam("userid")));

        TLMsg reg = call(m, "register", "userId", "newbie", "password", "newpw123",
                "nickname", "", "bio", "", "email", "");
        check("36 注册成功", reg.parseBoolean("success", false));
        check("37 注册默认 member", m.rows.get("newbie").roles.equals("member"));
        check("38 注册昵称回落登录名", "newbie".equals(m.rows.get("newbie").nickname));
        TLMsg dup = call(m, "register", "userId", "newbie", "password", "newpw123");
        check("39 重复注册拒绝", "duplicate_user_id".equals(dup.getStringParam("errorCode", "")));
        TLMsg badId = call(m, "register", "userId", "坏 名", "password", "newpw123");
        check("40 非法登录名拒绝", "invalid_user_id".equals(badId.getStringParam("errorCode", "")));
        TLMsg shortPwd = call(m, "register", "userId", "shorty", "password", "123");
        check("41 短密码拒绝", "invalid_password".equals(shortPwd.getStringParam("errorCode", "")));

        TLMsg adm = call(m, "isAdmin", "userId", "admin");
        check("42 isAdmin 管理员", adm.parseBoolean("admin", false));
        TLMsg notAdm = call(m, "isAdmin", "userId", "tianlong");
        check("43 isAdmin 普通用户", !notAdm.parseBoolean("admin", true));

        TLMsg st = call(m, "getUserStatus", "userId", "admin");
        check("58 状态查询", st.parseBoolean("found", false) && st.getIntParam("status", 0) == 1);
    }

    static void testProfileFlows() {
        InMemoryUserModule m = seeded();
        TLMsg detail = call(m, "getUserDetail", "userId", "admin");
        check("44 详情 found", detail.parseBoolean("found", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) detail.getMapParam("user", null);
        check("45 详情无密码哈希", user != null && !user.containsKey("passwordHash"));

        String hashBefore = m.rows.get("tianlong").passwordHash;
        String rolesBefore = m.rows.get("tianlong").roles;
        int statusBefore = m.rows.get("tianlong").status;
        TLMsg upd = call(m, "updateProfile", "userId", "tianlong", "nickname", "天龙",
                "bio", "一句话介绍", "email", "tl@example.com");
        check("46 资料更新成功", upd.parseBoolean("success", false)
                && "天龙".equals(m.rows.get("tianlong").nickname)
                && "一句话介绍".equals(m.rows.get("tianlong").bio)
                && hashBefore.equals(m.rows.get("tianlong").passwordHash)
                && rolesBefore.equals(m.rows.get("tianlong").roles)
                && statusBefore == m.rows.get("tianlong").status);

        String avatar = "data:image/png;base64," + java.util.Base64.getEncoder()
                .encodeToString(PNG);
        TLMsg av = call(m, "updateProfile", "userId", "tianlong", "avatar", avatar);
        check("47 头像保存", av.parseBoolean("success", false)
                && avatar.equals(m.rows.get("tianlong").avatar));
        TLMsg badAv = call(m, "updateProfile", "userId", "tianlong",
                "avatar", "data:image/gif;base64,AAAA");
        check("48 非法头像拒绝", "invalid_avatar".equals(badAv.getStringParam("errorCode", "")));
        byte[] oversized = new byte[204801];
        System.arraycopy(PNG, 0, oversized, 0, PNG.length);
        TLMsg bigAv = call(m, "updateProfile", "userId", "tianlong",
                "avatar", "data:image/png;base64," + java.util.Base64.getEncoder()
                        .encodeToString(oversized));
        check("49 超大头像拒绝", "invalid_avatar".equals(bigAv.getStringParam("errorCode", "")));
        TLMsg fakePng = call(m, "updateProfile", "userId", "tianlong",
                "avatar", "data:image/png;base64," + java.util.Base64.getEncoder()
                        .encodeToString("not-a-png".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check("79 伪 PNG 魔数拒绝", "invalid_avatar".equals(fakePng.getStringParam("errorCode", "")));

        TLMsg wrongOld = call(m, "changePassword", "userId", "tianlong",
                "oldPassword", "wrong", "newPassword", "newpw456");
        check("50 旧密码不符拒绝", !wrongOld.parseBoolean("success", true));
        TLMsg chg = call(m, "changePassword", "userId", "tianlong",
                "oldPassword", "pw123456", "newPassword", "newpw456");
        check("51 改密成功", chg.parseBoolean("success", false));
        check("52 新密码可登录", call(m, "login", "userId", "tianlong", "password", "newpw456")
                .parseBoolean("success", false));
        check("53 旧密码失效", !call(m, "login", "userId", "tianlong", "password", "pw123456")
                .parseBoolean("success", true));

        TLMsg avGet = call(m, "getAvatar", "userId", "tianlong", "operatorId", "tianlong");
        check("54 本人取头像", avGet.parseBoolean("found", false) && avatar.equals(avGet.getParam("avatar")));
        TLMsg avDenied = call(m, "getAvatar", "userId", "admin", "operatorId", "tianlong");
        check("55 非管理员不能看他人头像", "forbidden".equals(avDenied.getStringParam("errorCode", "")));
        TLMsg avAdmin = call(m, "getAvatar", "userId", "tianlong", "operatorId", "admin");
        check("56 管理员可看他人头像", avAdmin.parseBoolean("found", false));

        m.rows.get("tianlong").status = 0;
        TLMsg disabledInfo = call(m, "getUserInfo", "userId", "tianlong");
        TLMsg disabledLogin = call(m, "login", "userId", "tianlong", "password", "newpw456");
        check("57 禁用用户授权回落 guest 且登录拒绝",
                ((List<?>) disabledInfo.getParam("role")).equals(List.of("guest"))
                        && "default".equals(disabledInfo.getParam("userid"))
                        && "disabled".equals(disabledLogin.getStringParam("errorCode", "")));
    }

    static void testAdminFlows() {
        InMemoryUserModule m = seeded();
        TLMsg list = call(m, "listUsers", "operatorId", "admin", "keyword", "", "status", -1,
                "page", 1, "pageSize", 2);
        check("59 管理员列表成功", list.parseBoolean("success", false));
        check("60 列表总数", list.getIntParam("total", 0) == 3);
        check("61 分页大小", ((List<?>) list.getParam("users")).size() == 2);
        TLMsg denied = call(m, "listUsers", "operatorId", "tianlong");
        TLMsg deniedCreate = call(m, "createUser", "operatorId", "tianlong",
                "userId", "xuser", "password", "xxx12345", "roles", "member");
        check("62 非管理员列表/建用户拒绝",
                "forbidden".equals(denied.getStringParam("errorCode", ""))
                        && "forbidden".equals(deniedCreate.getStringParam("errorCode", "")));

        TLMsg created = call(m, "createUser", "operatorId", "admin", "userId", "worker",
                "password", "worker123", "nickname", "工人", "roles", "member",
                "group", "2", "bio", "", "email", "");
        check("63 管理员建用户", created.parseBoolean("success", false)
                && m.rows.containsKey("worker"));
        TLMsg dup = call(m, "createUser", "operatorId", "admin", "userId", "worker",
                "password", "worker123", "roles", "member");
        check("64 重复建用户拒绝", "duplicate_user_id".equals(dup.getStringParam("errorCode", "")));

        String workerHashBefore = m.rows.get("worker").passwordHash;
        // worker 保持非管理员：68/71 依赖 admin 是唯一管理员、worker 无重置权限
        TLMsg upd = call(m, "updateUser", "operatorId", "admin", "userId", "worker",
                "nickname", "工人二号", "roles", List.of("member", "guest"), "group", "2",
                "bio", "hi", "email", "w@example.com", "status", 1);
        check("65 管理员改用户且密码不变", upd.parseBoolean("success", false)
                && "工人二号".equals(m.rows.get("worker").nickname)
                && m.rows.get("worker").roles.equals("member,guest")
                && workerHashBefore.equals(m.rows.get("worker").passwordHash));

        TLMsg delSelf = call(m, "deleteUser", "operatorId", "admin", "userId", "admin");
        check("66 不能删自己", "self_forbidden".equals(delSelf.getStringParam("errorCode", "")));
        TLMsg disableSelf = call(m, "updateUser", "operatorId", "admin", "userId", "admin",
                "nickname", "admin", "roles", "member,admin", "group", "0",
                "bio", "", "email", "", "status", 0);
        check("67 不能禁用自己", "self_forbidden".equals(disableSelf.getStringParam("errorCode", "")));

        // admin 是唯一管理员：把自己的 admin 角色去掉应被拒绝
        TLMsg demote = call(m, "updateUser", "operatorId", "admin", "userId", "admin",
                "nickname", "admin", "roles", "member", "group", "0",
                "bio", "", "email", "", "status", 1);
        check("68 不能移除最后一个管理员", "last_admin".equals(demote.getStringParam("errorCode", "")));

        TLMsg reset = call(m, "resetPassword", "operatorId", "admin",
                "userId", "worker", "newPassword", "reset123");
        check("69 管理员重置密码", reset.parseBoolean("success", false));
        check("70 重置后可登录", call(m, "login", "userId", "worker", "password", "reset123")
                .parseBoolean("success", false));
        TLMsg resetDenied = call(m, "resetPassword", "operatorId", "worker",
                "userId", "admin", "newPassword", "hack123");
        check("71 非管理员不能重置", "forbidden".equals(resetDenied.getStringParam("errorCode", "")));

        TLMsg filtered = call(m, "listUsers", "operatorId", "admin", "keyword", "工人", "status", -1,
                "page", 1, "pageSize", 20);
        check("72 关键词筛选昵称", filtered.getIntParam("total", 0) == 1);
        // 停用 console_user，让状态筛选有区分度（admin/tianlong/worker 仍为启用）
        m.rows.get("console_user").status = 0;
        TLMsg statusFilter = call(m, "listUsers", "operatorId", "admin", "keyword", "",
                "status", 1, "page", 1, "pageSize", 20);
        check("73 状态筛选", statusFilter.getIntParam("total", 0) == 3);

        TLMsg del = call(m, "deleteUser", "operatorId", "admin", "userId", "worker");
        check("74 删除用户", del.parseBoolean("success", false) && !m.rows.containsKey("worker"));
        m.rows.get("tianlong").roles = "member,admin";
        TLMsg demote2 = call(m, "updateUser", "operatorId", "admin", "userId", "admin",
                "nickname", "admin", "roles", "member", "group", "0",
                "bio", "", "email", "", "status", 1);
        check("75 有其他管理员时可降级", demote2.parseBoolean("success", false));

        m.rows.get("tianlong").nickname = "已改名";
        m.bootstrapIfEmpty();
        check("76 种子幂等不覆盖", "已改名".equals(m.rows.get("tianlong").nickname));

        m.rows.get("console_user").status = 0;
        TLMsg stDisabled = call(m, "getUserStatus", "userId", "console_user");
        check("77 状态查询禁用", stDisabled.parseBoolean("found", false)
                && stDisabled.getIntParam("status", 1) == 0);
        TLMsg stUnknown = call(m, "getUserStatus", "userId", "ghost");
        check("78 状态查询未知", !stUnknown.parseBoolean("found", true));
    }

    static void testRegisterSettings() {
        InMemoryUserModule m = seeded();
        TLMsg policy = call(m, "getRegisterPolicy");
        check("81 默认策略允许注册且无需审核", policy.parseBoolean("success", false)
                && policy.parseBoolean("allowRegister", false)
                && !policy.parseBoolean("registerNeedApproval", true));

        InMemoryUserModule paramOnly = new InMemoryUserModule();
        Map<String, String> p = new HashMap<>();
        p.put("allowSelfRegister", "false");
        p.put("registerNeedApproval", "true");
        paramOnly.applyParams(p);
        TLMsg paramPolicy = call(paramOnly, "getRegisterPolicy");
        check("82 无DB记录时参数兜底", !paramPolicy.parseBoolean("allowRegister", true)
                && paramPolicy.parseBoolean("registerNeedApproval", false));

        m.settings.put("allowSelfRegister", "false");
        m.reloadRegisterSettings();
        TLMsg closedReg = call(m, "register", "userId", "closeduser", "password", "pw123456");
        check("83 DB 关闭注册覆盖参数", "register_disabled".equals(closedReg.getStringParam("errorCode", ""))
                && !call(m, "getRegisterPolicy").parseBoolean("allowRegister", true));

        m.settings.put("allowSelfRegister", "true");
        m.settings.put("registerNeedApproval", "false");
        m.reloadRegisterSettings();
        TLMsg direct = call(m, "register", "userId", "directuser", "password", "pw123456");
        check("84 审核关闭时注册即启用", direct.parseBoolean("success", false)
                && direct.getIntParam("status", 0) == 1
                && !direct.parseBoolean("pendingApproval", true)
                && m.rows.get("directuser").status == 1);

        m.settings.put("registerNeedApproval", "true");
        m.reloadRegisterSettings();
        TLMsg pending = call(m, "register", "userId", "pendinguser", "password", "pw123456");
        check("85 审核开启时注册进入待审核", pending.parseBoolean("success", false)
                && pending.getIntParam("status", 0) == 2
                && pending.parseBoolean("pendingApproval", false)
                && m.rows.get("pendinguser").status == 2);

        TLMsg pendingLogin = call(m, "login", "userId", "pendinguser", "password", "pw123456");
        check("86 待审核用户登录拒绝", !pendingLogin.parseBoolean("success", true)
                && "pending_approval".equals(pendingLogin.getStringParam("errorCode", "")));
        check("87 待审核登录文案", "账号正在等待管理员审核"
                .equals(pendingLogin.getStringParam("error", "")));

        TLMsg approve = call(m, "updateUser", "operatorId", "admin", "userId", "pendinguser", "status", 1);
        check("88 审核通过后可登录", approve.parseBoolean("success", false)
                && call(m, "login", "userId", "pendinguser", "password", "pw123456")
                        .parseBoolean("success", false));

        TLMsg reject = call(m, "updateUser", "operatorId", "admin", "userId", "directuser", "status", 0);
        TLMsg rejectedLogin = call(m, "login", "userId", "directuser", "password", "pw123456");
        check("89 拒绝后为禁用态", reject.parseBoolean("success", false)
                && m.rows.get("directuser").status == 0
                && "disabled".equals(rejectedLogin.getStringParam("errorCode", "")));

        call(m, "register", "userId", "keepuser", "password", "pw123456");
        TLMsg keepPending = call(m, "updateUser", "operatorId", "admin", "userId", "keepuser",
                "nickname", "待审改名");
        check("90 编辑未传 status 保留待审核", keepPending.parseBoolean("success", false)
                && m.rows.get("keepuser").status == 2
                && "待审改名".equals(m.rows.get("keepuser").nickname));

        m.settings.put("registerNeedApproval", "false");
        m.reloadRegisterSettings();
        call(m, "register", "userId", "explicituser", "password", "pw123456");
        m.settings.put("registerNeedApproval", "true");
        m.reloadRegisterSettings();
        call(m, "updateUser", "operatorId", "admin", "userId", "explicituser", "status", 2);
        check("91 显式 status=2 保留待审核", m.rows.get("explicituser").status == 2);

        TLMsg invalidUser = call(m, "register", "userId", "invaliduser", "password", "pw123456");
        call(m, "updateUser", "operatorId", "admin", "userId", "invaliduser", "status", 5);
        check("92 非法状态归一为启用", invalidUser.parseBoolean("success", false)
                && m.rows.get("invaliduser").status == 1);

        TLMsg pendingList = call(m, "listUsers", "operatorId", "admin", "keyword", "",
                "status", 2, "page", 1, "pageSize", 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pendingRows = (List<Map<String, Object>>) pendingList.getParam("users");
        boolean allPending = pendingRows != null && !pendingRows.isEmpty();
        if (pendingRows != null)
            for (Map<String, Object> row : pendingRows)
                allPending = allPending && Long.valueOf(2L).equals(
                        Long.valueOf(String.valueOf(row.get("status"))));
        check("93 待审核筛选只返回 status=2", pendingList.parseBoolean("success", false)
                && pendingList.getIntParam("total", 0) == 2 && allPending);

        call(m, "updateUser", "operatorId", "admin", "userId", "keepuser",
                "roles", "member,admin");
        check("94 待审核用户无管理员权限且回落 guest",
                m.rows.get("keepuser").status == 2
                        && !call(m, "isAdmin", "userId", "keepuser").parseBoolean("admin", true)
                        && ((List<?>) call(m, "getUserInfo", "userId", "keepuser").getParam("role"))
                                .equals(List.of("guest")));

        TLMsg deniedSet = call(m, "setRegisterSettings", "operatorId", "tianlong",
                "allowRegister", false);
        check("95 非管理员不能改注册设置", "forbidden".equals(deniedSet.getStringParam("errorCode", ""))
                && call(m, "getRegisterPolicy").parseBoolean("allowRegister", false));

        TLMsg saved = call(m, "setRegisterSettings", "operatorId", "admin",
                "allowRegister", false, "registerNeedApproval", false);
        TLMsg savedPolicy = call(m, "getRegisterPolicy");
        check("96 管理员保存设置立即生效", saved.parseBoolean("success", false)
                && !savedPolicy.parseBoolean("allowRegister", true)
                && !savedPolicy.parseBoolean("registerNeedApproval", true)
                && "register_disabled".equals(call(m, "register", "userId", "blockeduser",
                        "password", "pw123456").getStringParam("errorCode", "")));

        TLMsg noItems = call(m, "setRegisterSettings", "operatorId", "admin");
        TLMsg badBool = call(m, "setRegisterSettings", "operatorId", "admin",
                "allowRegister", "yes");
        check("97 设置缺项/非法布尔拒绝", "invalid_input".equals(noItems.getStringParam("errorCode", ""))
                && "invalid_input".equals(badBool.getStringParam("errorCode", "")));

        TLMsg deniedGet = call(m, "getRegisterSettings", "operatorId", "tianlong");
        TLMsg adminGet = call(m, "getRegisterSettings", "operatorId", "admin");
        check("98 设置读取权限与元数据", "forbidden".equals(deniedGet.getStringParam("errorCode", ""))
                && adminGet.parseBoolean("success", false)
                && adminGet.parseBoolean("available", false)
                && adminGet.parseBoolean("persisted", false)
                && adminGet.getLongParam("updatedAt", 0L) > 0
                && "admin".equals(adminGet.getStringParam("updatedBy", "")));

        InMemoryUserModule off = seeded();
        off.settingsAvailable = false;
        TLMsg offSet = call(off, "setRegisterSettings", "operatorId", "admin",
                "allowRegister", false);
        check("99 设置存储不可用时软降级", call(off, "getRegisterPolicy")
                        .parseBoolean("allowRegister", false)
                && "storage_error".equals(offSet.getStringParam("errorCode", ""))
                && call(off, "register", "userId", "offuser", "password", "pw123456")
                        .parseBoolean("success", false));

        InMemoryUserModule restarted = new InMemoryUserModule();
        Map<String, String> restartParams = new HashMap<>();
        restartParams.put("pbkdf2Iterations", "1000");
        restartParams.put("allowSelfRegister", "true");
        restartParams.put("registerNeedApproval", "true");
        restarted.applyParams(restartParams);
        restarted.settings.putAll(m.settings);   // 模拟重启后从 DB 读回
        restarted.reloadRegisterSettings();
        TLMsg restartPolicy = call(restarted, "getRegisterPolicy");
        check("100 重启后 DB 设置覆盖参数", !restartPolicy.parseBoolean("allowRegister", true)
                && !restartPolicy.parseBoolean("registerNeedApproval", true));

        TLMsg createdPending = call(m, "createUser", "operatorId", "admin", "userId", "created2",
                "password", "pw123456", "roles", "member", "status", 2);
        check("101 管理端创建不产生待审核", createdPending.parseBoolean("success", false)
                && m.rows.get("created2").status == 1);

        TLMsg selfPending = call(m, "updateUser", "operatorId", "admin", "userId", "admin",
                "status", 2);
        check("102 管理员不能把自己转待审核",
                "self_forbidden".equals(selfPending.getStringParam("errorCode", ""))
                        && m.rows.get("admin").status == 1);
    }

    static void testPasswordValidation() {
        TLUserModule m = new TLUserModule("userSelfTest");
        check("20 密码长度校验", m.validatePassword(null) != null
                && m.validatePassword("123") != null
                && m.validatePassword("123456") == null
                && m.validatePassword("x".repeat(65)) != null);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
