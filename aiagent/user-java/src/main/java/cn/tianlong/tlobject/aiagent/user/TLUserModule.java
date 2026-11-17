package cn.tianlong.tlobject.aiagent.user;

import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDatabase;
import cn.tianlong.tlobject.modules.LogLevel;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static cn.tianlong.tlobject.base.TLParamString.DB_DELETE;
import static cn.tianlong.tlobject.base.TLParamString.DB_INSERT;
import static cn.tianlong.tlobject.base.TLParamString.DB_P_PARAMS;
import static cn.tianlong.tlobject.base.TLParamString.DB_P_SQL;
import static cn.tianlong.tlobject.base.TLParamString.DB_QUERY;
import static cn.tianlong.tlobject.base.TLParamString.DB_R_RESULT;
import static cn.tianlong.tlobject.base.TLParamString.DB_UPDATE;
import static cn.tianlong.tlobject.base.TLParamString.RESULT;

/**
 * 数据库用户模块。
 *
 * <p>职责：用户增删改查、登录校验、PBKDF2 密码、角色/group 解析、管理员保护、种子初始化。
 * 用户表在 database_config.xml 声明（模块参数 tableName，默认 aiUsers）。</p>
 *
 * <p>接口契约：getUserInfo 采用 userid/role/group 契约（供 TLAppAuthModule 消费）；
 * 其余 action 供 webui / 管理页 / 控制台使用，详见设计文档
 * docs/superpowers/specs/2026-11-16-db-user-module-design.md。</p>
 */
public class TLUserModule extends TLBaseModule {

    private static final Pattern USER_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{2,32}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HASH_PREFIX = "pbkdf2";
    private static final String DUMMY_HASH = hashPassword("tlobject-dummy-password", 120000);
    private static final Set<String> WEAK_PASSWORDS = Set.of("123456", "admin123", "password", "12345678");
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    protected String tableName = "aiUsers";
    protected String defaultRole = "guest";
    protected String selfRegisterRole = "member";
    protected List<String> adminRoles = new ArrayList<>(List.of("admin"));
    protected boolean allowSelfRegister = true;
    protected int minPasswordLength = 6;
    protected int maxPasswordLength = 64;
    protected int maxAvatarBytes = 204800;
    protected int pbkdf2Iterations = 120000;
    protected String bootstrapUsers = "";
    protected String bootstrapGroups = "";

    protected IObject usersTable;

    public TLUserModule() { super(); }
    public TLUserModule(String name) { super(name); }
    public TLUserModule(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    /** action 分发（管理接口在后续任务中接入）。 */
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        try {
            switch (msg.getAction()) {
                case "login": return login(msg);
                case "register": return register(msg);
                case "getUserInfo": return getUserInfo(msg);
                case "getUserDetail": return getUserDetail(msg);
                case "getUserStatus": return getUserStatus(msg);
                case "updateProfile": return updateProfile(msg);
                case "changePassword": return changePassword(msg);
                case "getAvatar": return getAvatar(msg);
                case "isAdmin": return isAdminAction(msg);
                case "listUsers": return listUsers(msg);
                case "createUser": return createUser(msg);
                case "updateUser": return updateUser(msg);
                case "deleteUser": return deleteUser(msg);
                case "resetPassword": return resetPassword(msg);
            }
            return null;
        } catch (StorageException e) {
            putLog("用户数据访问失败: action=" + msg.getAction(), LogLevel.ERROR, "storage");
            if ("getUserInfo".equals(msg.getAction())) {
                String uid = msg.getStringParam("userId", "default");
                return createMsg().setParam("userid", uid)
                        .setParam("role", List.of(defaultRole))
                        .setParam("group", "")
                        .setParam("storageError", true);
            }
            return err("storage_error", "用户数据访问失败，请稍后重试");
        }
    }

    // ======================== 登录 / 注册 / 授权 ========================

    private TLMsg login(TLMsg msg) {
        String userId = msg.getStringParam("userId", "").trim();
        String password = msg.getStringParam("password", "");
        if (userId.isEmpty()) return err("invalid_input", "请输入用户ID");
        UserRecord u = findByIdFull(userId);
        if (u == null) {
            verifyPassword(password, DUMMY_HASH);   // 等时，避免用耗时判断账号是否存在
            return err("user_not_found", "用户ID或密码错误");
        }
        if (u.status != 1) return err("disabled", "账号已被禁用，请联系管理员");
        if (u.passwordHash == null || u.passwordHash.isEmpty())
            return err("empty_password", "用户ID或密码错误");
        if (password != null && password.length() > 256)
            return err("wrong_password", "用户ID或密码错误");
        if (!verifyPassword(password, u.passwordHash))
            return err("wrong_password", "用户ID或密码错误");
        long now = System.currentTimeMillis();
        String ip = msg.getStringParam("clientIP", "");
        if (!updateLogin(userId, now, ip))
            putLog("最后登录时间更新失败: " + userId, LogLevel.WARN, "login");
        u.lastLoginAt = now;
        u.lastLoginIp = ip;
        u.updatedAt = now;
        return okUser(u);
    }

