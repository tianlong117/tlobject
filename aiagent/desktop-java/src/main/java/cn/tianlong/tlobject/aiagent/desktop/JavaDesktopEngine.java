package cn.tianlong.tlobject.aiagent.desktop;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面自动化引擎（Java 版 desktop skill 的底层实现）。
 *
 * 纯 JDK：截图/鼠标/键盘全部走 {@link Robot}——零外部依赖（对比 Python 版的 pyautogui+mss）。
 * 与 Python 版 desktop_agent.py 动作对齐并补足：
 *   screenshot / click / type / move / scroll / get_screen_size
 *   + double_click / drag / key（组合键）/ wait / get_mouse_position / paste（剪贴板，中文输入的正解）
 *
 * DPI 处理（策略：明确报错不误导）：
 *   Robot 截图/点按使用同一坐标体系；若 JVM 非 DPI 感知且系统缩放>100%，Windows 会对该进程
 *   做坐标虚拟化，AWT 报告的逻辑尺寸与物理像素不一致——LLM 从截图上读的坐标会与真实位置错位，
 *   点按将误操作桌面。故初始化时用双信号检测（AWT 变换缩放 + 截图尺寸交叉验证），不一致则**点按/输入类动作
 *   直接拒绝**（截图与坐标查询仍可用），错误文案给出修复方法。
 *
 * 创建日期：2026/10/04 作者:tianlong
 */
public class JavaDesktopEngine {

    /** 截图最大高度（像素）；超出按比例缩小，保证 base64 体量可控（4K 全屏 PNG 可达数 MB）。0=不限 */
    private final int maxShotHeight;

    private final Robot robot;
    /** 物理像素 / AWT 报告尺寸。1.0=无缩放或 JVM 已 DPI 感知；>1.0=坐标错位风险 */
    private double dpiScale = 1.0;
    /** 检测来源：transform（AWT 变换缩放）/ capture-probe（截图尺寸交叉验证）/ consistent（两信号均一致） */
    private String dpiSource = "consistent";

    private int screenW;    // AWT 报告尺寸（坐标体系）
    private int screenH;
    private int physW;      // 物理像素（截图尺寸）
    private int physH;

    public JavaDesktopEngine(int maxShotHeight) throws AWTException {
        this.maxShotHeight = Math.max(0, maxShotHeight);
        if (GraphicsEnvironment.isHeadless()) {
            throw new AWTException("当前 JVM 处于 headless 模式，无法使用桌面自动化（启动参数勿带 -Djava.awt.headless=true）");
        }
        this.robot = new Robot();
        detectDpi();
    }

    public int getScreenWidth() { return screenW; }
    public int getScreenHeight() { return screenH; }
    public double getDpiScale() { return dpiScale; }
    public String getDpiSource() { return dpiSource; }

    // ======================== DPI 检测 ========================

    private void detectDpi() {
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        screenW = d.width;
        screenH = d.height;
        physW = d.width;
        physH = d.height;
        dpiScale = 1.0;
        // 信号1：AWT 变换缩放（JDK 官方 API；非 DPI 感知 JVM 在缩放系统上 >1.0）
        try {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            double tx = gc.getDefaultTransform().getScaleX();
            if (tx > 1.02) {
                dpiScale = tx;
                physW = (int) Math.round(screenW * tx);
                physH = (int) Math.round(screenH * tx);
                dpiSource = "transform";
                return;
            }
        } catch (Throwable ignored) { }
        // 信号2：截图尺寸交叉验证（构造期直接跑一次，保证早期发现）
        verifyCaptureScaling();
    }

    /**
     * 零依赖 DPI 检测（双信号，任一命中即视为坐标错位风险）：
     *  信号1：AWT 变换缩放——非 DPI 感知 JVM 在缩放系统上 getDefaultTransform()>1.0；
     *  信号2：截图尺寸交叉验证——请求 tkW 宽截图，若返回像素数明显更大（Windows DPI 虚拟化
     *         把 AWT 坐标空间截成物理像素），说明坐标/截图不同空间，点按将错位。
     * 注：JNA 平台库（GetSystemMetrics 精确物理尺寸）不在本模块依赖内——保持零下载。
     */
    private void verifyCaptureScaling() {
        if (dpiScale > 1.02) return;   // 信号1 已命中
        try {
            java.awt.image.BufferedImage probe =
                    robot.createScreenCapture(new java.awt.Rectangle(0, 0, screenW, screenH));
            if (probe.getWidth() > screenW * 1.02) {   // 信号2 命中
                dpiScale = (double) probe.getWidth() / screenW;
                physW = probe.getWidth();
                physH = probe.getHeight();
                dpiSource = "capture-probe";
            }
        } catch (Throwable ignored) {
            // 探测失败（权限/显示变化）：按已有一致处理（100% 缩放机器上必然一致）
        }
    }

