package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.*;

/**
 * 文件版会话管理器——JSONL 文件存储。
 *
 * @author tianlong
 * @date 2026/8/2
 */
public class TLSessionManager extends TLBaseSessionManager {

    /** 存储根路径 */
    protected String sessionStorePath = "./data/";

    public TLSessionManager() { super(); }
    public TLSessionManager(String name) { super(name); }
    public TLSessionManager(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null) {
            if (params.get("enableCheckpoint") != null)
                enableCheckpoint = Boolean.parseBoolean(params.get("enableCheckpoint"));
            if (params.get("sessionStorePath") != null) {
                sessionStorePath = params.get("sessionStorePath");
                if (!sessionStorePath.endsWith("/")) sessionStorePath += "/";
            }
        }
    }

    // ======================== 实现抽象方法 ========================

    @Override
    protected void storeRound(Map<String, Object> roundData) {
        if (!enableCheckpoint) return;
        try {
            String sessionId = (String) roundData.getOrDefault("sessionId", "");
            String userId = (String) roundData.getOrDefault("userId", "");
            String agentName = (String) roundData.getOrDefault("agentName", "");
            java.io.File dir = new java.io.File(getSessionStorePath(userId));
            if (!dir.exists()) dir.mkdirs();

            java.io.File file = new java.io.File(dir, sessionFileName(agentName, sessionId));
            int lineCount = 0;
            if (file.exists()) {
                lineCount = (int) java.nio.file.Files.lines(file.toPath()).count();
            }
            roundData.put("roundSeq", lineCount + 1);
            roundData.put("timestamp", System.currentTimeMillis());

            String json = gson.toJson(roundData);
            java.nio.file.Files.write(file.toPath(), (json + "\n").getBytes("UTF-8"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            putLog("storeRound failed: " + e.toString(), cn.tianlong.tlobject.modules.LogLevel.WARN);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected java.util.List<java.util.Map<String, Object>> loadRoundData(String sessionId, String userId) {
        java.util.List<java.util.Map<String, Object>> rounds = new java.util.ArrayList<>();
        try {
            java.io.File file = findSessionFile(getSessionStorePath(userId), sessionId);
            if (file == null || !file.exists()) return rounds;

            java.util.List<String> lines = java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                com.google.gson.JsonObject obj = gson.fromJson(line, com.google.gson.JsonObject.class);
                java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                for (String key : obj.keySet()) {
                    com.google.gson.JsonElement el = obj.get(key);
                    if (el.isJsonPrimitive()) {
                        com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
                        if (p.isString()) map.put(key, p.getAsString());
                        else if (p.isNumber()) map.put(key, p.getAsDouble());
                        else if (p.isBoolean()) map.put(key, p.getAsBoolean());
                    } else if (el.isJsonArray() && "messages".equals(key)) {
                        // 反序列化 messages
                        List<TLConversationHistory> msgs = new ArrayList<>();
                        for (com.google.gson.JsonElement me : el.getAsJsonArray()) {
                            msgs.add(gson.fromJson(me, TLConversationHistory.class));
                        }
                        map.put(key, msgs);
                    } else if (el.isJsonObject()) {
                        if ("pendingToolCall".equals(key)) {
                            map.put(key, gson.fromJson(el, TLToolCall.class));
                        } else {
                            map.put(key, gson.fromJson(el, Map.class));
                        }
                    }
                }
                // 确保 roundSeq 是 Integer
                if (map.containsKey("roundSeq") && map.get("roundSeq") instanceof Double) {
                    map.put("roundSeq", ((Double) map.get("roundSeq")).intValue());
                }
                rounds.add(map);
            }
        } catch (Exception e) {
            putLog("loadRoundData failed: " + e.toString(), cn.tianlong.tlobject.modules.LogLevel.WARN);
        }
        return rounds;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected java.util.Map<String, Object> findLatestMeta(String userId) {
        try {
            java.io.File dir = new java.io.File(getSessionStorePath(userId));
            if (!dir.exists() || !dir.isDirectory()) return null;
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null || files.length == 0) return null;

            java.io.File latest = files[0];
            for (java.io.File f : files) {
                if (f.lastModified() > latest.lastModified()) latest = f;
            }

            TLMsg meta = readLastLineMeta(latest);
            if (meta == null) return null;
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("sessionId", meta.getStringParam("sessionId", ""));
            result.put("state", meta.getStringParam("state", ""));
            result.put("agentName", meta.getStringParam("agentName", ""));
            result.put("userMessage", meta.getStringParam("userMessage", ""));
            result.put("roundSeq", meta.getIntParam("roundSeq", 0));
            result.put("savedAt", latest.lastModified());
            result.put("count", meta.getIntParam("count", 0));
            return result;
        } catch (Exception e) { return null; }
    }

    @Override
    protected void deleteSessionData(String sessionId, String userId) {
        try {
            java.io.File file = findSessionFile(getSessionStorePath(userId), sessionId);
            if (file != null && file.exists()) file.delete();
        } catch (Exception ignored) {}
    }

    @Override
    @SuppressWarnings("unchecked")
    protected java.util.List<java.util.Map<String, Object>> listSessionsMeta(String userId) {
        java.util.List<java.util.Map<String, Object>> sessions = new java.util.ArrayList<>();
        try {
            java.io.File dir = new java.io.File(getSessionStorePath(userId));
            if (!dir.exists() || !dir.isDirectory()) return sessions;
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files == null) return sessions;

            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

            for (java.io.File f : files) {
                TLMsg meta = readLastLineMeta(f);
                if (meta == null) continue;
                java.util.LinkedHashMap<String, Object> info = new java.util.LinkedHashMap<>();
                info.put("sessionId", meta.getStringParam("sessionId", ""));
                info.put("state", meta.getStringParam("state", ""));
                info.put("agentName", meta.getStringParam("agentName", ""));
                info.put("userMessage", meta.getStringParam("userMessage", ""));
                info.put("savedAt", f.lastModified());
                info.put("count", meta.getIntParam("count", 0));
                sessions.add(info);
            }
        } catch (Exception e) { /* ignore */ }
        return sessions;
    }

    // ======================== 文件特有工具方法 ========================

    private String getSessionStorePath(String userId) {
        String uid = (userId != null && !userId.isEmpty()) ? sanitizePathSegment(userId) : "default";
        return sessionStorePath + uid + "/session_store/";
    }

    private static String sanitizePathSegment(String name) {
        if (name == null) return "null";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String sessionFileName(String agentName, String sessionId) {
        String agent = (agentName != null && !agentName.isEmpty()) ? sanitizePathSegment(agentName) : "agent";
        String sid = sanitizePathSegment(sessionId != null ? sessionId : "unknown");
        return agent + "_" + sid + ".json";
    }

    private java.io.File findSessionFile(String dir, String sessionId) {
        java.io.File d = new java.io.File(dir);
        if (!d.exists() || !d.isDirectory()) return null;
        String sid = sanitizePathSegment(sessionId);
        java.io.File[] files = d.listFiles((dn, n) -> n.endsWith("_" + sid + ".json"));
        if (files != null && files.length > 0) return files[0];
        java.io.File legacy = new java.io.File(d, sid + ".json");
        return legacy.exists() ? legacy : null;
    }

    private TLMsg readLastLineMeta(java.io.File file) {
        if (file == null || !file.exists()) return null;
        try {
            String lastLine = null;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) lastLine = line;
                }
            }
            if (lastLine == null) return null;
            com.google.gson.JsonObject obj = gson.fromJson(lastLine, com.google.gson.JsonObject.class);
            TLMsg result = new TLMsg();
            result.setParam("sessionId", obj.get("sessionId").getAsString());
            result.setParam("state", obj.get("state").getAsString());
            result.setParam("agentName", obj.has("agentName") ? obj.get("agentName").getAsString() : "");
            result.setParam("userMessage", obj.has("userMessage") ? obj.get("userMessage").getAsString() : "");
            result.setParam("roundSeq", obj.has("roundSeq") ? obj.get("roundSeq").getAsInt() : 0);
            if (obj.has("messages")) result.setParam("count", obj.getAsJsonArray("messages").size());
            return result;
        } catch (Exception e) { return null; }
    }
}
