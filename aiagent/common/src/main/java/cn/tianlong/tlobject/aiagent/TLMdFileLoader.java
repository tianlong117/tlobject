package cn.tianlong.tlobject.aiagent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;

/**
 * MD 文件加载与 frontmatter 解析工具类。
 * Skill（SKILL.md）与 Agent（md/{agentName}.md）共用：
 * 读取文件（文件系统优先、classpath 回退）+ 解析 YAML frontmatter 的 description 与正文。
 *
 * 创建日期：2026/7/16
 * 作者:tianlong
 */
public class TLMdFileLoader {

    private TLMdFileLoader() {
    }

    /** 尝试文件系统，回退到 classpath。refClass 用于取 classpath 资源的 ClassLoader */
    public static String readFileOrResource(String path, Class<?> refClass) {
        try {
            // Windows 下 getResource().getPath() 形如 /D:/...，去掉开头的 /，否则 Paths.get 抛 InvalidPathException
            String fsPath = path;
            if (fsPath.length() > 2 && fsPath.charAt(0) == '/' && fsPath.charAt(2) == ':')
                fsPath = fsPath.substring(1);
            return new String(Files.readAllBytes(Paths.get(fsPath)), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException e) {
            return readClasspathResource(path, refClass);
        }
    }

    /** 从 classpath 读取资源文件，找不到返回 null */
    public static String readClasspathResource(String resourcePath, Class<?> refClass) {
        if (resourcePath.startsWith("./") || resourcePath.startsWith(".\\"))
            resourcePath = resourcePath.substring(2);
        InputStream is = refClass.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 提取 YAML frontmatter 中的 description。
     *
     * <p>支持单行、引号包裹、以及 YAML 常见的多行折叠/字面量写法：</p>
     * <pre>
     * description: &gt;-
     *   第一行
     *   第二行
     * </pre>
     *
     * <p>旧实现只取 "description:" 同一行的值，遇到 {@code >-} 后正文在后续缩进行时
     * 会返回空字符串——mermaid-flowchart 安装报"描述文件缺少 frontmatter description"
     * 即此因。现在按 YAML 规则收集后续缩进行：{@code >} 折叠成空格、{@code |} 保留换行。</p>
     *
     * <p>无 frontmatter 或无 description 时返回 null。</p>
     */
    public static String parseFrontmatterDescription(String content) {
        if (content == null || !content.startsWith("---")) return null;
        int endIdx = content.indexOf("\n---", 3);
        if (endIdx <= 0) return null;

        String[] lines = content.substring(4, endIdx).split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (!line.startsWith("description:")) continue;

            String value = line.substring("description:".length()).trim();
            boolean folded = false, literal = false;
            if (value.startsWith(">-") || value.startsWith(">")) {
                folded = true;
                value = value.substring(value.startsWith(">-") ? 2 : 1).trim();
            } else if (value.startsWith("|-") || value.startsWith("|")) {
                literal = true;
                value = value.substring(value.startsWith("|-") ? 2 : 1).trim();
            }

            // 多行块：收集后续缩进行（空行按段落换行），遇到下一个非缩进 key 停止
            if (folded || literal || value.isEmpty()) {
                StringBuilder sb = new StringBuilder(value);
                for (int j = i + 1; j < lines.length; j++) {
                    String contRaw = lines[j];
                    String cont = contRaw.trim();
                    if (cont.isEmpty()) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                        continue;
                    }
                    if (!contRaw.startsWith(" ") && !contRaw.startsWith("\t")) break;
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n')
                        sb.append(literal ? '\n' : ' ');
                    sb.append(cont);
                }
                value = sb.toString().trim();
            }
            if (value.isEmpty()) return null;

            // YAML 引号
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))
                value = value.substring(1, value.length() - 1);
            return value;
        }
        return null;
    }

    /** 返回 frontmatter 之后的正文；无 frontmatter 时返回全文 */
    public static String parseBody(String content) {
        if (content == null) return null;
        if (content.startsWith("---")) {
            int endIdx = content.indexOf("\n---", 3);
            if (endIdx > 0) {
                return content.substring(endIdx + 4).trim();
            }
        }
        return content;
    }
}