    private boolean dpiMismatch() {
        return dpiScale > 1.02;
    }

    /** 错位时点按/输入类动作的统一拒绝文案（明确说清 + 给修复方法） */
    private Map<String, Object> dpiReject() {
        return err("JVM 未 DPI 感知而系统缩放约 " + Math.round((dpiScale - 1) * 100) + "%："
                + "AWT 逻辑坐标(" + screenW + "x" + screenH + ")与物理像素(" + physW + "x" + physH + ")不一致，"
                + "鼠标/键盘操作会坐标错位（可能误点）。修复：把系统显示缩放调为 100%。"
                + "截图与坐标查询仍可用。");
    }

    // ======================== 动作分发 ========================

    private static final String VALID_ACTIONS =
            "screenshot, get_screen_size, get_mouse_position, move, click, double_click, "
            + "drag, scroll, type, key, paste, wait";

    public Map<String, Object> execute(String action, Map<String, Object> in) {
        if (action == null || action.isEmpty()) return err("action is required. Valid: " + VALID_ACTIONS);
        try {
            switch (action) {
                case "screenshot":         return screenshot(in);
                case "get_screen_size":    return screenSize(in);
                case "get_mouse_position": return mousePosition(in);
                case "move":               return move(in);
                case "click":              return click(in, 1);
                case "double_click":       return click(in, 2);
                case "drag":               return drag(in);
                case "scroll":             return scroll(in);
                case "type":               return type(in);
                case "key":                return key(in);
                case "paste":              return paste(in);
                case "wait":               return wait(in);
                default:
                    return err("unknown action: " + action + " (valid: " + VALID_ACTIONS + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err("interrupted");
        } catch (Exception e) {
            return err(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ======================== 各 action ========================

    private Map<String, Object> screenshot(Map<String, Object> in) throws Exception {
        Integer x = intOf(in, "x"), y = intOf(in, "y");
        Integer w = intOf(in, "width"), h = intOf(in, "height");
        Rectangle rect;
        String clampNotice = null;
        if (x != null && y != null && w != null && h != null && w > 0 && h > 0) {
            // 区域截图越界钳制：不钳会给屏外涂黑的图，LLM 无法区分"黑屏"与"越界"
            int cx = Math.max(0, Math.min(x, screenW - 1));
            int cy = Math.max(0, Math.min(y, screenH - 1));
            int cw = Math.min(w, screenW - cx);
            int ch = Math.min(h, screenH - cy);
            rect = new Rectangle(cx, cy, Math.max(1, cw), Math.max(1, ch));
            if (cx != x || cy != y || cw != w || ch != h) {
                clampNotice = "请求区域 (" + x + "," + y + "," + w + "x" + h + ") 超出屏幕，"
                        + "已钳制为 (" + cx + "," + cy + "," + cw + "x" + ch + ")";
            }
        } else {
            rect = new Rectangle(0, 0, screenW, screenH);   // 主屏可见区域（与 AWT 坐标一致）
        }
        BufferedImage img = robot.createScreenCapture(rect);
        Map<String, Object> out = ok();
        out.put("screenshot_base64", toPngBase64(img));
        out.put("x", rect.x); out.put("y", rect.y);
        out.put("width", rect.width); out.put("height", rect.height);
        out.put("screen_width", screenW); out.put("screen_height", screenH);
        if (clampNotice != null) out.put("notice", clampNotice);
        if (dpiMismatch()) {
            out.put("dpi_notice", "系统缩放约 " + Math.round((dpiScale - 1) * 100)
                    + "%，截图坐标与点按坐标可能错位，点按类操作已禁用");
        }
        return out;
    }

    private Map<String, Object> screenSize(Map<String, Object> in) {
        Map<String, Object> out = ok();
        out.put("width", screenW);     // 坐标体系（LLM 应使用）
        out.put("height", screenH);
        if (dpiMismatch()) {
            out.put("physical_width", physW);
            out.put("physical_height", physH);
            out.put("dpi_notice", "系统缩放导致逻辑/物理像素不一致，点按类操作已禁用");
        }
        return out;
    }

    private Map<String, Object> mousePosition(Map<String, Object> in) {
        Point p = MouseInfo.getPointerInfo().getLocation();
        Map<String, Object> out = ok();
        out.put("x", p.x);
        out.put("y", p.y);
        out.put("screen_width", screenW);
        out.put("screen_height", screenH);
        return out;
    }

    private Map<String, Object> move(Map<String, Object> in) {
        if (dpiMismatch()) return dpiReject();
        Integer x = intOf(in, "x"), y = intOf(in, "y");
        if (x == null || y == null) return err("move requires x,y");
        if (!inBounds(x, y)) return outOfBounds(x, y);
        robot.mouseMove(x, y);
        Map<String, Object> out = ok();
        out.put("moved_to", new int[]{x, y});
        return out;
    }

    private Map<String, Object> click(Map<String, Object> in, int clicks) throws InterruptedException {
        if (dpiMismatch()) return dpiReject();
        Integer x = intOf(in, "x"), y = intOf(in, "y");
        if (x == null || y == null) return err((clicks > 1 ? "double_click" : "click") + " requires x,y");
        if (!inBounds(x, y)) return outOfBounds(x, y);
        String button = strOf(in, "button", "left");
        int mask = buttonMask(button);
        robot.mouseMove(x, y);
        for (int i = 0; i < clicks; i++) {
            robot.mousePress(mask);
            robot.mouseRelease(mask);
            if (i + 1 < clicks) Thread.sleep(60);   // 双击间隔（系统阈值内）
        }
        Map<String, Object> out = ok();
        out.put(clicks > 1 ? "double_clicked" : "clicked", new int[]{x, y});
        out.put("button", button);
        return out;
    }

    private Map<String, Object> drag(Map<String, Object> in) throws InterruptedException {
        if (dpiMismatch()) return dpiReject();
        Integer x1 = intOf(in, "x1"), y1 = intOf(in, "y1");
        Integer x2 = intOf(in, "x2"), y2 = intOf(in, "y2");
        if (x1 == null || y1 == null || x2 == null || y2 == null)
            return err("drag requires x1,y1,x2,y2");
        if (!inBounds(x1, y1) || !inBounds(x2, y2)) return err("drag 坐标超出屏幕范围");
        Integer durationMs = intOf(in, "duration");
        int steps = 12;
        robot.mouseMove(x1, y1);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        for (int i = 1; i <= steps; i++) {
            robot.mouseMove(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps);
            if (durationMs != null && durationMs > 0) Thread.sleep(Math.max(1, durationMs / steps));
        }
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        Map<String, Object> out = ok();
        out.put("dragged", new int[]{x1, y1, x2, y2});
        return out;
    }

    private Map<String, Object> scroll(Map<String, Object> in) {
        if (dpiMismatch()) return dpiReject();
        Integer amount = intOf(in, "amount");
        if (amount == null) {
            // 有 amount 键但解析不出数字 → 参数错误显式报（避免静默滚默认量误导）
            if (in != null && in.get("amount") != null) return err("amount 必须是数字（正=上滚，负=下滚）");
            amount = 3;   // 完全未给才用默认
        }
        robot.mouseWheel(amount);
        Map<String, Object> out = ok();
        out.put("scrolled", amount);
        return out;
    }

    /** 文本输入：仅可靠支持 ASCII（Robot 键码映射所限）。中文请用 paste。 */
    private Map<String, Object> type(Map<String, Object> in) throws InterruptedException {
        if (dpiMismatch()) return dpiReject();
        String text = strOf(in, "text", "");
        if (text.isEmpty()) return err("type requires text");
        if (!isAscii(text)) {
            return err("type 仅支持 ASCII 字符；含中文/非 ASCII 请改用 paste（剪贴板粘贴）");
        }
        Integer intervalMs = intOf(in, "interval");
        int iv = intervalMs != null ? Math.max(0, intervalMs) : 20;
        StringBuilder dropped = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (!typeChar(c)) dropped.append(c);
            if (iv > 0) Thread.sleep(iv);
        }
        Map<String, Object> out = ok();
        if (dropped.length() > 0) {
            // 如实报告：声称"全打了"但实际漏键会让 LLM 基于错误前提继续操作
            out.put("typed", text);
            out.put("dropped_chars", dropped.toString());
            out.put("notice", "以下字符无对应键位被跳过（建议改用 paste）: " + dropped);
        } else {
            out.put("typed", text);
        }
        return out;
    }

    /** @return false=该字符无可用键位（未输入） */
    private boolean typeChar(char c) {
        boolean upper = Character.isUpperCase(c);
        int code = KeyEvent.getExtendedKeyCodeForChar(c);
        if (code == KeyEvent.VK_UNDEFINED) return false;
        if (upper) robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(code);
        robot.keyRelease(code);
        if (upper) robot.keyRelease(KeyEvent.VK_SHIFT);
        return true;
    }

    /** 组合键：key 如 "enter" / "ctrl+c" / "alt+tab" / "ctrl+shift+esc"（+ 连接，按序按下逆序释放）。 */
    private Map<String, Object> key(Map<String, Object> in) {
        if (dpiMismatch()) return dpiReject();
        String combo = strOf(in, "key", "");
        if (combo.isEmpty()) return err("key requires key（如 enter / ctrl+c）");
        String[] parts = combo.toLowerCase().split("\\+");
        int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Integer code = keyCode(parts[i].trim());
            if (code == null) return err("unknown key name: " + parts[i]);
            codes[i] = code;
        }
        for (int code : codes) robot.keyPress(code);
        for (int i = codes.length - 1; i >= 0; i--) robot.keyRelease(codes[i]);   // 逆序释放
        Map<String, Object> out = ok();
        out.put("key", combo);
        return out;
    }

    /** 剪贴板粘贴：写入文本 → Ctrl+V（中文/任意 Unicode 输入的正解） */
    private Map<String, Object> paste(Map<String, Object> in) throws InterruptedException {
        if (dpiMismatch()) return dpiReject();
        String text = strOf(in, "text", "");
        if (text.isEmpty()) return err("paste requires text");
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(new StringSelection(text), null);
        Thread.sleep(80);   // 等剪贴板 owner 就绪（Windows 偶发竞争）
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Map<String, Object> out = ok();
        out.put("pasted", text);
        return out;
    }

    private Map<String, Object> wait(Map<String, Object> in) throws InterruptedException {
        Integer ms = intOf(in, "ms");
        if (ms == null) ms = intOf(in, "amount");
        if (ms == null) return err("wait requires ms（毫秒）");
        int t = Math.max(0, Math.min(ms, 60000));
        Thread.sleep(t);
        Map<String, Object> out = ok();
        out.put("waited_ms", t);
        return out;
    }

    // ======================== 工具方法 ========================

    private Integer keyCode(String name) {
        switch (name) {
            case "enter": case "return": return KeyEvent.VK_ENTER;
            case "tab": return KeyEvent.VK_TAB;
            case "esc": case "escape": return KeyEvent.VK_ESCAPE;
            case "space": return KeyEvent.VK_SPACE;
            case "backspace": return KeyEvent.VK_BACK_SPACE;
            case "delete": case "del": return KeyEvent.VK_DELETE;
            case "home": return KeyEvent.VK_HOME;
            case "end": return KeyEvent.VK_END;
            case "pageup": return KeyEvent.VK_PAGE_UP;
            case "pagedown": return KeyEvent.VK_PAGE_DOWN;
            case "up": return KeyEvent.VK_UP;
            case "down": return KeyEvent.VK_DOWN;
            case "left": return KeyEvent.VK_LEFT;
            case "right": return KeyEvent.VK_RIGHT;
            case "ctrl": case "control": return KeyEvent.VK_CONTROL;
            case "alt": return KeyEvent.VK_ALT;
            case "shift": return KeyEvent.VK_SHIFT;
            case "win": case "meta": return KeyEvent.VK_WINDOWS;
            case "f1": return KeyEvent.VK_F1;
            case "f2": return KeyEvent.VK_F2;
            case "f3": return KeyEvent.VK_F3;
            case "f4": return KeyEvent.VK_F4;
            case "f5": return KeyEvent.VK_F5;
            case "f6": return KeyEvent.VK_F6;
            case "f7": return KeyEvent.VK_F7;
            case "f8": return KeyEvent.VK_F8;
            case "f9": return KeyEvent.VK_F9;
            case "f10": return KeyEvent.VK_F10;
            case "f11": return KeyEvent.VK_F11;
            case "f12": return KeyEvent.VK_F12;
            default:
                if (name.length() == 1) return KeyEvent.getExtendedKeyCodeForChar(name.charAt(0));
                return null;
        }
    }

    private int buttonMask(String button) {
        switch (button == null ? "" : button.toLowerCase()) {
            case "right":  return InputEvent.BUTTON3_DOWN_MASK;
            case "middle": return InputEvent.BUTTON2_DOWN_MASK;
            default:       return InputEvent.BUTTON1_DOWN_MASK;
        }
    }

    private boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < screenW && y < screenH;
    }

    private Map<String, Object> outOfBounds(Integer x, Integer y) {
        return err("坐标 (" + x + "," + y + ") 超出屏幕范围 " + screenW + "x" + screenH);
    }

    private boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    private String toPngBase64(BufferedImage img) throws Exception {
        if (maxShotHeight > 0 && img.getHeight() > maxShotHeight) {
            double scale = (double) maxShotHeight / img.getHeight();
            int nw = Math.max(1, (int) Math.round(img.getWidth() * scale));
            BufferedImage scaled = new BufferedImage(nw, maxShotHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, nw, maxShotHeight, null);
            g.dispose();
            img = scaled;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }

    private Integer intOf(Map<String, Object> in, String k) {
        Object v = in == null ? null : in.get(k);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return (int) Double.parseDouble(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private String strOf(Map<String, Object> in, String k, String def) {
        Object v = in == null ? null : in.get(k);
        return v == null ? def : String.valueOf(v);
    }
}
