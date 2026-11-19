package cn.tianlong.tlobject.aiagent.webui;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpSession;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 数字图片验证码：JDK 自绘（零第三方依赖），状态存 HttpSession。
 *
 * <p>一次性语义：校验成功/失败都会清除当前码，前端必须换图；
 * 注册成功后写 60s 一次性的 {@link #SES_LOGIN_PASS}，供紧随其后的自动登录使用
 * （此时验证码已被注册消费，不再重复要求）。</p>
 *
 * <p>注意：不要在这里设置全局 {@code java.awt.headless=true}——同 JVM 的
 * desktop-java 技能依赖 AWT Robot。BufferedImage 绘制本身不需要显示设备。</p>
 */
public final class TLCaptcha {

    public static final int WIDTH = 120;
    public static final int HEIGHT = 40;
    public static final int CODE_LENGTH = 4;

    /** session 属性：当前验证码 / 过期时间戳 / 注册后一次性登录通行证到期时间戳 */
    public static final String SES_CODE = "tlweb_captcha_code";
    public static final String SES_EXPIRE = "tlweb_captcha_expire";
    public static final String SES_LOGIN_PASS = "tlweb_captcha_login_pass";

    private static final long LOGIN_PASS_MS = 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TLCaptcha() { }

    /** 4 位数字验证码。 */
    public static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    /** 生成 PNG 图片字节（浅底 + 噪线/噪点 + 每字随机颜色/偏移/旋转）。 */
    public static byte[] renderPng(String code) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            // 噪线
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i < 7; i++) {
                g.setColor(new Color(150 + r.nextInt(80), 150 + r.nextInt(80), 150 + r.nextInt(80)));
                g.drawLine(r.nextInt(WIDTH), r.nextInt(HEIGHT), r.nextInt(WIDTH), r.nextInt(HEIGHT));
            }
            // 噪点
            for (int i = 0; i < 40; i++) {
                g.setColor(new Color(120 + r.nextInt(100), 120 + r.nextInt(100), 120 + r.nextInt(100)));
                g.fillOval(r.nextInt(WIDTH), r.nextInt(HEIGHT), 2, 2);
            }
            // 数字
            g.setFont(new Font("SansSerif", Font.BOLD, 26));
            int step = WIDTH / (CODE_LENGTH + 1);
            for (int i = 0; i < code.length(); i++) {
                int x = step / 2 + i * step;
                int y = 29 + r.nextInt(7) - 3;
                g.setColor(new Color(20 + r.nextInt(90), 20 + r.nextInt(90), 20 + r.nextInt(90)));
                Graphics2D digit = (Graphics2D) g.create();
                try {
                    digit.rotate((r.nextDouble() - 0.5) * 0.5, x, y - 8);
                    digit.drawString(String.valueOf(code.charAt(i)), x, y);
                } finally {
                    digit.dispose();
                }
            }
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            if (!ImageIO.write(img, "png", out)) throw new IllegalStateException("PNG 编码不可用");
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
    }

    /** 常量时间比较；input 允许首尾空白。 */
    public static boolean matches(String expected, String input) {
        if (expected == null || expected.isEmpty() || input == null) return false;
        String v = input.trim();
        if (v.isEmpty()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                v.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发新码（会覆盖旧码并清掉一次性登录通行证）。 */
    public static void issue(HttpSession session, String code, long expireAt) {
        if (session == null) return;
        session.setAttribute(SES_CODE, code);
        session.setAttribute(SES_EXPIRE, expireAt);
        session.removeAttribute(SES_LOGIN_PASS);
    }

    /** 校验结果：null 表示通过；否则带回 errorCode 与文案。 */
    public static final class Check {
        public final String code;
        public final String message;
        Check(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /**
     * 校验验证码；失败（缺码/过期/未填/错误）一律清码，成功且 consume 时也清码。
     * 返回 null 表示通过。
     */
    public static Check verify(HttpSession session, String input, boolean consume, long now) {
        if (session == null) return new Check("captcha_expired", "验证码已过期，请刷新");
        Object codeAttr = session.getAttribute(SES_CODE);
        Object expireAttr = session.getAttribute(SES_EXPIRE);
        long expireAt = expireAttr instanceof Number ? ((Number) expireAttr).longValue() : 0L;
        String expected = codeAttr == null ? "" : String.valueOf(codeAttr);
        if (expected.isEmpty() || expireAt <= now) {
            clear(session);
            return new Check("captcha_expired", "验证码已过期，请刷新");
        }
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            clear(session);
            return new Check("captcha_required", "请输入验证码");
        }
        if (!matches(expected, value)) {
            clear(session);
            return new Check("captcha_invalid", "验证码错误，请刷新后重试");
        }
        if (consume) clear(session);
        return null;
    }

    /** 注册成功后写 60s 一次性自动登录通行证（注册时验证码已消费）。 */
    public static void markLoginPass(HttpSession session, long now) {
        if (session != null) session.setAttribute(SES_LOGIN_PASS, now + LOGIN_PASS_MS);
    }

    /** 取出一次性通行证：有效则消费并返回 true（无效/过期也删除）。 */
    public static boolean takeLoginPass(HttpSession session, long now) {
        if (session == null) return false;
        Object value = session.getAttribute(SES_LOGIN_PASS);
        session.removeAttribute(SES_LOGIN_PASS);
        long until = value instanceof Number ? ((Number) value).longValue() : 0L;
        return until > now;
    }

    private static void clear(HttpSession session) {
        session.removeAttribute(SES_CODE);
        session.removeAttribute(SES_EXPIRE);
    }
}
