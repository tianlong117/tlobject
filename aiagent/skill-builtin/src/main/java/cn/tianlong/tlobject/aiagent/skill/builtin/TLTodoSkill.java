package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.IObject;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.IOError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 任务清单技能：维护当前任务的多步清单（"活清单"）。
 * 函数名由配置 {@code skillName="todo"} 生效；裸接线（未配 skillName）时基类按
 * "params.skillName → 模块名"顺序预填（super.setModuleParams() 先于本类方法体执行完，
 * 类内再写 fallback 是死代码——故不保留，函数名缺失的兜底去看基类）。
 *
 * <p>与 planTask（一次性规划）互补：planTask 生成清单一版即止，本技能让清单在
 * 每轮对话里持续更新——模型每完成一步带完整列表重写一次，回执自带全表，
 * 任务接续时先 read 对齐进度。
 *
 * <h3>消息接口（SKILL_EXECUTE）</h3>
 * <pre>{@code
 *   {operation: "write", items: [{text, status}, ...或裸字符串...]} → 渲染回执（同时落盘 + 发 msgBus todoEvent）
 *   {operation: "read"}                                            → 读回当前清单并渲染
 * }</pre>
 * 会话/用户标识经 systemArgs 传入（执行器已接线）：sessionId / userId，缺省 "default"；
 * 两者都做文件名净化，落盘 {@code {dataDir}/{userId}/tasks/{sessionId}.md}（人可读，解析只认 "- [" 行）。
 *
 * <p>状态归一：pending / in_progress / completed / failed（乱值一律 pending，容忍 LLM 用词随意）。
 * 标记：[ ] / [~] / [x] / [✗]，与 planTask 清单风格一致。
 *
 * <p>msgBus 事件（topic todoEvent）供 webui 等订阅者渲染；发布失败绝不影响工具结果
 * （模块缺失时裸按名 putMsg 会 shutdown 整个应用——必须走 getModuleInFactory + instanceof 守卫）。
 *
 * 创建日期：2026/10/11
 * 作者:tianlong
 */
public class TLTodoSkill extends TLBaseSkill {

    /**
     * 落盘根目录（params 的 dataDir 可覆写，测试用）。
     * 默认 "data" 相对**进程 CWD**解析：双击 bat 启动=仓库根、IDE 启动=模块目录（仓库
     * 既有困惑点，参照 SkillInstallerSelfTest 同类 CWD 说明）；需要固定位置就在 XML 里配绝对路径。
     */
    private String dataDir = "data";

    // 状态归一后的四个取值（也是 [ ] 标记的来源）
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    // 换行归一/trim 后为空的项文字占位——不让空项在回执与文件里变成裸 "- [ ] " 行
    private static final String EMPTY_TEXT_PLACEHOLDER = "(未命名任务)";

    // Windows 保留设备名（不区分大小写；落成文件/目录名会创建失败）。命中则加 "_" 前缀。
    private static final Set<String> RESERVED_WIN = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    public TLTodoSkill() {
        super();
    }

    public TLTodoSkill(String name) {
        super(name);
    }

