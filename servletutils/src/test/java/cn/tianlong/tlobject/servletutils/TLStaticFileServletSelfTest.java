package cn.tianlong.tlobject.servletutils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 通用静态文件 servlet 自测（runnable main，无 JUnit / 无容器）。
 * 覆盖：root 解析、请求路径安全、文件定位（含 symlink 逃逸）、
 * 文件系统优先与 classpath 回退、MIME/charset、context root 判定。
 * 失败退出码 1。
 */
public class TLStaticFileServletSelfTest {
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        testResolveRoot();
        testResourcePath();
        testResolveFile();
        testFallback();
        testMime();
        testContextRoot();
        System.out.println("[TLStaticFileServletSelfTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void testResolveRoot() {
        boolean win = File.separatorChar == '\\';
        if (win) {
            Path p = TLStaticFileServlet.resolveRoot("/D:/app/conf/", "webui");
            check("1 Windows /D: 前导斜杠 + 相对 root", p != null
                    && p.toString().replace('\\', '/').endsWith("D:/app/conf/webui"));
            Path p2 = TLStaticFileServlet.resolveRoot("D:/app/conf", "webui");
            check("2 无尾分隔符结果一致", p != null && p2 != null
                    && p.toString().equals(p2.toString()));
            Path abs = TLStaticFileServlet.resolveRoot("/D:/app/conf/", "C:/tmp/ui");
            check("3 绝对 root 原样", abs != null
                    && abs.toString().replace('\\', '/').equals("C:/tmp/ui"));
        } else {
            Path p = TLStaticFileServlet.resolveRoot("/home/app/conf/", "webui");
            check("1 Unix 相对 root", p != null && p.toString().equals("/home/app/conf/webui"));
            Path abs = TLStaticFileServlet.resolveRoot("/home/app/conf/", "/srv/ui");
            check("3 Unix 绝对 root 原样", abs != null && abs.toString().equals("/srv/ui"));
        }
        check("4 无 configDir 时相对 root 不可解析",
                TLStaticFileServlet.resolveRoot(null, "webui") == null);
        check("5 空白 root 返回 null",
                TLStaticFileServlet.resolveRoot("D:/x", "   ") == null);
        check("6 CLASSPATH/ 前缀不走文件系统",
                TLStaticFileServlet.resolveRoot("D:/x", "CLASSPATH/conf/webui") == null);
        check("7 jar 内目录不走文件系统",
                TLStaticFileServlet.resolveRoot("D:/x", "jar:file:/a.jar!/conf/webui") == null);
    }

    static void testResourcePath() {
        check("8 普通文件", "/chat.html".equals(TLStaticFileServlet.toResourcePath("/chat.html")));
        check("9 嵌套文件", "/assets/x.css".equals(TLStaticFileServlet.toResourcePath("/assets/x.css")));
        check("10 文件名含空格", "/app v2.css".equals(TLStaticFileServlet.toResourcePath("/app v2.css")));
        check("11 文件名含加号", "/a+b.css".equals(TLStaticFileServlet.toResourcePath("/a+b.css")));

        check("12 拒绝 ..", TLStaticFileServlet.toResourcePath("/../secret.txt") == null);
        check("13 拒绝中间 ..", TLStaticFileServlet.toResourcePath("/a/../b") == null);
        check("14 拒绝隐藏文件", TLStaticFileServlet.toResourcePath("/.env") == null);
        check("15 拒绝双斜杠/绝对注入", TLStaticFileServlet.toResourcePath("//D:/secret.txt") == null);
        check("16 拒绝盘符/ADS", TLStaticFileServlet.toResourcePath("/D:/secret.txt") == null);
        check("17 拒绝结尾斜杠", TLStaticFileServlet.toResourcePath("/sub/") == null);
        check("18 拒绝反斜杠", TLStaticFileServlet.toResourcePath("/a\\b") == null);
        check("19 拒绝 NUL", TLStaticFileServlet.toResourcePath("/a\0b") == null);
        check("20 拒绝挂载根空路径", TLStaticFileServlet.toResourcePath("/") == null);
        check("21 拒绝非 / 开头", TLStaticFileServlet.toResourcePath("chat.html") == null);
        check("22 拒绝 null", TLStaticFileServlet.toResourcePath(null) == null);
        check("23 拒绝 Windows 尾点", TLStaticFileServlet.toResourcePath("/trail.") == null);
        check("24 拒绝 Windows 尾空格", TLStaticFileServlet.toResourcePath("/trail ") == null);
    }

