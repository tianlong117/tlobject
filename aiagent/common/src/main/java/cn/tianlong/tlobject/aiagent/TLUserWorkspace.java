package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 用户 workspace 与路径访问判定（无状态工具类）。
 *
 * <p>约定：
 * <ul>
 *   <li>用户工作目录 = {@code <tlobject.workspaceRoot>/<safeUserId>}，默认 {@code workspace/<userId>}；</li>
 *   <li>自己的 {@code data/{uid}}、{@code data/documents/{uid}} 视为可信区；
 *       {@code data/{uid}/uploads} 仅兼容 2026-11-09 前的旧上传件（新上传件在
 *       {@code workspace/{uid}/uploads}，见 {@link #uploadsDir(String)}）；</li>
 *   <li>其他用户的 workspace，以及其他用户 {@code data/{uid}/...} 下的已知 per-user 子目录
 *       视为跨用户，硬拒绝。已知子目录及其归属模块：{@code uploads}/{@code attachments}
 *       （上传件与附件）、{@code longterm_memory}（{@code TLLongTermMemoryModule}）、
 *       {@code tasks}（任务中心）、{@code traces}（Provider trace）、
 *       {@code session_store}（{@code TLSessionManager}）、
 *       {@code scheduled_tasks}（{@code TLScheduleTaskSkill}）；</li>
 *   <li>其余路径统一交给审批门禁（path_access）。</li>
 * </ul>
 *
 * <p>判定以路径段为单位，不解析符号链接（与仓库现有安全检查同口径）。但 workspace 根下的
 * 单段直接子项会做一次文件系统类型检查，因此“纯路径段判定”的说法并不严格准确：
 * <ul>
 *   <li>已存在的目录 {@code workspace/{其他 uid}}（含其下内容）→ 跨用户，硬拒绝；</li>
 *   <li>已存在的普通文件（如遗留的 {@code workspace/poet.txt}）→ 越界，走审批；</li>
 *   <li>不存在的单段路径 → 越界，走审批。</li>
 * </ul>
 *
 * <p>路径段比较与平台语义一致：Windows 大小写不敏感（{@link Path#equals}），POSIX 大小写敏感。
 *
 * <p>已知取舍：共享目录若第二段恰好与某个 per-user 子目录同名（如共享的
 * {@code data/team/uploads}），会被判为跨用户而 fail-closed 拒绝。框架不为共享数据根
 * 做例外（无法可靠区分），需要共享时应放在 workspace 或经审批的独立根下。
 *
 * 创建日期：2026/11/07 作者:tianlong
 */
public final class TLUserWorkspace implements TLAiAgentParamString {

    /** 覆盖 workspace 根的系统属性（默认 "workspace"，按进程 CWD 解析；测试用临时目录覆盖） */
    public static final String P_WORKSPACE_ROOT = "tlobject.workspaceRoot";

    /**
     * 工具 → 路径参数名。新增文件工具必须在此登记，否则不受路径守卫约束。
     *
     * <p><b>与 workspaceMode 的契约：</b>{@link #evaluate} 把相对路径解析到
     * {@code workspace/{userId}}，因此只对配置了 {@code workspaceMode=true} 的技能有效。
     * 执行器启用 {@code pathGuard} 却没有对应的 {@code workspaceMode} 属于不支持配置：
     * 技能仍写入旧根目录，守卫却检查 workspace（fail-open）。{@code view_image} 与 MCP
     * 工具由各自的守卫处理，故意不在此登记；通过 {@code skillName} 改名的技能必须按
     * 新的函数名登记，否则同样不受约束。</p>
     */
    private static final Map<String, List<String>> PATH_ARGS = Map.of(
            "file_operation", List.of("path"),
            "word", List.of("path", "output")
    );

    /**
     * {@code data/{uid}/} 下已知的 per-user 子目录（路径第二段），归属：
     * uploads（旧上传件位置，仅为兼容存量；新上传件在 workspace）、
     * attachments（系统自动附件）、longterm_memory（{@code TLLongTermMemoryModule}）、
     * tasks（任务中心）、traces（Provider trace）、session_store（{@code TLSessionManager}）、
     * scheduled_tasks（{@code TLScheduleTaskSkill}）。
     *
     * <p>共享目录若第二段恰好与这些名字相同，会被 fail-closed 判为跨用户（已知取舍，见类注释）。</p>
     */
    private static final Set<String> USER_DATA_SUBDIRS = Set.of(
            "uploads", "attachments", "longterm_memory", "tasks", "traces",
            "session_store", "scheduled_tasks");

    private TLUserWorkspace() {}

    /** userId：systemArgs → args → sessionId 兜底（sessionId 同样是 systemArgs 优先） */
    public static String userId(TLMsg msg) {
        if (msg == null) return "default";
        String uid = usableId(msg.getSystemParam(AI_P_USERID, null));
        if (uid != null) return uid;
        if (msg.containsParam(AI_P_USERID))
            uid = usableId(msg.getStringParam(AI_P_USERID, ""));
        if (uid != null) return uid;
        String sid = usableId(msg.getSystemParam(AI_P_SESSIONID, null));
        if (sid != null) return sid;
        sid = usableId(msg.getStringParam(AI_P_SESSIONID, ""));
        return sid != null ? sid : "default";
    }

    /**
     * 可用身份值：null / 空白 / 字面量 {@code "null"}（大小写不敏感，trim 后）都视为未提供。
     * 与 {@code TLMsgUtils.getSystemParamNonEmpty} 同口径（后者只认精确 "null"），
     * 用于 systemArgs/args 的 userId 与 sessionId 兜底判定。
     */
    private static String usableId(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return s;
    }

    /** workspace 基础根（绝对化、规范化） */
    public static Path baseRoot() {
        String raw = System.getProperty(P_WORKSPACE_ROOT);
        if (raw != null) raw = raw.trim();
        if (raw == null || raw.isEmpty()) raw = "workspace";
        return Paths.get(raw).toAbsolutePath().normalize();
    }

    public static Path userRoot(String userId) {
        return baseRoot().resolve(TLPathSafety.safeSegment(userId, "default")).normalize();
    }

    public static Path userRoot(TLMsg msg) {
        return userRoot(userId(msg));
    }

    /** 用户 workspace 根，按需创建；创建失败不抛（真正的读写会给出准确错误） */
    public static Path ensureUserRoot(TLMsg msg) {
        Path p = userRoot(msg);
        try { Files.createDirectories(p); } catch (Exception ignored) { }
        return p;
    }

    /** 用户上传件目录：workspace/{userId}/uploads（绝对、规范化） */
    public static Path uploadsDir(String userId) {
        return userRoot(userId).resolve("uploads").normalize();
    }

    public static Path uploadsDir(TLMsg msg) {
        return uploadsDir(userId(msg));
    }

    /** 上传件目录，按需创建；创建失败不抛（真正的写入会给出准确错误） */
    public static Path ensureUploadsDir(TLMsg msg) {
        Path p = uploadsDir(msg);
        try { Files.createDirectories(p); } catch (Exception ignored) { }
        return p;
    }

    /**
     * 解析上传件路径（只计算，不保证存在）：相对路径按 workspace/{userId} 解析
     * （前端 {@code uploads/xxx} 即落到 {@code workspace/{uid}/uploads/xxx}）；绝对路径原样规范化。
     */
    public static Path resolveUploadPath(String userId, String raw) {
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) p = userRoot(userId).resolve(p);
        return p.normalize().toAbsolutePath();
    }

    /**
     * 查找上传件实际文件（只返回存在的普通文件，否则 null）：
     * <ul>
     *   <li>相对路径：先 workspace/{uid}/{raw}，再旧 data/{uid}/{raw}；</li>
     *   <li>绝对路径：先原样；若落在旧 data/{uid}/uploads 内且文件已不在，
     *       按文件名回退到 workspace/{uid}/uploads。</li>
     * </ul>
     */
    public static Path resolveExistingUpload(String userId, String raw) {
        String seg = TLPathSafety.safeSegment(userId, "default");
        Path p;
        try { p = Paths.get(raw); } catch (InvalidPathException e) { return null; }
        Path dataUser = Paths.get("data").toAbsolutePath().normalize().resolve(seg);
        if (!p.isAbsolute()) {
            Path inWorkspace = userRoot(seg).resolve(raw).normalize().toAbsolutePath();
            if (Files.isRegularFile(inWorkspace)) return inWorkspace;
            Path legacy = dataUser.resolve(raw).normalize().toAbsolutePath();
            return Files.isRegularFile(legacy) ? legacy : null;
        }
        Path abs = p.normalize().toAbsolutePath();
        if (Files.isRegularFile(abs)) return abs;
        if (isInside(abs, dataUser.resolve("uploads"))) {
            Path mapped = uploadsDir(seg).resolve(abs.getFileName()).normalize().toAbsolutePath();
            if (Files.isRegularFile(mapped)) return mapped;
        }
        return null;
    }

    /** 相对路径按用户 workspace 解析；绝对路径原样规范化 */
    public static Path resolveInput(TLMsg msg, String raw) {
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) p = userRoot(msg).resolve(p);
        return p.normalize().toAbsolutePath();
    }

    public static boolean isInside(Path p, Path root) {
        return p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    /** 自己的 data/{uid} 与 data/documents/{uid} */
    public static boolean isOwnData(Path p, String userId) {
        String seg = TLPathSafety.safeSegment(userId, "default");
        Path data = Paths.get("data").toAbsolutePath().normalize();
        return isInside(p, data.resolve(seg)) || isInside(p, data.resolve("documents").resolve(seg));
    }

    /**
     * 是否跨用户：workspace/{其他 uid}/... 或 data/{其他 uid}/已知子目录、data/documents/{其他 uid}。
     *
     * <p>入参应为绝对规范化路径（相对路径会按进程 CWD 解析，通常不是调用方本意）；正常入口是
     * {@link #evaluate}，它先用 {@link #resolveInput} 解析。workspace 根下的单段条目会做一次
     * 文件系统类型检查（{@link Files#isDirectory}）以区分“别人的 workspace 目录”和遗留文件，
     * 因此本方法是路径段判定 + 这一处文件系统查询。</p>
     */
    public static boolean isCrossUser(Path p, String userId) {
        String seg = TLPathSafety.safeSegment(userId, "default");
        Path abs = p.toAbsolutePath().normalize();

        Path ws = baseRoot();
        if (isInside(abs, ws) && !abs.equals(ws)) {
            Path rel = ws.relativize(abs);
            // 首段不是自己即为跨用户。单段条目按类型区分：已存在的目录
            // workspace/{其他 uid} 是别人的 workspace（硬拒绝）；遗留文件或不存在
            // 的单段路径仍按越界走审批。段比较遵循文件系统大小写语义。
            if (!sameSegment(rel.getName(0).toString(), seg)
                    && (rel.getNameCount() >= 2 || Files.isDirectory(abs))) return true;
        }

        Path data = Paths.get("data").toAbsolutePath().normalize();
        if (isInside(abs, data) && !abs.equals(data)) {
            Path rel = data.relativize(abs);
            // 能走进这里说明不是 data 根本身，rel 至少一段
            String first = rel.getName(0).toString();
            if (sameSegment("documents", first) && rel.getNameCount() >= 2) {
                if (!sameSegment(rel.getName(1).toString(), seg)) return true;
            }
            if (rel.getNameCount() >= 2
                    && isUserDataSubdir(rel.getName(1).toString())
                    && !sameSegment(first, seg)) return true;
        }
        return false;
    }

    /**
     * 可信区：自己的 workspace 或自己的 data。
     * 入参应为绝对规范化路径（见 {@link #isCrossUser}）。
     */
    public static boolean isTrusted(Path p, String userId) {
        return isInside(p, userRoot(userId)) || isOwnData(p, userId);
    }

    /** 路径段比较：Windows 大小写不敏感（Path.equals），POSIX 保持敏感 */
    private static boolean sameSegment(String a, String b) {
        return Paths.get(a).equals(Paths.get(b));
    }

    /** 第二段是否为已知 per-user 数据子目录（大小写遵循平台语义） */
    private static boolean isUserDataSubdir(String s) {
        for (String sub : USER_DATA_SUBDIRS) {
            if (sameSegment(sub, s)) return true;
        }
        return false;
    }

    /**
     * 评估一次工具调用的路径——入口方法：先按 userId 把相对路径解析到
     * {@code workspace/{userId}}，再做跨用户/可信区判定。未登记的工具一律 ALLOW（保持旧行为）。
     *
     * <p><b>与 workspaceMode 的契约：</b>相对路径会解析到 {@code workspace/{userId}}，所以本方法
     * 只对配置了 {@code workspaceMode=true} 的技能有效。执行器启用 {@code pathGuard} 却没有
     * 对应的 {@code workspaceMode} 属于不支持配置：技能仍写旧根目录，守卫却检查 workspace
     * （fail-open）。{@code view_image} 与 MCP 工具由各自的守卫处理，故意不在此登记；通过
     * {@code skillName} 改名的技能必须按新的函数名登记。详见 {@link #PATH_ARGS}。</p>
     *
     * <p>非法路径按 DENY 返回，不抛异常：{@link InvalidPathException}（非法字符）与
     * {@link java.io.IOError}（Windows 盘符相对路径如 {@code a:b.py} 取不到该盘工作目录）同样处理。</p>
     */
    public static Decision evaluate(String toolName, Map<String, Object> args, TLMsg msg) {
        List<String> keys = toolName == null ? null : PATH_ARGS.get(toolName);
        if (keys == null || args == null) return Decision.allow();
        String uid = userId(msg);
        List<Path> all = new ArrayList<>();
        List<Path> outside = new ArrayList<>();
        for (String key : keys) {
            Object v = args.get(key);
            if (!(v instanceof String)) continue;
            String raw = ((String) v).trim();
            if (raw.isEmpty()) continue;
            Path p;
            try {
                p = resolveInput(msg, raw);
            } catch (InvalidPathException | java.io.IOError e) {
                // IOError 是 Error 族：Windows 盘符相对路径（a:b.py）的 toAbsolutePath 会抛，
                // 不在这里转成 DENY 就会穿过执行器的 catch (Exception) 杀死工作线程
                return Decision.deny("非法路径: " + raw);
            }
            all.add(p);
            if (isCrossUser(p, uid)) {
                return Decision.deny(p, "无权访问其他用户目录: " + p);
            }
            if (!isTrusted(p, uid)) outside.add(p);
        }
        return outside.isEmpty() ? Decision.allow() : Decision.approval(all, outside);
    }

    public static final class Decision {
        public enum Kind { ALLOW, NEED_APPROVAL, DENY }

        public final Kind kind;
        /** 所有已评估路径 */
        public final List<Path> paths;
        /** 需要审批的路径（超出可信区） */
        public final List<Path> outsidePaths;
        /** 拒绝文案；非 DENY 时为空串 */
        public final String error;

        private Decision(Kind kind, List<Path> paths, List<Path> outsidePaths, String error) {
            this.kind = kind;
            this.paths = Collections.unmodifiableList(paths);
            this.outsidePaths = Collections.unmodifiableList(outsidePaths);
            this.error = error == null ? "" : error;
        }

        static Decision allow() {
            return new Decision(Kind.ALLOW, Collections.emptyList(), Collections.emptyList(), "");
        }

        static Decision approval(List<Path> all, List<Path> outside) {
            return new Decision(Kind.NEED_APPROVAL, all, outside, "");
        }

        /** 确定性拒绝（如跨用户），携带触发拒绝的路径供结构化展示 */
        static Decision deny(Path p, String error) {
            List<Path> single = Collections.singletonList(p);
            return new Decision(Kind.DENY, single, single, error);
        }

        /** 无合法路径可携带的拒绝（如非法路径字符串） */
        static Decision deny(String error) {
            return new Decision(Kind.DENY, Collections.emptyList(), Collections.emptyList(), error);
        }
    }
}
