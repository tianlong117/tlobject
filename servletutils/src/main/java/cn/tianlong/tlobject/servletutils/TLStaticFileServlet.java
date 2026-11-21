package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用静态文件 servlet（纯 HttpServlet，无 webui 语义）。
 *
 * <p>通过 TLJettyServer 的 extraServlets 挂载，类名后用 ?k=v&amp;k2=v2 传 init 参数：</p>
 * <ul>
 *   <li>{@code root} — 文件系统静态目录。相对路径基于 moduleFactory.getConfigDir()，
 *       绝对路径原样（支持 file: URI）；不配 = 无文件系统源。</li>
 *   <li>{@code fallbackClasspath} — 可选 classpath 前缀（如 {@code /webui}）。
 *       文件系统逐文件未命中时按 {@code 前缀 + resource} 从 classpath 读取（兼容 UI 打进 jar）。</li>
 *   <li>{@code indexRedirect} — 可选，挂载根的 302 目标（如 {@code /webui/chat.html}）。</li>
 *   <li>{@code welcomeFile} — 可选，无 indexRedirect 时挂载根直接返回的文件名（如 index.html）。</li>
 *   <li>{@code cacheControl} — 可选，Cache-Control 响应头，默认 {@code no-cache}（开发期改文件刷新即生效）。</li>
 * </ul>
 *
 * <p>无 root 且无 fallbackClasspath 时是"重定向壳"：仅 context root 302 到 indexRedirect，
 * 其余 404 —— 用于把 "/" 映射指向首页。有静态源时（/xxx/* 前缀映射），pathInfo 为
 * null 或 "/" 走 indexRedirect/welcomeFile，其余作为资源路径。</p>
 *
 * <p>路径安全：拒绝反斜杠、NUL、空段、"."/".."、盘符/ADS 冒号、隐藏文件、Windows 尾点/尾空格段；
 * 文件定位做 normalize + toRealPath 双重越界校验（防 symlink/junction 逃逸），只服务普通文件。</p>
 *
 * @author tianlong
 */
public class TLStaticFileServlet extends HttpServlet {