    static void testResolveFile() throws Exception {
        Path tmp = Files.createTempDirectory("tlstatic");
        try {
            Path root = tmp.resolve("webui");
            Files.createDirectories(root.resolve("assets"));
            Files.write(root.resolve("chat.html"), "chat".getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("assets/x.css"), "css".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(root.resolve("dir"));

            check("25 命中普通文件", TLStaticFileServlet.resolveFile(root, "/chat.html") != null);
            check("26 命中嵌套文件", TLStaticFileServlet.resolveFile(root, "/assets/x.css") != null);
            check("27 缺失文件返回 null", TLStaticFileServlet.resolveFile(root, "/missing.txt") == null);
            check("28 目录不服务", TLStaticFileServlet.resolveFile(root, "/dir") == null);
            check("29 直接调用也拒绝越界", TLStaticFileServlet.resolveFile(root, "/../secret.txt") == null);

            Path outside = tmp.resolve("secret.txt");
            Files.write(outside, "secret".getBytes(StandardCharsets.UTF_8));
            Path link = root.resolve("escape.txt");
            try {
                Files.createSymbolicLink(link, outside);
                check("30 symlink 逃逸被拒", TLStaticFileServlet.resolveFile(root, "/escape.txt") == null);
            } catch (UnsupportedOperationException | IOException e) {
                System.out.println("[SKIP] 30 symlink 逃逸：当前环境不允许创建 symlink（" + e.getMessage() + "）");
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    static void testFallback() throws Exception {
        TLStaticFileServlet.OpenResource cp = TLStaticFileServlet.open(
                null, TLStaticFileServlet.class, "/webui", "/__selftest_probe.txt");
        check("31 classpath 回退可读", cp != null && !cp.fromFile);
        if (cp != null) {
            try (InputStream in = cp.in) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                check("32 classpath 回退内容正确", "classpath-probe".equals(body));
            }
        } else {
            check("32 classpath 回退内容正确", false);
        }

        Path tmp = Files.createTempDirectory("tlstatic");
        try {
            Path root = tmp.resolve("webui");
            Files.createDirectories(root);
            Files.write(root.resolve("__selftest_probe.txt"), "fs-probe".getBytes(StandardCharsets.UTF_8));
            TLStaticFileServlet.OpenResource fs = TLStaticFileServlet.open(
                    root, TLStaticFileServlet.class, "/webui", "/__selftest_probe.txt");
            check("33 文件系统优先", fs != null && fs.fromFile);
            if (fs != null) {
                try (InputStream in = fs.in) {
                    String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    check("34 文件系统内容优先", "fs-probe".equals(body));
                }
            } else {
                check("34 文件系统内容优先", false);
            }
            check("35 两边都没有返回 null", TLStaticFileServlet.open(
                    root, TLStaticFileServlet.class, "/webui", "/nope.txt") == null);
        } finally {
            deleteRecursively(tmp);
        }
    }

    static void testMime() {
        check("36 html", "text/html".equals(TLStaticFileServlet.contentType("/chat.html")));
        check("37 js", "application/javascript".equals(TLStaticFileServlet.contentType("/app.js")));
        check("38 css", "text/css".equals(TLStaticFileServlet.contentType("/style.css")));
        check("39 json", "application/json".equals(TLStaticFileServlet.contentType("/map.json")));
        check("40 svg", "image/svg+xml".equals(TLStaticFileServlet.contentType("/logo.svg")));
        check("41 png", "image/png".equals(TLStaticFileServlet.contentType("/a.png")));
        check("42 jpg", "image/jpeg".equals(TLStaticFileServlet.contentType("/a.jpg")));
        check("43 woff2", "font/woff2".equals(TLStaticFileServlet.contentType("/f.woff2")));
        check("44 大小写不敏感", "image/png".equals(TLStaticFileServlet.contentType("/A.PNG")));
        check("45 未知扩展名", "application/octet-stream".equals(TLStaticFileServlet.contentType("/a.unknown")));
        check("46 文本类型拼 charset", TLStaticFileServlet.isUtf8TextType("text/css")
                && TLStaticFileServlet.isUtf8TextType("application/json")
                && TLStaticFileServlet.isUtf8TextType("image/svg+xml"));
        check("47 二进制不拼 charset", !TLStaticFileServlet.isUtf8TextType("image/png")
                && !TLStaticFileServlet.isUtf8TextType("font/woff2"));
    }

    static void testContextRoot() {
        check("48 根 context 的 /", TLStaticFileServlet.isContextRoot("/", ""));
        check("49 根 context 的空 uri", TLStaticFileServlet.isContextRoot("", ""));
        check("50 根 context 的其他路径", !TLStaticFileServlet.isContextRoot("/foo", ""));
        check("51 子 context 精确", TLStaticFileServlet.isContextRoot("/app", "/app"));
        check("52 子 context 带斜杠", TLStaticFileServlet.isContextRoot("/app/", "/app"));
        check("53 子 context 其他路径", !TLStaticFileServlet.isContextRoot("/app/x", "/app"));
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    static void deleteRecursively(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