    private synchronized TLMsg register(TLMsg msg) {
        if (!allowSelfRegister) return err("register_disabled", "当前已关闭自助注册");
        String userId = msg.getStringParam("userId", "").trim();
        String password = msg.getStringParam("password", "");
        String nickname = msg.getStringParam("nickname", "").trim();
        String bio = msg.getStringParam("bio", "").trim();
        String email = msg.getStringParam("email", "").trim();
        String e = validateUserId(userId);
        if (e != null) return err("invalid_user_id", e);
        e = validatePassword(password);
        if (e != null) return err("invalid_password", e);
        e = validateProfile(nickname, bio, email);
        if (e != null) return err("invalid_profile", e);
        if (findById(userId) != null) return err("duplicate_user_id", "登录名已被占用");
        long now = System.currentTimeMillis();
        UserRecord r = new UserRecord();
        r.userId = userId;
        r.nickname = nickname.isEmpty() ? userId : nickname;
        r.passwordHash = hashPassword(password, pbkdf2Iterations);
        r.roles = selfRegisterRole;
        r.bio = bio;
        r.email = email;
        r.status = 1;
        r.createdAt = now;
        r.updatedAt = now;
        if (!insertRecord(r)) return err("storage_error", "注册失败，请稍后重试");
        return okUser(r);
    }

    private TLMsg getUserInfo(TLMsg msg) {
        String userId = msg.getStringParam("userId", "default");
        if (userId == null || userId.isEmpty()) userId = "default";
        UserRecord u = findById(userId);
        List<String> roles;
        String group = "";
        if (u == null || u.status != 1) {
            // 未知/禁用账号按 default 身份授权，避免按 user=<id> 的授权策略命中禁用账号
            userId = "default";
            roles = List.of(defaultRole);
        } else {
            roles = parseRoles(u.roles);
            if (roles.isEmpty()) roles = List.of(defaultRole);
            group = u.group;
        }
        return createMsg()
                .setParam("userid", userId)
                .setParam("role", roles)
                .setParam("group", group);
    }

    // ======================== 资料 / 头像 ========================

    private TLMsg getUserDetail(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        UserRecord u = findByIdFull(userId);
        if (u == null) return createMsg().setParam("found", false);
        return createMsg().setParam("found", true).setParam("user", sanitize(u, true));
    }

    /** 轻量状态查询（webui 每次请求的门禁用，不取头像）。 */
    private TLMsg getUserStatus(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        UserRecord u = findById(userId);
        if (u == null) return createMsg().setParam("found", false);
        return createMsg().setParam("found", true).setParam("status", u.status);
    }

    private TLMsg updateProfile(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        UserRecord u = findByIdFull(userId);
        if (u == null) return err("user_not_found", "用户不存在");
        if (u.status != 1) return err("disabled", "账号已被禁用");
        String nickname = msg.containsParam("nickname")
                ? msg.getStringParam("nickname", "").trim() : u.nickname;
        String bio = msg.containsParam("bio") ? msg.getStringParam("bio", "").trim() : u.bio;
        String email = msg.containsParam("email") ? msg.getStringParam("email", "").trim() : u.email;
        String e = validateProfile(nickname, bio, email);
        if (e != null) return err("invalid_profile", e);
        if (msg.containsParam("avatar")) {
            String avatar = msg.getStringParam("avatar", "");
            e = validateAvatar(avatar);
            if (e != null) return err("invalid_avatar", e);
            u.avatar = avatar;
            u.avatarLoaded = true;
            u.hasAvatar = avatar != null && !avatar.isEmpty();
        }
        u.nickname = nickname.isEmpty() ? userId : nickname;
        u.bio = bio;
        u.email = email;
        u.updatedAt = System.currentTimeMillis();
        if (!updateRecord(u)) return err("storage_error", "保存失败，请稍后重试");
        return createMsg().setParam("success", true).setParam("user", sanitize(u, true));
    }

    private TLMsg changePassword(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        String oldPwd = msg.getStringParam("oldPassword", "");
        String newPwd = msg.getStringParam("newPassword", "");
        UserRecord u = findByIdFull(userId);
        if (u == null) return err("user_not_found", "用户不存在");
        if (u.status != 1) return err("disabled", "账号已被禁用");
        if (oldPwd != null && oldPwd.length() > 256)
            return err("wrong_password", "旧密码不正确");
        if (u.passwordHash == null || u.passwordHash.isEmpty()
                || !verifyPassword(oldPwd, u.passwordHash))
            return err("wrong_password", "旧密码不正确");
        String e = validatePassword(newPwd);
        if (e != null) return err("invalid_password", e);
        if (!updatePassword(userId, hashPassword(newPwd, pbkdf2Iterations)))
            return err("storage_error", "修改失败，请稍后重试");
        return createMsg().setParam("success", true);
    }

