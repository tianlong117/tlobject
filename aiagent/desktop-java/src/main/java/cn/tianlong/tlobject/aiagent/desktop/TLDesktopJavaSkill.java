package cn.tianlong.tlobject.aiagent.desktop;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面自动化 Skill（Java 版）。函数名默认 desktop。
 *
 * 与 Python 版（scriptExecutionSkill + desktop_agent.py 子进程）的关系：
 *  - 动作对齐：screenshot/click/type/move/scroll/get_screen_size
 *  - Java 版补足：double_click / drag / key（组合键） / wait / get_mouse_position / paste（中文输入）
 *  - 切换：agent 配置里 sameClassAs="scriptExecutionSkill" ↔ "desktopJavaSkill"（一行）
 *  - 零外部依赖（纯 JDK Robot）；无子进程开销；不受 Python 环境/GBK 编码影响
 *
 * 配置参数（XML params）：
 *  - maxShotHeight: 截图最大高度（默认 1080；超出按比例缩小，控制 base64 体量；0=不限）
 *
 * 创建日期：2026/10/04 作者:tianlong
 */
public class TLDesktopJavaSkill extends TLBaseSkill {

    /** 截图最大高度（像素）；超出按比例缩小 */
    private int maxShotHeight = 1080;

    private volatile JavaDesktopEngine engine;

    public TLDesktopJavaSkill() { super(); }
    public TLDesktopJavaSkill(String name) { super(name); }
    public TLDesktopJavaSkill(String name, TLObjectFactory factory) { super(name, factory); }

    // ======================== 配置与 schema ========================

    @Override
    protected void setModuleParams() {
        if (params != null && params.get("maxShotHeight") != null) {
            try { maxShotHeight = Integer.parseInt(params.get("maxShotHeight")); }
            catch (NumberFormatException e) {
                putLog("Desktop(java) param maxShotHeight parse failed, using default " + maxShotHeight,
                        LogLevel.DEBUG);
            }
        }
        super.setModuleParams();

        if (skillName == null || skillName.isEmpty()) skillName = "desktop";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Desktop automation (screen control). Actions: screenshot (capture screen, optional "
                    + "region x/y/width/height), get_screen_size, get_mouse_position, move(x,y), click(x,y[,button]), "
                    + "double_click(x,y), drag(x1,y1,x2,y2[,duration]), scroll(amount), type(text, ASCII only), "
                    + "key(key, e.g. 'enter', 'ctrl+c'), paste(text, for Chinese/Unicode via clipboard), wait(ms). "
                    + "Use screenshot first to see the screen, then click by pixel coordinates from the screenshot.";

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            parameterSchema.put("action", prop("string",
                    "screenshot|get_screen_size|get_mouse_position|move|click|double_click|drag|scroll|type|key|paste|wait",
                    true));
            parameterSchema.put("x", prop("number", "X coordinate (pixels from screenshot); region x for screenshot"));
            parameterSchema.put("y", prop("number", "Y coordinate"));
            parameterSchema.put("x1", prop("number", "drag start X"));
            parameterSchema.put("y1", prop("number", "drag start Y"));
            parameterSchema.put("x2", prop("number", "drag end X"));
            parameterSchema.put("y2", prop("number", "drag end Y"));
            parameterSchema.put("width", prop("number", "screenshot region width"));
            parameterSchema.put("height", prop("number", "screenshot region height"));
            parameterSchema.put("text", prop("string", "Text for type/paste"));
            parameterSchema.put("key", prop("string", "Key combo for key action, e.g. 'enter', 'ctrl+c', 'alt+tab'"));
            parameterSchema.put("amount", prop("number", "Scroll amount (positive=up, negative=down)"));
            parameterSchema.put("ms", prop("number", "Milliseconds for wait"));
            parameterSchema.put("duration", prop("number", "Optional drag duration in ms"));
            parameterSchema.put("interval", prop("number", "Optional per-char interval ms for type (default 20)"));
            parameterSchema.put("button", prop("string", "Mouse button for click: left (default)|right|middle"));
        }
    }

    private Map<String, Object> prop(String type, String desc) { return prop(type, desc, false); }

    private Map<String, Object> prop(String type, String desc, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        if (required) m.put("required", true);
        return m;
    }

    // ======================== 引擎 ========================

    private JavaDesktopEngine ensureEngine() throws Exception {
        JavaDesktopEngine e = engine;
        if (e != null) return e;
        synchronized (this) {
            if (engine == null) {
                engine = new JavaDesktopEngine(maxShotHeight);
                JavaDesktopEngine created = engine;
                putLog("Desktop(java) engine started: screen=" + created.getScreenWidth() + "x"
                        + created.getScreenHeight() + " dpiScale=" + created.getDpiScale()
                        + " (source=" + created.getDpiSource() + ")", LogLevel.INFO);
            }
            return engine;
        }
    }

    // ======================== 入口 ========================

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            // 直连/命令式调用：参数平铺在消息上
            input = new LinkedHashMap<>();
            for (String k : new String[]{"action", "x", "y", "x1", "y1", "x2", "y2", "width", "height",
                    "text", "key", "amount", "ms", "duration", "interval", "button"}) {
                if (msg.containsParam(k)) input.put(k, msg.getParam(k));
            }
        }
        String action = input.get("action") != null ? String.valueOf(input.get("action")) : "";
        if (action.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: action is required. Valid actions: screenshot, "
                            + "get_screen_size, get_mouse_position, move, click, double_click, drag, scroll, "
                            + "type, key, paste, wait");
        }

        try {
            // ensureEngine 在 try 内：classpath 手接线缺失时引擎构造抛的是 LinkageError，
            // 框架工具执行器不兜 Error——放 try 外工具结果会凭空消失（与 browser-java 同款防护）
            JavaDesktopEngine eng = ensureEngine();
            Map<String, Object> result = eng.execute(action, input);
            Object okParam = result.get("ok");
            boolean ok = okParam == null || Boolean.parseBoolean(String.valueOf(okParam));
            return createMsg().setParam(RESULT, ok)
                    .setParam(AI_P_SKILLOUTPUT, GSON.toJson(result));
        } catch (Exception e) {
            putLog("Desktop(java) action failed: " + e, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Desktop action failed: " + e);
        } catch (LinkageError e) {
            putLog("Desktop(java) action failed (linkage): " + e, LogLevel.WARN);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Desktop action failed (missing classes?): " + e);
        }
    }

    @Override
    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        engine = null;   // 引擎无后台资源（Robot 无生命周期），置空即释放引用
        return super.destroy(fromWho, msg);
    }

    /** 与 browser-java 同款：base64 中 "=" 不能被 HTML 转义（默认 gson 会转），否则前端截图正则失配 */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
}
