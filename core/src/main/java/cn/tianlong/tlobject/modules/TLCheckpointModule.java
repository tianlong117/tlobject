package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLMsgUtils;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.tianlong.tlobject.base.TLParamString.*;

/**
 * 断点续传模块。利用 beforeMsgTable/afterMsgTable 机制，
 * 在消息处理前后自动保存/清除断点，实现框架级的 WAL（Write-Ahead Log）。
 *
 * <p>原理:
 * <pre>
 *   beforeMsgTable: saveCheckpoint(moduleName, msg)  → 写入断点文件
 *   action:         业务方法执行
 *   nextMsg:        nextMsg 链执行
 *   afterMsgTable:  clearAndForward(moduleName, msg) → 无 nextMsg 则清除，有则断点前移
 * </pre>
 *
 * <p>使用方式（模块 XML 配置，无需修改 Java 代码）:
 * <pre>
 * &lt;beforeMsgTable&gt;
 *     &lt;action value="*"&gt;
 *         &lt;msg action="saveCheckpoint" destination="checkpoint"/&gt;
 *     &lt;/action&gt;
 * &lt;/beforeMsgTable&gt;
 * &lt;afterMsgTable&gt;
 *     &lt;action value="*"&gt;
 *         &lt;msg action="clearAndForward" destination="checkpoint"/&gt;
 *     &lt;/action&gt;
 * &lt;/afterMsgTable&gt;
 * &lt;startMsg&gt;
 *     &lt;msg action="restoreCheckpoint" destination="checkpoint"/&gt;
 * &lt;/startMsg&gt;
 * </pre>
 *
 * <p>配置参数:
 * <pre>
 * &lt;param name="checkpointDir" value="data/checkpoint/"/&gt;
 * </pre>
 *
 * @author tianlong
 */
public class TLCheckpointModule extends TLBaseModule {

    private String checkpointDir;
    private final ConcurrentHashMap<String, Object> moduleLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> seqCounters = new ConcurrentHashMap<>();

    public TLCheckpointModule() {
        super();
    }

    public TLCheckpointModule(String name) {
        super(name);
    }

    public TLCheckpointModule(String name, TLObjectFactory moduleFactory) {
        super(name, moduleFactory);
    }

    @Override
    protected TLBaseModule init() {
        if (params != null && params.get(CHECKPOINT_P_DIR) != null) {
            checkpointDir = params.get(CHECKPOINT_P_DIR);
        } else {
            checkpointDir = "data/checkpoint/";
        }
        File dir = new File(checkpointDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 扫描已有历史文件，恢复 seq 计数器
        initSeqCounters();
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case CHECKPOINT_SAVE:
                returnMsg = saveCheckpoint(fromWho, msg);
                break;
            case CHECKPOINT_CLEARANDFORWARD:
                returnMsg = clearAndForward(fromWho, msg);
                break;
            case CHECKPOINT_RESTORE:
                returnMsg = restoreCheckpoint(fromWho, msg);
                break;
            case CHECKPOINT_GETHISTORY:
                returnMsg = getHistory(fromWho, msg);
                break;
            case CHECKPOINT_GETGLOBALHISTORY:
                returnMsg = getGlobalHistory(fromWho, msg);
                break;
            default:
        }
        return returnMsg;
    }

    /**
     * beforeMsgTable 调用：保存断点（WAL）。
     * 将调用模块的当前 msg（含 nextMsg 链）序列化到文件。
     * <p>
     * 调用模块名从 {@code msg.getPrevious()} 获取（由 putMsg 自动设置）。
     * 原始 msg 从 {@code msg.getSystemParam(DOWITHMSG)} 获取（由 doMsgList 注入）。
     */
    public TLMsg saveCheckpoint(Object fromWho, TLMsg msg) {
        String moduleName = msg.getPrevious();
        if (moduleName == null) {
            return createMsg();
        }
        TLMsg originalMsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
        if (originalMsg != null) {
            // 保存断点文件（覆盖写），标记不可关闭
            saveToFile(moduleName, originalMsg);
            shutdownable = false;
            // 如果配置了 ifHistory=true，追加历史记录
            if ("true".equals(msg.getStringParam(CHECKPOINT_P_IFHISTORY, "false"))) {
                appendToHistory(moduleName, originalMsg);
            }
        }
        return createMsg();
    }

