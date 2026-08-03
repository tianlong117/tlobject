package cn.tianlong.tlobject.aiagent.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP 运行时检测工具。
 *
 * 在安装 MCP Server 前检测所需命令（npx、uvx、docker 等）是否可用，
 * 不可用时给出平台相关的安装提示。
 *
 * @author tianlong
 * @date 2026/8/3
 */
public class TLMcpRuntime {

    /** 已知运行时及其检测命令 */
    private static final Map<String, RuntimeInfo> KNOWN_RUNTIMES = new LinkedHashMap<>();
    static {
        KNOWN_RUNTIMES.put("npx", new RuntimeInfo(
                "npx", "Node.js / npm",
                "https://nodejs.org/",
                "下载 Node.js 安装包: https://nodejs.org/ (建议 LTS 版本)\n"
              + "  安装后 npx 自动可用。"
        ));
        KNOWN_RUNTIMES.put("npx.cmd", new RuntimeInfo(
                "npx.cmd", "Node.js / npm",
                "https://nodejs.org/",
                "下载 Node.js 安装包: https://nodejs.org/ (建议 LTS 版本)\n"
              + "  安装后 npx 自动可用。"
        ));
        KNOWN_RUNTIMES.put("uvx", new RuntimeInfo(
                "uvx", "Python uv",
                "https://docs.astral.sh/uv/",
                "方式1: pip install uv\n"
              + "  方式2: powershell -c \"irm https://astral.sh/uv/install.ps1 | iex\""
        ));
        KNOWN_RUNTIMES.put("docker", new RuntimeInfo(
                "docker", "Docker",
                "https://www.docker.com/products/docker-desktop/",
                "下载 Docker Desktop: https://www.docker.com/products/docker-desktop/"
        ));
    }

    /**
     * 检测结果。
     */
    public static class CheckResult {
        public final boolean available;
        public final String hint;       // 不可用时的安装提示
        public final String foundPath;  // 可用时的实际路径

        private CheckResult(boolean available, String hint, String foundPath) {
            this.available = available;
            this.hint = hint;
            this.foundPath = foundPath;
        }

        public static CheckResult ok(String path) {
            return new CheckResult(true, null, path);
        }

        public static CheckResult fail(String hint) {
            return new CheckResult(false, hint, null);
        }
    }

    /**
     * 检测指定命令是否可用。
     * 先尝试直接运行 {@code command --version}，
     * 失败则在 Windows 常见路径下查找。
     *
     * @param command 命令名（如 "npx", "uvx", "docker"）
     * @return 检测结果
     */
    public static CheckResult checkCommand(String command) {
        if (command == null || command.isEmpty()) {
            return CheckResult.fail("命令为空");
        }

        // 如果 command 已是完整路径（含 \ 或 /），直接检测
        if (command.contains("/") || command.contains("\\")) {
            return checkByPath(command);
        }

        // 1. 尝试运行 command --version
        boolean works = tryRun(command, "--version", 5);
        if (works) {
            return CheckResult.ok(command);
        }

        // 2. Windows: 尝试 npx.cmd 变体
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            if (!command.endsWith(".cmd") && !command.endsWith(".exe")) {
                String cmdVariant = command + ".cmd";
                works = tryRun(cmdVariant, "--version", 5);
                if (works) {
                    return CheckResult.ok(cmdVariant);
                }
            }
        }

        // 3. 给出安装提示
        String hint = buildHint(command);
        return CheckResult.fail(hint);
    }

    /**
     * 获取已知命令的安装提示，未知命令返回通用提示。
     */
    public static String getInstallHint(String command) {
        return buildHint(command);
    }

    // ======================== Private ========================

    private static CheckResult checkByPath(String fullPath) {
        boolean works = tryRun(fullPath, "--version", 5);
        if (works) return CheckResult.ok(fullPath);
        return CheckResult.fail("命令不可用: " + fullPath + "\n  请检查路径是否正确，程序是否已安装。");
    }

    /**
     * 通过 ProcessBuilder 尝试运行命令，获取退出码。
     */
    private static boolean tryRun(String command, String arg, int timeoutSec) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, arg);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 消费 stdout 避免阻塞
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) { /* drain */ }
            }
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建安装提示。
     */
    private static String buildHint(String command) {
        // 查找已知运行时
        for (Map.Entry<String, RuntimeInfo> e : KNOWN_RUNTIMES.entrySet()) {
            if (command.equals(e.getKey()) || command.startsWith(e.getKey())) {
                RuntimeInfo info = e.getValue();
                return "未检测到 " + info.displayName + "（需要 " + command + " 命令）。\n"
                     + "  官网: " + info.url + "\n"
                     + "  " + info.installGuide.replace("\n", "\n  ");
            }
        }
        // 未知命令
        return "未检测到命令: " + command + "\n"
             + "  请确认该命令已安装且在 PATH 中。";
    }

    /** 已知运行时信息 */
    private static class RuntimeInfo {
        final String command;
        final String displayName;
        final String url;
        final String installGuide;

        RuntimeInfo(String command, String displayName, String url, String installGuide) {
            this.command = command;
            this.displayName = displayName;
            this.url = url;
            this.installGuide = installGuide;
        }
    }
}