    private static final String P_ROOT = "root";
    private static final String P_FALLBACK_CLASSPATH = "fallbackClasspath";
    private static final String P_INDEX_REDIRECT = "indexRedirect";
    private static final String P_WELCOME_FILE = "welcomeFile";
    private static final String P_CACHE_CONTROL = "cacheControl";
    private static final String LOG_TAG = "staticFileServlet";

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html"), Map.entry("htm", "text/html"),
            Map.entry("js", "application/javascript"), Map.entry("mjs", "application/javascript"),
            Map.entry("css", "text/css"), Map.entry("json", "application/json"),
            Map.entry("map", "application/json"), Map.entry("txt", "text/plain"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"), Map.entry("avif", "image/avif"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"), Map.entry("otf", "font/otf"),
            Map.entry("eot", "application/vnd.ms-fontobject"),
            Map.entry("wasm", "application/wasm"));

    private final TLObjectFactory moduleFactory;
    private final AtomicBoolean missingWarned = new AtomicBoolean();

    /** 解析后的文件系统静态目录；null = 无文件系统源 */
    private Path staticRoot;
    /** classpath 回退前缀（规范化后以 / 开头、无结尾 /）；null = 不回退 */
    private String fallbackPrefix;
    private String indexRedirect;
    private String welcomeFile;
    private String cacheControl = "no-cache";

    /** TLJettyServer extraServlets 优先使用本构造器（同 TLServletDispatch 模式） */
    public TLStaticFileServlet(String name, TLObjectFactory modulefactory) {
        this.moduleFactory = modulefactory;
    }

    /** 无 factory 时相对 root 无法解析，只能走 classpath 回退（供容器默认构造场景） */
    public TLStaticFileServlet() {
        this.moduleFactory = null;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        String rootParam = trimToNull(config.getInitParameter(P_ROOT));
        fallbackPrefix = normalizeClasspathPrefix(trimToNull(config.getInitParameter(P_FALLBACK_CLASSPATH)));
        indexRedirect = trimToNull(config.getInitParameter(P_INDEX_REDIRECT));
        welcomeFile = trimToNull(config.getInitParameter(P_WELCOME_FILE));
        String cc = trimToNull(config.getInitParameter(P_CACHE_CONTROL));
        if (cc != null) cacheControl = cc;

        if (rootParam != null) {
            staticRoot = resolveRoot(moduleFactory == null ? null : moduleFactory.getConfigDir(), rootParam);
            if (staticRoot == null) {
                log(LogLevel.WARN, "root 无法解析: " + rootParam
                        + (moduleFactory == null ? "（无 factory，相对路径不可用）" : ""));
            }
        }
        log(LogLevel.INFO, "静态资源 servlet: root=" + (staticRoot == null ? "(none)" : staticRoot)
                + ", fallbackClasspath=" + (fallbackPrefix == null ? "(none)" : fallbackPrefix)
                + ", indexRedirect=" + (indexRedirect == null ? "(none)" : indexRedirect));
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String uri = req.getRequestURI();
            String ctx = req.getContextPath();
            boolean hasSource = staticRoot != null || fallbackPrefix != null;

            if (!hasSource) {
                // 重定向壳：只认 context root，其余 404
                if (indexRedirect != null && isContextRoot(uri, ctx)) {
                    resp.sendRedirect(indexRedirect);
                } else {
                    writeNotFound(resp, uri, null);
                }
                return;
            }

            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
                if (indexRedirect != null) {
                    resp.sendRedirect(indexRedirect);
                } else if (welcomeFile != null) {
                    serve("/" + welcomeFile, resp);
                } else {
                    writeNotFound(resp, uri, null);
                }
                return;
            }

            String resource = toResourcePath(pathInfo);
            if (resource == null) {
                log(LogLevel.WARN, "拒绝不安全的静态资源路径: " + uri);
                writeError(resp, 403, "禁止访问的路径");
                return;
            }
            serve(resource, resp);
        } catch (Exception e) {
            log(LogLevel.WARN, "静态资源处理异常: " + e);
            try {
                writeError(resp, 500, "服务器内部错误");
            } catch (Exception ignored) {
            }
        }
    }

    // ======================== 静态文件 ========================

    private void serve(String resource, HttpServletResponse resp) throws IOException {
        OpenResource r = open(staticRoot, getClass(), fallbackPrefix, resource);
        if (r == null) {
            writeNotFound(resp, resource, staticRoot);
            logMissing(resource);
            return;
        }
        try (InputStream in = r.in) {
            resp.setStatus(200);
            resp.setHeader("Cache-Control", cacheControl);
            String mime = contentType(resource);
            if (isUtf8TextType(mime)) {
                resp.setContentType(mime + ";charset=UTF-8");
                resp.setCharacterEncoding("UTF-8");
            } else {
                resp.setContentType(mime);
            }
            if (r.length >= 0) resp.setContentLengthLong(r.length);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) resp.getOutputStream().write(buf, 0, n);
        }
    }

    /** 文件系统优先；同一资源缺失才试 classpath 前缀；都没有返回 null。 */
    static OpenResource open(Path root, Class<?> ref, String fallbackPrefix, String resource) {
        Path file = resolveFile(root, resource);
        if (file != null) {
            try {
                return new OpenResource(
                        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS),
                        Files.size(file), true, file.toString());
            } catch (IOException ignored) {
                // 竞态删除：继续 classpath 回退
            }
        }
        if (fallbackPrefix != null) {
            String cp = fallbackPrefix + resource;
            InputStream in = ref.getResourceAsStream(cp);
            if (in != null) return new OpenResource(in, -1L, false, "classpath:" + cp);
        }
        return null;
    }

    /** 静态根内定位可服务的普通文件；目录/缺失/越界/软链逃逸返回 null。 */
    static Path resolveFile(Path root, String resource) {
        if (root == null || resource == null || !resource.startsWith("/")) return null;
        try {
            Path base = root.toAbsolutePath().normalize();
            Path candidate = base.resolve(resource.substring(1)).normalize();
            if (!candidate.startsWith(base)) return null;
            if (!Files.isRegularFile(candidate)) return null;          // 目录不列、不索引
            Path realRoot = base.toRealPath();
            Path real = candidate.toRealPath();                        // 解开 symlink/junction
            if (!real.startsWith(realRoot)) return null;
            return Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) ? real : null;
        } catch (IOException | InvalidPathException | SecurityException e) {
            return null;
        }
    }

    // ======================== 路径解析 ========================

    /**
     * 解析 root 参数为绝对 Path。相对路径基于 configDir；绝对路径原样；
     * CLASSPATH/、jar: 等非文件系统来源返回 null（交给 fallbackClasspath）。
     */
    static Path resolveRoot(String configDir, String rootParam) {
        if (rootParam == null) return null;
        String raw = rootParam.trim();
        if (raw.isEmpty()) return null;
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file:")) {
            try {
                return Paths.get(java.net.URI.create(raw)).toAbsolutePath().normalize();
            } catch (Exception e) {
                return null;
            }
        }
        if (lower.startsWith("classpath/") || lower.startsWith("classpath\\")
                || lower.startsWith("jar:") || raw.contains("!/")) {
            return null;
        }
        try {
            String r = normalizeFsPath(raw);
            Path candidate;
            if (Paths.get(r).isAbsolute()) {
                candidate = Paths.get(r);
            } else {
                if (configDir == null) return null;
                String base = normalizeFsPath(configDir);
                if (base == null || base.isEmpty()) return null;
                if (!base.endsWith("/")) base = base + "/";
                candidate = Paths.get(base + r);
            }
            return candidate.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Windows 的 /D:/... 去掉前导斜杠；反斜杠统一成正斜杠，供 Paths.get 使用。 */
    static String normalizeFsPath(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (File.separatorChar == '\\') {
            if (p.length() > 2 && p.charAt(0) == '/' && p.charAt(2) == ':') p = p.substring(1);
            p = p.replace('\\', '/');
        }
        return p;
    }

    /**
     * 请求资源路径（pathInfo，容器已解码）→ 安全相对路径；不安全返回 null。
     * 拒绝反斜杠、NUL、空段（// 与结尾 /）、"."/".."、盘符/ADS 冒号、隐藏文件、
     * 以及 Windows 会裁掉的尾点/尾空格段。
     */
    static String toResourcePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') return null;
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return null;
        String[] segments = path.substring(1).split("/", -1);
        for (String seg : segments) {
            if (seg.isEmpty()) return null;
            if (seg.indexOf(':') >= 0) return null;
            if (seg.startsWith(".")) return null;
            if (seg.endsWith(".") || seg.endsWith(" ")) return null;
        }
        return path;
    }

    /** classpath 前缀规范化：保证以 / 开头、去掉结尾 /；空返回 null。 */
    static String normalizeClasspathPrefix(String prefix) {
        if (prefix == null) return null;
        String p = prefix.trim();
        if (p.isEmpty()) return null;
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /** uri 是否为 context root（ctx 为空时即 "/"）。 */
    static boolean isContextRoot(String uri, String ctx) {
        if (uri == null) return false;
        String base = ctx == null ? "" : ctx;
        return uri.equals(base) || uri.equals(base + "/");
    }

    // ======================== MIME / 响应 ========================

    static String contentType(String resource) {
        int dot = resource.lastIndexOf('.');
        if (dot < 0 || dot == resource.length() - 1) return "application/octet-stream";
        return MIME.getOrDefault(resource.substring(dot + 1).toLowerCase(Locale.ROOT),
                "application/octet-stream");
    }

    static boolean isUtf8TextType(String mime) {
        return mime.startsWith("text/")
                || "application/javascript".equals(mime)
                || "application/json".equals(mime)
                || "image/svg+xml".equals(mime);
    }

    private void writeNotFound(HttpServletResponse resp, String resourceOrUri, Path root) throws IOException {
        String fsPath = null;
        if (root != null && resourceOrUri != null && resourceOrUri.startsWith("/")
                && !resourceOrUri.equals("/")) {
            try {
                fsPath = root.resolve(resourceOrUri.substring(1)).normalize().toString();
            } catch (InvalidPathException ignored) {
            }
        }
        StringBuilder sb = new StringBuilder("{\"success\":false,\"error\":\"资源不存在: ")
                .append(escapeJson(resourceOrUri)).append('"');
        sb.append(",\"staticRoot\":");
        if (staticRoot == null) sb.append("null");
        else sb.append('"').append(escapeJson(staticRoot.toString())).append('"');
        sb.append(",\"looked\":[");
        boolean first = true;
        if (fsPath != null) {
            sb.append('"').append(escapeJson(fsPath)).append('"');
            first = false;
        }
        if (fallbackPrefix != null && resourceOrUri != null && resourceOrUri.startsWith("/")) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson("classpath:" + fallbackPrefix + resourceOrUri)).append('"');
        }
        sb.append("]}");
        writeJson(resp, 404, sb.toString());
    }

    private static void writeError(HttpServletResponse resp, int status, String error) throws IOException {
        writeJson(resp, status, "{\"success\":false,\"error\":\"" + escapeJson(error) + "\"}");
    }

    private static void writeJson(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(body);
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    // ======================== 工具 ========================

    private void logMissing(String resource) {
        String msg = "静态资源未找到: " + resource + "（root=" + staticRoot
                + ", fallback=" + fallbackPrefix + "）";
        if (missingWarned.compareAndSet(false, true)) log(LogLevel.WARN, msg);
        else log(LogLevel.DEBUG, msg);
    }

    private void log(LogLevel level, String msg) {
        if (moduleFactory != null) moduleFactory.putLog(msg, level, LOG_TAG);
        else System.err.println("[TLStaticFileServlet] " + level + " " + msg);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static final class OpenResource implements AutoCloseable {
        final InputStream in;
        final long length;
        final boolean fromFile;
        final String source;

        OpenResource(InputStream in, long length, boolean fromFile, String source) {
            this.in = in;
            this.length = length;
            this.fromFile = fromFile;
            this.source = source;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