    /**
     * afterMsgTable 调用：清除当前断点，如有 nextMsg 则将断点前移。
     * <p>
     * 当前 action + nextMsg 链执行成功后调用:
     * <ul>
     *   <li>若原始 msg 有 nextMsg → 保存 nextMsg 为新的断点（断点前移）</li>
     *   <li>若原始 msg 无 nextMsg → 删除断点文件（处理链结束）</li>
     * </ul>
     */
    public TLMsg clearAndForward(Object fromWho, TLMsg msg) {
        String moduleName = msg.getPrevious();
        if (moduleName == null) {
            return createMsg();
        }
        TLMsg originalMsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
        if (originalMsg != null) {
            TLMsg nextMsg = originalMsg.getNextMsg();
            if (nextMsg != null) {
                saveToFile(moduleName, nextMsg);
            } else {
                deleteFile(moduleName);
            }
            shutdownable = true;
        }
        return createMsg();
    }

    /**
     * startMsg 调用：恢复断点。
     * <p>
     * 启动时检查该模块是否有未清除的断点文件。
     * 有则加载 msg 并通过 putMsg 重新触发完整处理流程
     * （before → action → nextMsg → after）。
     */
    public TLMsg restoreCheckpoint(Object fromWho, TLMsg msg) {
        String moduleName = msg.getPrevious();
        if (moduleName == null) {
            return createMsg();
        }
        // 检查 seq 参数：有 seq 则从历史定位，无 seq 则从断点文件恢复
        String seqStr = msg.getStringParam(CHECKPOINT_P_SEQ, null);
        if (seqStr != null && !seqStr.isEmpty()) {
            int seq = Integer.parseInt(seqStr);
            TLMsg historyMsg = findBySeq(moduleName, seq);
            if (historyMsg != null) {
                putLog("replaying from history seq=" + seq + " for module: " + moduleName
                        + ", action: " + historyMsg.getAction(), LogLevel.INFO, "restoreCheckpoint");
                return putMsg(moduleName, historyMsg);
            }
            putLog("history seq=" + seq + " not found for module: " + moduleName,
                    LogLevel.WARN, "restoreCheckpoint");
            return createMsg();
        }
        // 检查断点文件：有则崩溃恢复（历史继续），无则正常启动（备份旧历史，新会话）
        boolean hasCheckpoint = getCheckpointFile(moduleName).exists();
        if (!hasCheckpoint) {
            rotateHistoryFile(moduleName);
        }
        // 从断点文件恢复
        TLMsg savedMsg = loadFromFile(moduleName);
        if (savedMsg != null) {
            putLog("restoring checkpoint for module: " + moduleName
                    + ", action: " + savedMsg.getAction(), LogLevel.INFO, "restoreCheckpoint");
            return putMsg(moduleName, savedMsg);
        }
        return createMsg();
    }

    // ==================== 历史记录 ====================

    /**
     * 正常启动时（断点文件不存在），将旧历史文件按时间戳备份，开始新会话。
     */
    private void rotateHistoryFile(String moduleName) {
        synchronized (getLock(moduleName)) {
            File historyFile = new File(checkpointDir, moduleName + ".history.jsonl");
            if (historyFile.exists()) {
                String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                File backupFile = new File(checkpointDir, moduleName + "." + timestamp + ".history.jsonl");
                if (historyFile.renameTo(backupFile)) {
                    putLog("rotated history for module: " + moduleName
                            + " → " + backupFile.getName(), LogLevel.INFO, "restoreCheckpoint");
                }
            }
            // 新会话，seq 从 1 开始
            seqCounters.remove(moduleName);
        }
    }