    public TLTodoSkill(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();

        // skillName 不在此兜底：super.setModuleParams()（上面第一行）已按
        // "params.skillName → 模块名"顺序填完，本类再写 fallback 恒不命中（死代码）。
        // 函数名 = todo 由配置 skillName="todo" 生效；裸接线时函数名=模块名。
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "维护当前任务的多步清单。operation=write 全量重写清单"
                    + "（items 为 [{text,status}]，status: pending/in_progress/completed/failed；"
                    + "每完成一步就带完整列表重写一次）；operation=read 读回当前清单"
                    + "（任务接续/对齐进度时先读）。多步任务开始时，把计划清单写入。";

        if (params != null && params.get("dataDir") != null && !params.get("dataDir").isEmpty())
            dataDir = params.get("dataDir");

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> opProp = new LinkedHashMap<>();
            opProp.put("type", "string");
            opProp.put("description", "write = 全量重写清单（必须带 items）；read = 读回当前清单");
            opProp.put("enum", new String[]{"write", "read"});
            opProp.put("required", true);
            parameterSchema.put("operation", opProp);

            Map<String, Object> itemProp = new LinkedHashMap<>();
            itemProp.put("type", "object");
            Map<String, Object> itemProps = new LinkedHashMap<>();
            itemProps.put("text", prop("string", "任务项文字"));
            itemProps.put("status", prop("string", "pending | in_progress | completed | failed"));
            itemProp.put("properties", itemProps);

            Map<String, Object> itemsProp = new LinkedHashMap<>();
            itemsProp.put("type", "array");
            itemsProp.put("description", "write 必填：完整任务列表（全量重写语义，未列出=已删除）。"
                    + "元素为 {text,status} 或纯文字字符串（=pending）。每完成一步把整张表带上重写一次。");
            itemsProp.put("items", itemProp);
            parameterSchema.put("items", itemsProp);
        }
    }

    /** schema 属性小工厂：单层 {type, description} 属性 */
    private static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    // ======================== 执行入口 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        try {
            Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
            if (input.isEmpty()) {
                // 旧式调用兼容：参数平铺在消息上（无 skillInput 包装）
                input = new LinkedHashMap<>();
                if (msg.containsParam("operation")) input.put("operation", msg.getParam("operation"));
                if (msg.containsParam("items")) input.put("items", msg.getParam("items"));
            }

            // 先字符串化再判空：显式 JSON null / 非字符串（Gson 数字是 Double）直接强转会 NPE/CCE
            String operation = asString(input.get("operation"), "").trim();
            String op = operation.toLowerCase(Locale.ROOT);
            if (op.isEmpty())
                return err("operation is required: write（全量重写清单）或 read（读回清单）");
            if (!op.equals("write") && !op.equals("read"))
                return err("Unknown operation: " + operation + " (valid: write, read)");

            // 会话/用户标识：执行器以 systemArgs 传（sessionId/userId），缺省 default；
            // 两者都要净化——它们直接进文件路径，path injection（../、盘符、分隔符）必须挡在盘外
            String sessionId = sanitizeId(asString(msg.getSystemParam(AI_P_SESSIONID, "default"), "default"), "default");
            String userId = sanitizeId(asString(msg.getSystemParam("userId", "default"), "default"), "default");
            Path file = Paths.get(dataDir).resolve(userId).resolve("tasks").resolve(sessionId + ".md");

            if (op.equals("read")) return doRead(file);
            return doWrite(file, sessionId, userId, input.get("items"));
        } catch (Exception | IOError e) {
            // 顶层防御（基类 guard 是最后一道网，正常路径不依赖它）：IOError 是 Error（Windows 盘符
            // 相对路径等），catch(Exception) 接不住，逃出会经框架链 shutdown 整个应用
            putLog("Todo Skill error: " + e, LogLevel.ERROR);
            return err("Todo operation failed: " + msgOf(e));
        }
    }

    // ======================== write / read ========================

    private TLMsg doWrite(Path file, String sessionId, String userId, Object itemsRaw) {
        if (!(itemsRaw instanceof List))
            return err("items is required for write: a list of {text,status} (status: pending/in_progress/completed/failed)");
        List<TodoItem> items = parseItems((List<?>) itemsRaw);
        int[] counts = countByStatus(items);
        String rendered = render(items, counts);
        try {
            Path dir = file.getParent();
            if (dir != null) Files.createDirectories(dir);
            // 文件 = 可选头（人可读，解析只认 "- [" 行）+ 渲染行；全量重写语义
            String content = "# 任务清单（sessionId=" + sessionId + ", userId=" + userId + "）\n\n" + rendered;
            atomicWrite(file, content);   // 同目录临时文件 + 原子替换，不留半截清单
            putLog("Todo list written: " + file + " (" + items.size() + " items)", LogLevel.DEBUG);
            publishTodoEvent(rendered, sessionId, userId, counts);
            return ok(rendered);
        } catch (Exception | IOError e) {   // SecurityException/InvalidPathException 等也别逃出技能层
            return err("Todo write error: " + msgOf(e));
        }
    }

    private TLMsg doRead(Path file) {
        try {
            if (!Files.exists(file)) return ok("（当前无任务清单）");
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            List<TodoItem> items = parseChecklist(content);
            return ok(render(items, countByStatus(items)));
        } catch (Exception | IOError e) {
            return err("Todo read error: " + msgOf(e));
        }
    }

    // ======================== 解析 / 归一 / 渲染 ========================

    /** write 的 items 解析：元素 Map{text,status} / 裸 String（=pending）/ 其它（字符串化当 text、pending），一律容忍消化 */
    @SuppressWarnings("unchecked")
    private static List<TodoItem> parseItems(List<?> raw) {
        List<TodoItem> items = new ArrayList<>();
        for (Object el : raw) {
            String text;
            String status;
            if (el instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) el;
                text = normalizeText(asString(m.get("text"), ""));
                status = normalizeStatus(m.get("status"));
            } else if (el instanceof String) {
                text = normalizeText((String) el);
                status = STATUS_PENDING;
            } else {
                text = normalizeText(asString(el, ""));   // 数字/布尔等乱入：字符串化当文字；null 元素 → 占位
                status = STATUS_PENDING;
            }
            items.add(new TodoItem(text, status));
        }
        return items;
    }

    /**
     * 项文字归一：{@code \r\n | \r | \n} → 空格后 trim（渲染是单行格式——text 里残留的换行会被
     * read 解析成独立项，破坏"write 回执 == read 回执"的写读同构不变量：3 项写进去 4 项读回来）；
     * trim 顺带消掉渲染行尾空白（"- [ ] abc " 读回会被 strip 成 "abc"，不等则同构破裂）。
     * 归一后为空则记 {@link #EMPTY_TEXT_PLACEHOLDER}——弃项会造成静默丢数据，不做。
     */
    static String normalizeText(String raw) {
        String t = raw == null ? "" : raw.replaceAll("\\r\\n|\\r|\\n", " ").trim();
        return t.isEmpty() ? EMPTY_TEXT_PLACEHOLDER : t;
    }

    /** 状态归一：含 progress→in_progress；done/complete/completed/x→completed；fail 前缀→failed；其余→pending */
    static String normalizeStatus(Object raw) {
        String v = asString(raw, "").trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return STATUS_PENDING;
        if (v.contains("progress")) return STATUS_IN_PROGRESS;
        if (v.equals("done") || v.equals("complete") || v.equals("completed") || v.equals("x"))
            return STATUS_COMPLETED;
        if (v.startsWith("fail")) return STATUS_FAILED;
        return STATUS_PENDING;
    }

    /** 解析清单文件：只认 "- [m] text" 行（可选头/标题/空行全部忽略） */
    private static List<TodoItem> parseChecklist(String content) {
        List<TodoItem> items = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            String t = line.strip();        // 顺带剥掉 CRLF 的 \r
            if (!t.startsWith("- [")) continue;
            int close = t.indexOf(']');
            if (close < 3) continue;        // 形如 "- [abc"（无闭合）——忽略该行，不猜
            String mark = t.substring(3, close);
            String text = t.substring(close + 1).trim();
            items.add(new TodoItem(text, statusFromMarker(mark)));
        }
        return items;
    }

    private static String statusFromMarker(String mark) {
        String m = mark.trim();
        if (m.equals("~")) return STATUS_IN_PROGRESS;
        if (m.equalsIgnoreCase("x")) return STATUS_COMPLETED;
        if (m.equals("✗")) return STATUS_FAILED;
        return STATUS_PENDING;
    }

    private static String marker(String status) {
        if (STATUS_IN_PROGRESS.equals(status)) return "~";
        if (STATUS_COMPLETED.equals(status)) return "x";
        if (STATUS_FAILED.equals(status)) return "✗";
        return " ";
    }

    /** 计数：{pending, inProgress, completed, failed} */
    private static int[] countByStatus(List<TodoItem> items) {
        int[] c = new int[4];
        for (TodoItem it : items) {
            if (STATUS_IN_PROGRESS.equals(it.status)) c[1]++;
            else if (STATUS_COMPLETED.equals(it.status)) c[2]++;
            else if (STATUS_FAILED.equals(it.status)) c[3]++;
            else c[0]++;
        }
        return c;
    }

    /**
     * 渲染（回执与文件主体同格式，人可读、可被 planTask 风格复用）：
     * <pre>
     * 任务清单（3 项：1 进行中 / 1 完成 / 1 待执行）：
     * - [~] 进行中的事项
     * - [x] 已完成事项
     * - [ ] 待执行事项
     * </pre>
     * 计数按实际状态拼，0 的省略（失败项显示为 "N 失败"）。
     */
    private static String render(List<TodoItem> items, int[] c) {
        List<String> counts = new ArrayList<>();
        if (c[1] > 0) counts.add(c[1] + " 进行中");
        if (c[2] > 0) counts.add(c[2] + " 完成");
        if (c[0] > 0) counts.add(c[0] + " 待执行");
        if (c[3] > 0) counts.add(c[3] + " 失败");
        StringBuilder sb = new StringBuilder();
        sb.append("任务清单（").append(items.size()).append(" 项");
        if (!counts.isEmpty()) sb.append("：").append(String.join(" / ", counts));
        sb.append("）：").append("\n");
        for (TodoItem it : items)
            sb.append("- [").append(marker(it.status)).append("] ").append(it.text).append('\n');
        return sb.toString();
    }

    // ======================== 文件名净化 ========================

    /**
     * 会话/用户标识归一（进文件路径的唯一关口），升级自"非法字符→_"版，三点防塌缩/防别名：
     * <ol>
     *   <li>换过字符（或拦过保留名）就追加 {@code "_" + hex(raw.hashCode())} 短哈希——
     *       旧版把非法字符全换成 "_"，"用户甲"/"用户乙" 都塌缩成 "___"，会互相交叉读到别人清单；
     *       干净 ASCII id（如 webchat_admin_1）不加哈希，文件名与旧行为逐字节一致。</li>
     *   <li>首尾点替换为 "_"："."/".."/"..." 与 "aliasA."（Windows 会把 "aliasA." 落成 "aliasA"）不再
     *       与 "aliasA" 撞同一个文件。</li>
     *   <li>命中 {@link #RESERVED_WIN}（CON/PRN/AUX/NUL/COM1-9/LPT1-9）加 "_" 前缀——
     *       否则落盘直接失败，且把 OS 文案泄给模型。</li>
     * </ol>
     * 空/全空白归 def（def 自身也走完整净化）。哈希是防塌缩的工程折衷：哈希碰撞理论存在，
     * 但远优于必然塌缩；文件头写有落盘所用的净化名（含短哈希），可据此核对归属。
     */
    static String sanitizeId(String raw, String def) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return def;
        String r = s.replaceAll("[^A-Za-z0-9_.-]", "_");
        r = r.replaceAll("^\\.+", "_").replaceAll("\\.+$", "_");   // 首尾点（含 "."/".."/"..."）
        if (r.isEmpty()) r = "_";
        if (RESERVED_WIN.contains(r.toUpperCase(Locale.ROOT))) r = "_" + r;
        if (!r.equals(s)) r = r + "_" + Integer.toHexString(s.hashCode());   // 有替换即加短哈希，不同 id 不塌缩
        return r;
    }

    // ======================== msgBus 事件 ========================

    /**
     * 经消息总线发布清单变更事件（topic=todoEvent），供 webui 等订阅者渲染。
     * 必须走 getModuleInFactory + instanceof 守卫：裸按名 putMsg 在模块缺失时
     * 会 moduleFactory.shutdown(-1) 关掉整个应用（框架级陷阱）；
     * 整段 try/catch(Throwable)：发事件失败绝不能影响工具结果（无工厂环境 getModuleInFactory
     * 本身就会 NPE，也在吞掉之列）。
     */
    private void publishTodoEvent(String text, String sessionId, String userId, int[] c) {
        try {
            Object bus = getModuleInFactory("msgBus");
            if (!(bus instanceof IObject)) return;
            TLMsg evt = createMsg().setAction("todoEvent")
                    .setParam("text", text)
                    .setParam("sessionId", sessionId)
                    .setParam("userId", userId)
                    .setParam("pendingCount", c[0])
                    .setParam("inProgressCount", c[1])
                    .setParam("completedCount", c[2])
                    .setParam("failedCount", c[3]);
            evt.setDestination("todoEvent");   // 总线按 destination 路由到订阅者
            putMsg((IObject) bus, evt);
        } catch (Throwable t) {
            try {
                putLog("todo 事件发布失败: " + t, LogLevel.DEBUG);
            } catch (Throwable ignored) { }
        }
    }

    // ======================== 小工具 ========================

    /**
     * 同目录临时文件 + 原子替换（file_operation atomicWrite 同款先例；不引外模块）。
     * 不支持 ATOMIC_MOVE 的文件系统（部分网络盘）回退普通 move；finally 清临时文件，
     * 清理异常不得吞掉 move 的原始异常。
     */
    private static void atomicWrite(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, ".todo", ".tmp");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    private TLMsg ok(String s) { return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, s); }

    private TLMsg err(String s) { return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT, s); }

    /** 异常文案：message 为 null 时退化为类名，不把 "null" 泄给 LLM */
    private static String msgOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** 单项：文字 + 归一状态 */
    private static final class TodoItem {
        final String text;
        final String status;

        TodoItem(String text, String status) {
            this.text = text;
            this.status = status;
        }
    }
}