    private TLMsg getAvatar(TLMsg msg) {
        String target = msg.getStringParam("userId", "");
        String operator = msg.getStringParam("operatorId", "");
        if (target.isEmpty()) target = operator;
        if (!target.equals(operator) && !isAdmin(operator))
            return err("forbidden", "无权查看该用户头像");
        UserRecord u = findByIdFull(target);
        if (u == null) return createMsg().setParam("found", false);
        return createMsg().setParam("found", true).setParam("avatar", u.avatar);
    }

    private TLMsg isAdminAction(TLMsg msg) {
        String userId = msg.getStringParam("userId", "");
        return createMsg().setParam("admin", isAdmin(userId));
    }

    boolean isAdmin(String userId) {
        UserRecord u = findById(userId);
        return u != null && u.status == 1 && hasAdminRole(u);
    }

    // ======================== 管理接口 ========================

    private TLMsg listUsers(TLMsg msg) {
        String denied = adminDenied(msg.getStringParam("operatorId", ""));
        if (denied != null) return err("forbidden", denied);
        String keyword = msg.getStringParam("keyword", "").trim();
        int status = msg.containsParam("status") ? msg.getIntParam("status", -1) : -1;
        int page = Math.max(1, msg.getIntParam("page", 1));
        int pageSize = msg.getIntParam("pageSize", 20);
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 100) pageSize = 100;
        long offsetL = (long) (page - 1) * pageSize;
        if (offsetL > Integer.MAX_VALUE) offsetL = Integer.MAX_VALUE;
        int offset = (int) offsetL;
        int total = countFiltered(keyword, status);
        List<Map<String, Object>> users = new ArrayList<>();
        for (UserRecord u : listPage(keyword, status, pageSize, offset))
            users.add(sanitize(u, false));
        return createMsg().setParam("success", true)
                .setParam("total", total)
                .setParam("users", users);
    }

    private synchronized TLMsg createUser(TLMsg msg) {
        String operator = msg.getStringParam("operatorId", "");
        String userId = msg.getStringParam("userId", "").trim();
        String denied = adminDenied(operator);
        if (denied != null) {
            audit("createUser", operator, userId, "denied:forbidden");
            return err("forbidden", denied);
        }
        String password = msg.getStringParam("password", "");
        String nickname = msg.getStringParam("nickname", "").trim();
        String roles = rolesParam(msg, "");
        String group = msg.getStringParam("group", "").trim();
        String bio = msg.getStringParam("bio", "").trim();
        String email = msg.getStringParam("email", "").trim();
        int status = msg.containsParam("status") ? msg.getIntParam("status", 1) : 1;
        String e = validateUserId(userId);
        if (e != null) return err("invalid_user_id", e);
        e = validatePassword(password);
        if (e != null) return err("invalid_password", e);
        if (parseRoles(roles).isEmpty()) return err("invalid_roles", "角色不能为空");
        e = validateProfile(nickname, bio, email);
        if (e != null) return err("invalid_profile", e);
        if (findById(userId) != null) {
            audit("createUser", operator, userId, "denied:duplicate");
            return err("duplicate_user_id", "登录名已被占用");
        }
        long now = System.currentTimeMillis();
        UserRecord r = new UserRecord();
        r.userId = userId;
        r.nickname = nickname.isEmpty() ? userId : nickname;
        r.passwordHash = hashPassword(password, pbkdf2Iterations);
        r.roles = roles;
        r.group = group;
        r.bio = bio;
        r.email = email;
        r.status = status == 0 ? 0 : 1;
        r.createdAt = now;
        r.updatedAt = now;
        if (!insertRecord(r)) {
            audit("createUser", operator, userId, "error");
            return err("storage_error", "保存失败，请稍后重试");
        }
        audit("createUser", operator, userId, "ok");
        return createMsg().setParam("success", true).setParam("user", sanitize(r, true));
    }

    /**
     * 末位管理员检查是"读-判-写"（countEnabledAdmins 后再写），模块级同步锁把管理员变更串行化，
     * 防止并发同时降级/删除仅剩的两个管理员（单 JVM 内有效）。
     */
    private synchronized TLMsg updateUser(TLMsg msg) {
        String operator = msg.getStringParam("operatorId", "");
        String userId = msg.getStringParam("userId", "");
        String denied = adminDenied(operator);
        if (denied != null) {
            audit("updateUser", operator, userId, "denied:forbidden");
            return err("forbidden", denied);
        }
        // 必须用完整记录：updateRecord 是全字段更新，轻量记录会清空未加载的昵称/简介/邮箱等字段
        UserRecord u = findByIdFull(userId);
        if (u == null) return err("user_not_found", "用户不存在");
        if (operator.equals(userId)) {
            int newStatus = msg.containsParam("status") ? msg.getIntParam("status", u.status) : u.status;
            if (newStatus == 0) {
                audit("updateUser", operator, userId, "denied:self_forbidden");
                return err("self_forbidden", "不能禁用自己");
            }
        }
        String nickname = msg.containsParam("nickname")
                ? msg.getStringParam("nickname", "").trim() : u.nickname;
        String roles = msg.containsParam("roles") || msg.containsParam("role")
                ? rolesParam(msg, u.roles) : u.roles;
        String group = msg.containsParam("group") ? msg.getStringParam("group", "").trim() : u.group;
        String bio = msg.containsParam("bio") ? msg.getStringParam("bio", "").trim() : u.bio;
        String email = msg.containsParam("email") ? msg.getStringParam("email", "").trim() : u.email;
        int status = msg.containsParam("status") ? msg.getIntParam("status", u.status) : u.status;
        int normalizedStatus = status == 0 ? 0 : 1;
        if (parseRoles(roles).isEmpty()) return err("invalid_roles", "角色不能为空");
        String e = validateProfile(nickname, bio, email);
        if (e != null) return err("invalid_profile", e);
        boolean wasAdmin = u.status == 1 && hasAdminRole(u);
        boolean staysAdmin = normalizedStatus == 1 && rolesContainAdmin(roles);
        if (wasAdmin && !staysAdmin && countEnabledAdmins() <= 1) {
            audit("updateUser", operator, userId, "denied:last_admin");
            return err("last_admin", "不能移除最后一个启用管理员");
        }
        u.nickname = nickname.isEmpty() ? userId : nickname;
        u.roles = roles;
        u.group = group;
        u.bio = bio;
        u.email = email;
        u.status = normalizedStatus;
        u.updatedAt = System.currentTimeMillis();
        if (!updateRecord(u)) {
            audit("updateUser", operator, userId, "error");
            return err("storage_error", "保存失败，请稍后重试");
        }
        audit("updateUser", operator, userId, "ok");
        return createMsg().setParam("success", true).setParam("user", sanitize(u, true));
    }

    /**
     * 末位管理员检查是"读-判-写"（countEnabledAdmins 后再删），模块级同步锁把管理员变更串行化，
     * 防止并发同时删除仅剩的两个管理员（单 JVM 内有效）。
     */
    private synchronized TLMsg deleteUser(TLMsg msg) {
        String operator = msg.getStringParam("operatorId", "");
        String userId = msg.getStringParam("userId", "");
        String denied = adminDenied(operator);
        if (denied != null) {
            audit("deleteUser", operator, userId, "denied:forbidden");
            return err("forbidden", denied);
        }
        if (operator.equals(userId)) {
            audit("deleteUser", operator, userId, "denied:self_forbidden");
            return err("self_forbidden", "不能删除自己");
        }
        UserRecord u = findById(userId);
        if (u == null) return err("user_not_found", "用户不存在");
        if (u.status == 1 && hasAdminRole(u) && countEnabledAdmins() <= 1) {
            audit("deleteUser", operator, userId, "denied:last_admin");
            return err("last_admin", "不能删除最后一个启用管理员");
        }
        if (!deleteRecord(userId)) {
            audit("deleteUser", operator, userId, "error");
            return err("storage_error", "删除失败，请稍后重试");
        }
        audit("deleteUser", operator, userId, "ok");
        return createMsg().setParam("success", true);
    }

    private TLMsg resetPassword(TLMsg msg) {
        String operator = msg.getStringParam("operatorId", "");
        String userId = msg.getStringParam("userId", "");
        String denied = adminDenied(operator);
        if (denied != null) {
            audit("resetPassword", operator, userId, "denied:forbidden");
            return err("forbidden", denied);
        }
        UserRecord u = findById(userId);
        if (u == null) return err("user_not_found", "用户不存在");
        String newPwd = msg.getStringParam("newPassword", "");
        String e = validatePassword(newPwd);
        if (e != null) return err("invalid_password", e);
        if (!updatePassword(userId, hashPassword(newPwd, pbkdf2Iterations))) {
            audit("resetPassword", operator, userId, "error");
            return err("storage_error", "修改失败，请稍后重试");
        }
        audit("resetPassword", operator, userId, "ok");
        return createMsg().setParam("success", true);
    }

    /** 管理员写操作审计：只记操作者/目标/动作/结果，不记密码。 */
    private void audit(String op, String operator, String target, String result) {
        putLog("admin op=" + op + " operator=" + operator + " target=" + target + " result=" + result,
                LogLevel.INFO, "adminAudit");
    }

    private String adminDenied(String operatorId) {
        UserRecord op = findById(operatorId);
        if (op == null || op.status != 1 || !hasAdminRole(op)) return "需要管理员权限";
        return null;
    }

    private int countEnabledAdmins() {
        int count = 0;
        for (UserRecord u : findEnabled()) if (hasAdminRole(u)) count++;
        return count;
    }

    private boolean rolesContainAdmin(String roles) {
        for (String role : parseRoles(roles)) if (adminRoles.contains(role)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static String rolesParam(TLMsg msg, String def) {
        Object v = msg.containsParam("roles") ? msg.getParam("roles") : msg.getParam("role");
        if (v instanceof List) {
            List<String> roles = new ArrayList<>();
            for (Object o : (List<Object>) v) if (o != null && !String.valueOf(o).trim().isEmpty())
                roles.add(String.valueOf(o).trim());
            return joinRoles(roles);
        }
        if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v).trim();
        return def;
    }

    private TLMsg okUser(UserRecord u) {
        return createMsg()
                .setParam("success", true)
                .setParam("userId", u.userId)
                .setParam("nickname", nicknameOf(u))
                .setParam("roles", parseRoles(u.roles))
                .setParam("group", u.group)
                .setParam("bio", u.bio)
                .setParam("email", u.email)
                .setParam("avatar", u.avatar)
                .setParam("isAdmin", hasAdminRole(u));
    }

    private TLMsg err(String code, String message) {
        return createMsg()
                .setParam("success", false)
                .setParam("errorCode", code)
                .setParam("error", message);
    }

    private Map<String, Object> sanitize(UserRecord u, boolean includeAvatar) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.userId);
        m.put("nickname", nicknameOf(u));
        m.put("roles", parseRoles(u.roles));
        m.put("group", u.group);
        m.put("bio", u.bio);
        m.put("email", u.email);
        if (includeAvatar) m.put("avatar", u.avatar);
        else m.put("hasAvatar", u.hasAvatar || (u.avatar != null && !u.avatar.isEmpty()));
        m.put("status", u.status);
        m.put("createdAt", u.createdAt);
        m.put("updatedAt", u.updatedAt);
        m.put("lastLoginAt", u.lastLoginAt);
        m.put("lastLoginIp", u.lastLoginIp);
        return m;
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        applyParams(params);
    }

    /** 参数应用独立成包内方法，自测直接调用（不跑完整生命周期）。 */
    void applyParams(Map<String, String> p) {
        if (p == null) return;
        tableName = str(p, "tableName", tableName);
        defaultRole = str(p, "defaultRole", defaultRole);
        selfRegisterRole = str(p, "selfRegisterRole", selfRegisterRole);
        String ar = str(p, "adminRoles", null);
        if (ar != null && !ar.trim().isEmpty()) {
            List<String> parsed = splitCsv(ar);
            if (!parsed.isEmpty()) adminRoles = parsed;
            else putLog("adminRoles 配置为空，保留默认值", LogLevel.WARN, "params");
        }
        allowSelfRegister = bool(p, "allowSelfRegister", allowSelfRegister);
        minPasswordLength = num(p, "minPasswordLength", minPasswordLength);
        maxAvatarBytes = num(p, "maxAvatarBytes", maxAvatarBytes);
        pbkdf2Iterations = num(p, "pbkdf2Iterations", pbkdf2Iterations);
        if (minPasswordLength < 4 || minPasswordLength > maxPasswordLength) {
            putLog("minPasswordLength 超出范围，使用默认值 6", LogLevel.WARN, "params");
            minPasswordLength = 6;
        }
        if (pbkdf2Iterations < 1000) {
            putLog("pbkdf2Iterations 过小，使用最小值 1000", LogLevel.WARN, "params");
            pbkdf2Iterations = 1000;
        }
        if (maxAvatarBytes < 1024 || maxAvatarBytes > 10 * 1024 * 1024) {
            putLog("maxAvatarBytes 超出范围，使用默认值 204800", LogLevel.WARN, "params");
            maxAvatarBytes = 204800;
        }
        bootstrapUsers = str(p, "bootstrapUsers", "");
        bootstrapGroups = str(p, "bootstrapGroups", "");
    }

    @Override
    protected TLBaseModule init() {
        try {
            String dbTable = TLDatabase.getTableDbName(tableName, this);
            if (dbTable == null || dbTable.trim().isEmpty()) {
                putLog("用户模块初始化失败：database_config.xml 未声明表 " + tableName, LogLevel.ERROR, "init");
                return null;
            }
            usersTable = TLDatabase.getTable(tableName, this);
        } catch (Exception e) {
            putLog(e, LogLevel.ERROR, "init");
            usersTable = null;
        }
        if (usersTable == null) {
            putLog("用户模块初始化失败：表模块不可用 " + tableName, LogLevel.ERROR, "init");
            return null;
        }
        return this;
    }

    @Override
    public void runStartMsg() {
        super.runStartMsg();
        try {
            bootstrapIfEmpty();
        } catch (StorageException e) {
            putLog("用户种子初始化失败：数据库不可用", LogLevel.ERROR, "bootstrap");
        }
    }

    // ======================== 表访问（自测覆盖点） ========================

    /** 数据库/表模块访问失败（与"记录不存在"区分）。 */
    static class StorageException extends RuntimeException { }

    protected TLMsg sendToTable(TLMsg msg) {
        if (usersTable == null) return createMsg().setParam(RESULT, false);
        return putMsg(usersTable, msg);
    }

    /** 读查询：失败（无响应 / result=false）抛 StorageException。 */
    protected TLMsg query(TLMsg msg) {
        TLMsg r = sendToTable(msg);
        if (r == null || !r.parseBoolean(RESULT, true)) throw new StorageException();
        return r;
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> rowsOf(TLMsg result) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (result == null) return list;
        Object v = result.getParam(DB_R_RESULT);
        if (v instanceof List) {
            for (Object o : (List<Object>) v) if (o instanceof Map) list.add((Map<String, Object>) o);
        } else if (v instanceof Map) {
            list.add((Map<String, Object>) v);
        }
        return list;
    }

    /**
     * 轻量记录：仅认证/存在性判断所需字段（无密码哈希、无头像）。
     * 需要密码哈希（登录/改密）或头像（登录/资料/管理等）的流程必须用 {@link #findByIdFull(String)}。
     */
    protected UserRecord findById(String userId) {
        if (userId == null || userId.isEmpty()) return null;
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("uid", userId);
        TLMsg r = query(createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, "select user_id, roles, user_group, status from [table] where user_id = ?")
                .setParam(DB_P_PARAMS, p));
        List<Map<String, Object>> rows = rowsOf(r);
        return rows.isEmpty() ? null : toRecord(rows.get(0));
    }

    /**
     * 完整记录：含密码哈希与头像（avatarLoaded=true）。登录、改密、资料/管理更新等流程使用。
     */
    protected UserRecord findByIdFull(String userId) {
        if (userId == null || userId.isEmpty()) return null;
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("uid", userId);
        TLMsg r = query(createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, "select * from [table] where user_id = ?")
                .setParam(DB_P_PARAMS, p));
        List<Map<String, Object>> rows = rowsOf(r);
        return rows.isEmpty() ? null : toRecord(rows.get(0));
    }

    protected List<UserRecord> findEnabled() {
        List<UserRecord> out = new ArrayList<>();
        TLMsg r = query(createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, "select user_id, roles, status from [table] where status = 1"));
        for (Map<String, Object> row : rowsOf(r)) out.add(toRecord(row));
        return out;
    }

    protected List<UserRecord> listPage(String keyword, int status, int pageSize, int offset) {
        StringBuilder sql = new StringBuilder("select user_id, nickname, roles, user_group, bio, email, status, "
                + "created_at, updated_at, last_login_at, last_login_ip, "
                + "case when avatar is null or avatar = '' then 0 else 1 end as has_avatar "
                + "from [table] where 1=1");
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        appendFilters(sql, p, keyword, status);
        sql.append(" order by created_at desc, user_id limit ? offset ?");
        p.put("lim", pageSize);
        p.put("off", offset);
        List<UserRecord> out = new ArrayList<>();
        TLMsg r = query(createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql.toString()).setParam(DB_P_PARAMS, p));
        for (Map<String, Object> row : rowsOf(r)) out.add(toRecord(row));
        return out;
    }

    protected int countFiltered(String keyword, int status) {
        StringBuilder sql = new StringBuilder("select count(*) as cnt from [table] where 1=1");
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        appendFilters(sql, p, keyword, status);
        List<Map<String, Object>> rows = rowsOf(query(createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql.toString()).setParam(DB_P_PARAMS, p)));
        return rows.isEmpty() ? 0 : (int) toLong(rows.get(0).get("cnt"), 0L);
    }

    protected int countAll() {
        List<Map<String, Object>> rows = rowsOf(query(createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, "select count(*) as cnt from [table]")));
        return rows.isEmpty() ? 0 : (int) toLong(rows.get(0).get("cnt"), 0L);
    }

    private void appendFilters(StringBuilder sql, LinkedHashMap<String, Object> p, String keyword, int status) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            String like = "%" + likeEscape(keyword.trim()) + "%";
            // 用中性转义符 '!'：反斜杠在 MySQL 字符串字面量里是转义字符，escape '\' 会语法错误；
            // '!' 在 SQLite 与 MySQL 上都可直接作为 ESCAPE 字符。
            sql.append(" and (user_id like ? escape '!' or nickname like ? escape '!' or email like ? escape '!')");
            p.put("k1", like);
            p.put("k2", like);
            p.put("k3", like);
        }
        if (status >= 0) {
            sql.append(" and status = ?");
            p.put("st", status);
        }
    }

    private static String likeEscape(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    protected boolean insertRecord(UserRecord r) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("user_id", r.userId);
        p.put("nickname", r.nickname);
        p.put("password_hash", r.passwordHash);
        p.put("roles", r.roles);
        p.put("user_group", r.group);
        p.put("bio", r.bio);
        p.put("email", r.email);
        p.put("avatar", r.avatar);
        p.put("status", r.status);
        p.put("created_at", r.createdAt);
        p.put("updated_at", r.updatedAt);
        p.put("last_login_at", r.lastLoginAt);
        p.put("last_login_ip", r.lastLoginIp);
        TLMsg res = sendToTable(createMsg().setAction(DB_INSERT).setParam(DB_P_PARAMS, p));
        return res != null && res.parseBoolean(RESULT, false);
    }

    /**
     * 全字段更新：必须传入 {@link #findByIdFull(String)} 加载的完整记录；
     * 轻量记录（findById）未加载简介/邮箱等字段，直接写入会清空它们。
     * 密码不在此处更新（改密走 {@link #updatePassword(String, String)}）；
     * 头像仅在 avatarLoaded=true 时写入。
     */
    protected boolean updateRecord(UserRecord r) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("nickname", r.nickname);
        p.put("roles", r.roles);
        p.put("user_group", r.group);
        p.put("bio", r.bio);
        p.put("email", r.email);
        p.put("status", r.status);
        p.put("updated_at", r.updatedAt);
        StringBuilder sql = new StringBuilder("update [table] set nickname=?, roles=?, "
                + "user_group=?, bio=?, email=?, status=?, updated_at=?");
        if (r.avatarLoaded) {
            sql.append(", avatar=?");
            p.put("avatar", r.avatar);
        }
        sql.append(" where user_id=?");
        p.put("user_id", r.userId);
        TLMsg res = sendToTable(createMsg().setAction(DB_UPDATE)
                .setParam(DB_P_SQL, sql.toString())
                .setParam(DB_P_PARAMS, p));
        return res != null && res.parseBoolean(RESULT, false);
    }

    protected boolean updateLogin(String userId, long at, String ip) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("at", at);
        p.put("ip", ip == null ? "" : ip);
        p.put("upd", at);
        p.put("uid", userId);
        TLMsg res = sendToTable(createMsg().setAction(DB_UPDATE)
                .setParam(DB_P_SQL, "update [table] set last_login_at=?, last_login_ip=?, updated_at=? where user_id=?")
                .setParam(DB_P_PARAMS, p));
        return res != null && res.parseBoolean(RESULT, false);
    }

    protected boolean updatePassword(String userId, String hash) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("hash", hash);
        p.put("upd", System.currentTimeMillis());
        p.put("uid", userId);
        TLMsg res = sendToTable(createMsg().setAction(DB_UPDATE)
                .setParam(DB_P_SQL, "update [table] set password_hash=?, updated_at=? where user_id=?")
                .setParam(DB_P_PARAMS, p));
        return res != null && res.parseBoolean(RESULT, false);
    }

    protected boolean deleteRecord(String userId) {
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        p.put("uid", userId);
        TLMsg res = sendToTable(createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL, "delete from [table] where user_id = ?")
                .setParam(DB_P_PARAMS, p));
        return res != null && res.parseBoolean(RESULT, false);
    }

    // ======================== 用户记录 ========================

    static class UserRecord {
        String userId = "";
        String nickname = "";
        String passwordHash = "";
        String roles = "";
        String group = "";
        String bio = "";
        String email = "";
        String avatar = "";
        boolean avatarLoaded;
        boolean hasAvatar;
        String lastLoginIp = "";
        int status = 1;
        long createdAt;
        long updatedAt;
        long lastLoginAt;
    }

    static UserRecord toRecord(Map<String, Object> row) {
        UserRecord r = new UserRecord();
        r.userId = rowStr(row, "user_id", "");
        r.nickname = rowStr(row, "nickname", "");
        r.passwordHash = rowStr(row, "password_hash", "");
        r.roles = rowStr(row, "roles", "");
        r.group = rowStr(row, "user_group", "");
        r.bio = rowStr(row, "bio", "");
        r.email = rowStr(row, "email", "");
        if (row.containsKey("avatar")) {
            r.avatar = rowStr(row, "avatar", "");
            r.avatarLoaded = true;
        }
        r.hasAvatar = row.containsKey("has_avatar")
                ? toLong(row.get("has_avatar"), 0L) > 0
                : (r.avatarLoaded && r.avatar != null && !r.avatar.isEmpty());
        r.status = (int) toLong(row.get("status"), 0L);
        r.createdAt = toLong(row.get("created_at"), 0L);
        r.updatedAt = toLong(row.get("updated_at"), 0L);
        r.lastLoginAt = toLong(row.get("last_login_at"), 0L);
        r.lastLoginIp = rowStr(row, "last_login_ip", "");
        return r;
    }

    static String nicknameOf(UserRecord r) {
        return r.nickname == null || r.nickname.isEmpty() ? r.userId : r.nickname;
    }

    static List<String> parseRoles(String roles) {
        List<String> out = new ArrayList<>();
        if (roles == null) return out;
        for (String role : roles.split(",")) {
            String t = role.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static String joinRoles(List<String> roles) {
        return String.join(",", roles);
    }

    boolean hasAdminRole(UserRecord r) {
        for (String role : parseRoles(r.roles)) if (adminRoles.contains(role)) return true;
        return false;
    }

    // ======================== 种子初始化 ========================

    void bootstrapIfEmpty() {
        if (countAll() > 0) return;
        if (bootstrapUsers == null || bootstrapUsers.trim().isEmpty()) {
            putLog("用户表为空且未配置 bootstrapUsers，未创建任何账号", LogLevel.WARN, "bootstrap");
            return;
        }
        Map<String, String> groups = new LinkedHashMap<>();
        if (bootstrapGroups != null) {
            for (String entry : bootstrapGroups.split(";")) {
                int c = entry.indexOf(':');
                if (c > 0) groups.put(entry.substring(0, c).trim(), entry.substring(c + 1).trim());
            }
        }
        long now = System.currentTimeMillis();
        for (String entry : bootstrapUsers.split(";")) {
            if (entry.trim().isEmpty()) continue;
            String[] parts = entry.split(":", 3);
            String uid = parts[0].trim();
            if (parts.length < 2 || validateUserId(uid) != null) {
                putLog("忽略非法种子用户: " + uid, LogLevel.WARN, "bootstrap");
                continue;
            }
            if (findById(uid) != null) continue;
            String roles = parts[1].trim();
            String pwd = parts.length > 2 ? parts[2] : "";
            UserRecord r = new UserRecord();
            r.userId = uid;
            r.nickname = uid;
            r.roles = roles.isEmpty() ? selfRegisterRole : roles;
            r.group = groups.getOrDefault(uid, "");
            r.passwordHash = pwd.isEmpty() ? "" : hashPassword(pwd, pbkdf2Iterations);
            r.status = 1;
            r.createdAt = now;
            r.updatedAt = now;
            if (!pwd.isEmpty() && isWeakPassword(pwd))
                putLog("初始账号 " + uid + " 使用弱密码，请登录后修改", LogLevel.WARN, "bootstrap");
            if (!insertRecord(r)) putLog("种子用户写入失败: " + uid, LogLevel.ERROR, "bootstrap");
        }
    }

    static boolean isWeakPassword(String pwd) {
        return WEAK_PASSWORDS.contains(pwd) || pwd.length() < 8;
    }

    // ======================== 校验与哈希 ========================

    static String validateUserId(String userId) {
        if (userId == null || !USER_ID_PATTERN.matcher(userId).matches())
            return "登录名需为 2-32 位字母、数字、下划线或短横线";
        if (WINDOWS_RESERVED.contains(userId.toUpperCase())) return "登录名不能使用系统保留名";
        return null;
    }

    String validatePassword(String password) {
        if (password == null || password.length() < minPasswordLength)
            return "密码至少 " + minPasswordLength + " 位";
        if (password.length() > maxPasswordLength)
            return "密码不能超过 " + maxPasswordLength + " 位";
        return null;
    }

    String validateProfile(String nickname, String bio, String email) {
        if (nickname != null && nickname.length() > 32) return "昵称不能超过 32 个字符";
        if (bio != null && bio.length() > 200) return "一句话介绍不能超过 200 个字符";
        if (email != null && (email.length() > 128 || (!email.isEmpty() && !email.contains("@"))))
            return "邮箱格式不正确";
        return null;
    }

    String validateAvatar(String avatar) {
        if (avatar == null || avatar.isEmpty()) return null;
        String prefix;
        if (avatar.startsWith("data:image/png;base64,")) prefix = "data:image/png;base64,";
        else if (avatar.startsWith("data:image/jpeg;base64,")) prefix = "data:image/jpeg;base64,";
        else if (avatar.startsWith("data:image/webp;base64,")) prefix = "data:image/webp;base64,";
        else return "头像仅支持 PNG/JPEG/WebP";
        try {
            if (avatar.length() > (long) maxAvatarBytes * 4 / 3 + 64)
                return "头像不能超过 " + (maxAvatarBytes / 1024) + "KB";
            byte[] bytes = Base64.getDecoder().decode(avatar.substring(prefix.length()));
            if (bytes.length > maxAvatarBytes)
                return "头像不能超过 " + (maxAvatarBytes / 1024) + "KB";
            if (!looksLikeImage(bytes, prefix)) return "头像数据无效";
        } catch (IllegalArgumentException e) {
            return "头像数据无效";
        }
        return null;
    }

    /** 校验解码后的字节与声明类型匹配（PNG/JPEG/WebP 魔数）。 */
    private static boolean looksLikeImage(byte[] b, String prefix) {
        if (prefix.contains("png"))
            return b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E
                    && b[3] == 0x47 && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                    && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A;
        if (prefix.contains("jpeg"))
            return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                    && (b[2] & 0xFF) == 0xFF;
        if (prefix.contains("webp"))
            return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        return false;
    }

    static String hashPassword(String password, int iterations) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, iterations, 32);
        return HASH_PREFIX + "$" + iterations + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    static boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null || stored.isEmpty()) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password, salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations, int bytes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, bytes * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }

    // ======================== 小工具 ========================

    private static String str(Map<String, String> p, String key, String def) {
        String v = p.get(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static boolean bool(Map<String, String> p, String key, boolean def) {
        String v = p.get(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int num(Map<String, String> p, String key, int def) {
        String v = p.get(key);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split(",")) {
            String t = x.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String rowStr(Map<String, Object> row, String key, String def) {
        Object v = row.get(key);
        return v == null ? def : String.valueOf(v);
    }

    static long toLong(Object v, long def) {
        if (v instanceof Boolean) return ((Boolean) v) ? 1L : 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        if (v != null) {
            try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException ignored) { }
        }
        return def;
    }
}
