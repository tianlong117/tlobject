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
     * 提取 YAML frontmatter 中的 description（处理 >- / > 折叠语法与引号包裹）。
     * 无 frontmatter 或无 description 时返回 null。
     */
    public static String parseFrontmatterDescription(String content) {
        if (content == null || !content.startsWith("---")) return null;
        int endIdx = content.indexOf("\n---", 3);
        if (endIdx <= 0) return null;

        String fmDescription = null;
        String frontmatter = content.substring(4, endIdx).trim();
        for (String line : frontmatter.split("\n")) {
            line = line.trim();
            if (line.startsWith("description:")) {
                fmDescription = line.substring("description:".length()).trim();
                // YAML 多行折叠语法 >- / >
                if (fmDescription.startsWith(">-")) fmDescription = fmDescription.substring(2).trim();
                else if (fmDescription.startsWith(">")) fmDescription = fmDescription.substring(1).trim();
                // YAML 引号
                if ((fmDescription.startsWith("\"") && fmDescription.endsWith("\""))
                        || (fmDescription.startsWith("'") && fmDescription.endsWith("'")))
                    fmDescription = fmDescription.substring(1, fmDescription.length() - 1);
            }
        }
        return fmDescription;
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