    /**
     * 启动时扫描已有历史文件，恢复各模块的 seq 计数器。
     */
    private void initSeqCounters() {
        File dir = new File(checkpointDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".history.jsonl"));
        if (files == null) return;
        for (File file : files) {
            String moduleName = file.getName().replace(".history.jsonl", "");
            int lastSeq = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 行格式: {"seq":N,...} ，用简单的字符串匹配提取 seq
                    int idx = line.indexOf("\"seq\":");
                    if (idx >= 0) {
                        int start = idx + 6;
                        int end = line.indexOf(",", start);
                        if (end < 0) end = line.indexOf("}", start);
                        if (end > start) {
                            lastSeq = Integer.parseInt(line.substring(start, end).trim());
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (lastSeq > 0) {
                seqCounters.put(moduleName, new AtomicInteger(lastSeq));
            }
        }
    }

    /**
     * 追加一行历史记录到 {module}.history.jsonl。
     */
    private void appendToHistory(String moduleName, TLMsg msg) {
        synchronized (getLock(moduleName)) {
            AtomicInteger counter = seqCounters.computeIfAbsent(moduleName,
                    k -> new AtomicInteger(0));
            int seq = counter.incrementAndGet();

            File file = new File(checkpointDir, moduleName + ".history.jsonl");
            try (FileWriter writer = new FileWriter(file, true)) {
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                String previous = msg.getPrevious() != null ? msg.getPrevious() : "";
                String msgJson = TLMsgUtils.msgToJson(msg);
                // previous 表示谁把这条 msg 传过来的，与 module(处理者) 组成 previous→module 跳关系
                writer.write("{\"seq\":" + seq
                        + ",\"time\":\"" + time
                        + "\",\"module\":\"" + moduleName
                        + "\",\"previous\":\"" + previous
                        + "\",\"action\":\"" + msg.getAction()
                        + "\",\"msg\":" + (msgJson != null ? msgJson : "{}") + "}\n");
            } catch (IOException e) {
                putLog("appendHistory failed for " + moduleName + ": " + e.getMessage(),
                        LogLevel.ERROR, "saveCheckpoint");
            }
        }
    }

    /**
     * 从历史文件中查找指定 seq 的 msg。
     */
    private TLMsg findBySeq(String moduleName, int targetSeq) {
        synchronized (getLock(moduleName)) {
            File file = new File(checkpointDir, moduleName + ".history.jsonl");
            if (!file.exists()) return null;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String prefix = "\"seq\":" + targetSeq + ",";
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(prefix)) {
                        // 从 JSONL 行中提取 msg 字段
                        int msgStart = line.indexOf("\"msg\":");
                        if (msgStart < 0) continue;
                        String msgJson = line.substring(msgStart + 6, line.length() - 1);
                        return TLMsgUtils.jsonToMsg(msgJson);
                    }
                }
            } catch (IOException e) {
                putLog("findBySeq failed for " + moduleName + ": " + e.getMessage(),
                        LogLevel.ERROR, "restoreCheckpoint");
            }
        }
        return null;
    }

    /**
     * 返回某模块的消息历史列表（不含完整 msg，仅摘要信息）。
     */
    public TLMsg getHistory(Object fromWho, TLMsg msg) {
        String moduleName = (String) msg.getParam(CHECKPOINT_P_MODULE);
        if (moduleName == null) {
            moduleName = msg.getPrevious();
        }
        if (moduleName == null) {
            return createMsg().setParam(RESULT, new ArrayList<>());
        }
        List<Map<String, Object>> records = new ArrayList<>();
        synchronized (getLock(moduleName)) {
            File file = new File(checkpointDir, moduleName + ".history.jsonl");
            if (!file.exists()) {
                return createMsg().setParam(RESULT, records);
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    Map<String, Object> record = new HashMap<>();
                    record.put("seq", extractJsonInt(line, "seq"));
                    record.put("time", extractJsonString(line, "time"));
                    record.put("module", extractJsonString(line, "module"));
                    record.put("previous", extractJsonString(line, "previous"));
                    record.put("action", extractJsonString(line, "action"));
                    records.add(record);
                }
            } catch (IOException e) {
                putLog("getHistory failed for " + moduleName + ": " + e.getMessage(),
                        LogLevel.ERROR, "getHistory");
            }
        }
        return createMsg().setParam(RESULT, records);
    }

    /**
     * 扫描所有 *.history.jsonl，按时间合并，返回全局消息流动轨迹。
     */
    public TLMsg getGlobalHistory(Object fromWho, TLMsg msg) {
        List<Map<String, Object>> allRecords = new ArrayList<>();
        File dir = new File(checkpointDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".history.jsonl"));
        if (files != null) {
            for (File file : files) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        Map<String, Object> record = new HashMap<>();
                        record.put("seq", extractJsonInt(line, "seq"));
                        record.put("time", extractJsonString(line, "time"));
                        record.put("module", extractJsonString(line, "module"));
                        record.put("previous", extractJsonString(line, "previous"));
                        record.put("action", extractJsonString(line, "action"));
                        allRecords.add(record);
                    }
                } catch (IOException e) {
                    putLog("getGlobalHistory read failed: " + file.getName(), LogLevel.ERROR, "getGlobalHistory");
                }
            }
        }
        // 按时间排序
        allRecords.sort((a, b) -> {
            String ta = (String) a.getOrDefault("time", "");
            String tb = (String) b.getOrDefault("time", "");
            return ta.compareTo(tb);
        });
        return createMsg().setParam(RESULT, allRecords);
    }

    private int extractJsonInt(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":");
        if (idx < 0) return 0;
        int start = idx + key.length() + 3;
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        try { return Integer.parseInt(json.substring(start, end).trim()); } catch (Exception e) { return 0; }
    }

    private String extractJsonString(String json, String key) {
        int idx = json.indexOf("\"" + key + "\":\"");
        if (idx < 0) return null;
        int start = idx + key.length() + 4;
        int end = json.indexOf("\"", start);
        if (end > start) return json.substring(start, end);
        return null;
    }

    // ==================== 内部文件操作 ====================

    private File getCheckpointFile(String moduleName) {
        return new File(checkpointDir, moduleName + ".json");
    }

    private Object getLock(String moduleName) {
        return moduleLocks.computeIfAbsent(moduleName, k -> new Object());
    }

    private void saveToFile(String moduleName, TLMsg msg) {
        synchronized (getLock(moduleName)) {
            File file = getCheckpointFile(moduleName);
            try (FileWriter writer = new FileWriter(file)) {
                String json = TLMsgUtils.msgToJson(msg);
                if (json != null) {
                    writer.write(json);
                }
            } catch (IOException e) {
                putLog("saveCheckpoint failed for " + moduleName + ": " + e.getMessage(),
                        LogLevel.ERROR, "saveCheckpoint");
            }
        }
    }

    private TLMsg loadFromFile(String moduleName) {
        synchronized (getLock(moduleName)) {
            File file = getCheckpointFile(moduleName);
            if (!file.exists()) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return TLMsgUtils.jsonToMsg(sb.toString());
            } catch (IOException e) {
                putLog("loadCheckpoint failed for " + moduleName + ": " + e.getMessage(),
                        LogLevel.ERROR, "restoreCheckpoint");
                return null;
            }
        }
    }

    private void deleteFile(String moduleName) {
        synchronized (getLock(moduleName)) {
            File file = getCheckpointFile(moduleName);
            if (file.exists()) {
                file.delete();
            }
        }
    }
}
