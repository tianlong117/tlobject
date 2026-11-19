package cn.tianlong.tlobject.aiagent.webui;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpSession;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 验证码自测（runnable main，无 JUnit / 无 web 容器）。
 * 覆盖：码生成、PNG 渲染、匹配、一次性 session 校验、一次性登录通行证。
 * 失败退出码 1。
 */
public class TLCaptchaSelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        testCodeAndImage();
        testMatches();
        testSessionVerify();
        testLoginPass();
        System.out.println("[TLCaptchaSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void testCodeAndImage() throws Exception {
        Set<String> codes = new HashSet<>();
        boolean allDigits = true;
        String sample = null;
        for (int i = 0; i < 20; i++) {
            String code = TLCaptcha.randomCode();
            if (sample == null) sample = code;
            codes.add(code);
            allDigits = allDigits && code.matches("\\d{4}");
        }
        check("1 码为 4 位数字", allDigits);
        check("2 码有随机性", codes.size() >= 2);

        byte[] png = TLCaptcha.renderPng(sample);
        check("3 PNG 魔数", png.length > 8 && (png[0] & 0xFF) == 0x89
                && png[1] == 'P' && png[2] == 'N' && png[3] == 'G');
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        check("4 图片可解码且尺寸正确", img != null
                && img.getWidth() == TLCaptcha.WIDTH && img.getHeight() == TLCaptcha.HEIGHT);
    }

    static void testMatches() {
        check("5 匹配允许首尾空白", TLCaptcha.matches("1234", " 1234 "));
        check("6 拒绝 null/空/错误",
                !TLCaptcha.matches("1234", null)
                        && !TLCaptcha.matches("1234", "  ")
                        && !TLCaptcha.matches("1234", "4321"));
    }

    static void testSessionVerify() {
        long now = 1_000_000L;
        Map<String, Object> attrs = new HashMap<>();
        HttpSession s = fakeSession(attrs);

        TLCaptcha.issue(s, "1234", now + 60_000);
        check("7 正确码通过且消费",
                TLCaptcha.verify(s, "1234", true, now) == null
                        && !attrs.containsKey(TLCaptcha.SES_CODE)
                        && !attrs.containsKey(TLCaptcha.SES_EXPIRE));

        TLCaptcha.issue(s, "1234", now + 60_000);
        TLCaptcha.Check wrong = TLCaptcha.verify(s, "0000", true, now);
        check("8 错误码拒绝且作废",
                wrong != null && "captcha_invalid".equals(wrong.code)
                        && !attrs.containsKey(TLCaptcha.SES_CODE));

        TLCaptcha.issue(s, "1234", now - 1);
        TLCaptcha.Check expired = TLCaptcha.verify(s, "1234", true, now);
        check("9 过期码拒绝且作废",
                expired != null && "captcha_expired".equals(expired.code)
                        && !attrs.containsKey(TLCaptcha.SES_CODE));

        TLCaptcha.issue(s, "1234", now + 60_000);
        TLCaptcha.Check required = TLCaptcha.verify(s, "", true, now);
        check("10 空输入提示必填且作废",
                required != null && "captcha_required".equals(required.code)
                        && !attrs.containsKey(TLCaptcha.SES_CODE));

        TLCaptcha.Check missing = TLCaptcha.verify(s, "1234", true, now);
        check("11 未签发按过期处理", missing != null && "captcha_expired".equals(missing.code));

        TLCaptcha.issue(s, "1234", now + 60_000);
        check("12 consume=false 通过但保留码",
                TLCaptcha.verify(s, "1234", false, now) == null
                        && "1234".equals(attrs.get(TLCaptcha.SES_CODE)));
    }

    static void testLoginPass() {
        long now = 1_000_000L;
        Map<String, Object> attrs = new HashMap<>();
        HttpSession s = fakeSession(attrs);
        TLCaptcha.markLoginPass(s, now);
        check("13 一次性通行证有效且只消费一次",
                TLCaptcha.takeLoginPass(s, now) && !TLCaptcha.takeLoginPass(s, now));
        TLCaptcha.markLoginPass(s, now);
        check("14 一次性通行证过期失效", !TLCaptcha.takeLoginPass(s, now + 61_000));
        check("15 null session 安全",
                !TLCaptcha.takeLoginPass(null, now) && TLCaptcha.verify(null, "1", true, now) != null);
    }

    /** 最小假 HttpSession：只实现属性存取，其余方法返回类型默认值。 */
    static HttpSession fakeSession(Map<String, Object> attrs) {
        return (HttpSession) Proxy.newProxyInstance(
                TLCaptchaSelfTest.class.getClassLoader(),
                new Class<?>[]{HttpSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAttribute":
                            return attrs.get((String) args[0]);
                        case "setAttribute":
                            attrs.put((String) args[0], args[1]);
                            return null;
                        case "removeAttribute":
                            attrs.remove((String) args[0]);
                            return null;
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == long.class) return 0L;
                            return null;
                    }
                });
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) pass++; else fail++;
    }
}
