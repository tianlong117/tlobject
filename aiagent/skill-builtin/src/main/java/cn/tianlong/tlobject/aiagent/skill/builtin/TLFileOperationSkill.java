package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件操作Skill。允许LLM读写本地文件系统上的文件。
 * 函数名: file_operation
 *
 * 创建日期：2026/7/4
 * 作者:tianlong
 */
public class TLFileOperationSkill extends TLBaseSkill {

    /** 允许操作的根目录（安全限制） */
    private String allowedRootPath = ".";
    /** 最大文件读取大小 */
    private int maxReadSize = 1024 * 1024; // 1MB

    public TLFileOperationSkill() {
        super();
    }

    public TLFileOperationSkill(String name) {
        super(name);
    }

    public TLFileOperationSkill(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (skillName == null || skillName.isEmpty())
            skillName = "file_operation";
        if (skillDescription == null || skillDescription.isEmpty())
            skillDescription = "Read and write files on the local filesystem. Supports reading text files and writing content to files. Operations: read, write, list, exists, delete.";

        if (params != null) {
            if (params.get("allowedRootPath") != null)
                allowedRootPath = params.get("allowedRootPath");
            if (params.get("maxReadSize") != null) {
                try { maxReadSize = Integer.parseInt(params.get("maxReadSize")); }
                catch (NumberFormatException ignored) {}
            }
        }

        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> opProp = new LinkedHashMap<>();
            opProp.put("type", "string");
            opProp.put("description", "Operation to perform: read, write, list, exists, delete");
            opProp.put("enum", new String[]{"read", "write", "list", "exists", "delete"});
            opProp.put("required", true);
            parameterSchema.put("operation", opProp);

            Map<String, Object> pathProp = new LinkedHashMap<>();
            pathProp.put("type", "string");
            pathProp.put("description", "File or directory path (relative to allowed root)");
            pathProp.put("required", true);
            parameterSchema.put("path", pathProp);

            Map<String, Object> contentProp = new LinkedHashMap<>();
            contentProp.put("type", "string");
            contentProp.put("description", "Content to write (required for write operation)");
            parameterSchema.put("content", contentProp);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input = (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        if (input.isEmpty()) {
            input = new LinkedHashMap<>();
            if (msg.containsParam("operation")) input.put("operation", msg.getStringParam("operation", "read"));
            if (msg.containsParam("path")) input.put("path", msg.getStringParam("path", ""));
            if (msg.containsParam("content")) input.put("content", msg.getStringParam("content", ""));
        }

        String operation = (String) input.getOrDefault("operation", "read");
        String path = (String) input.getOrDefault("path", "");
        String content = (String) input.getOrDefault("content", "");

        if (path.isEmpty()) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Error: path is required");
        }

        // 安全检查：确保路径在允许范围内
        try {
            Path allowedRoot = Paths.get(allowedRootPath).toAbsolutePath().normalize();
            Path resolvedPath = allowedRoot.resolve(path).normalize().toAbsolutePath();
            // 如果路径超出根目录，尝试用文件名部分在根目录下重建路径
            if (!resolvedPath.startsWith(allowedRoot)) {
                Path fileNamePath = resolvedPath.getFileName();
                if (fileNamePath == null) {
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "Error: path is outside allowed root: " + resolvedPath);
                }
                resolvedPath = allowedRoot.resolve(fileNamePath.toString());
            }

            switch (operation.toLowerCase()) {
                case "read":
                    return doRead(resolvedPath);
                case "write":
                    return doWrite(resolvedPath, content);
                case "list":
                    return doList(resolvedPath);
                case "exists":
                    return doExists(resolvedPath);
                case "delete":
                    return doDelete(resolvedPath);
                default:
                    return createMsg().setParam(RESULT, false)
                            .setParam(AI_P_SKILLOUTPUT, "Unknown operation: " + operation);
            }
        } catch (Exception e) {
            putLog("File Skill error: " + e.toString(), LogLevel.ERROR);
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "File operation failed: " + e.getMessage());
        }
    }

    private TLMsg doRead(Path path) {
        try {
            if (!Files.exists(path)) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "File not found: " + path);
            }
            if (Files.isDirectory(path)) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Path is a directory, use list operation: " + path);
            }
            long fileSize = Files.size(path);
            if (fileSize > maxReadSize) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "File too large: " + fileSize + " bytes (max: " + maxReadSize + ")");
            }
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            putLog("File read: " + path + " (" + fileSize + " bytes)", LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, text);
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Read error: " + e.getMessage());
        }
    }

    private TLMsg doWrite(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(path, contentBytes);
            putLog("File written: " + path + " (" + contentBytes.length + " bytes)", LogLevel.DEBUG);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("written", true);
            result.put("path", path.toString());
            result.put("bytes", contentBytes.length);
            return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT,
                    "File written successfully: " + path + " (" + contentBytes.length + " bytes)");
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Write error: " + e.getMessage());
        }
    }

    private TLMsg doList(Path path) {
        try {
            if (!Files.exists(path)) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "Directory not found: " + path);
            }
            if (!Files.isDirectory(path)) {
                return doRead(path); // 如果是文件就读取
            }
            StringBuilder listing = new StringBuilder();
            listing.append("Directory listing for: ").append(path).append("\n");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    String type = Files.isDirectory(entry) ? "[DIR] " : "[FILE] ";
                    listing.append(type).append(entry.getFileName()).append("\n");
                }
            }
            return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, listing.toString());
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "List error: " + e.getMessage());
        }
    }

    private TLMsg doExists(Path path) {
        boolean exists = Files.exists(path);
        return createMsg().setParam(RESULT, true)
                .setParam(AI_P_SKILLOUTPUT, exists ? "File/directory exists: " + path : "File/directory does NOT exist: " + path);
    }

    private TLMsg doDelete(Path path) {
        try {
            if (!Files.exists(path)) {
                return createMsg().setParam(RESULT, false)
                        .setParam(AI_P_SKILLOUTPUT, "File not found: " + path);
            }
            Files.delete(path);
            putLog("File deleted: " + path, LogLevel.DEBUG);
            return createMsg().setParam(RESULT, true)
                    .setParam(AI_P_SKILLOUTPUT, "File deleted: " + path);
        } catch (IOException e) {
            return createMsg().setParam(RESULT, false)
                    .setParam(AI_P_SKILLOUTPUT, "Delete error: " + e.getMessage());
        }
    }
}
