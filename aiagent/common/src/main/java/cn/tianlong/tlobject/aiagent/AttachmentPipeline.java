package cn.tianlong.tlobject.aiagent;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.modules.LogLevel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附件领域：归一化 / 登记 / 清单 / 图片注入 / base64 剥离 / 白名单。
 * Phase 1 从 TLAiAgent 原样搬入，行为逐字保持。
 */
final class AttachmentPipeline implements TLAiAgentParamString {

    private final TLAiAgent owner;

    // 配置快照（setModuleParams 解析后由 TLAiAgent.refresh 注入）
    private int manifestMaxEntries = 20;
    private int maxAttachmentsPerMessage = 10;
    private boolean feedToolImages = true;
    private String imageDeclareKey = "image_for_model";
    private List<String> imageDataKeys = new ArrayList<>(Arrays.asList(
            "screenshot_base64", "image_base64", "image_path"));
    private int maxParseKB = 8192;

    AttachmentPipeline(TLAiAgent owner) {
        this.owner = owner;
    }

    /** 配置在解析前为默认值；setModuleParams 结束时刷新 */
    void refreshConfig(int manifestMaxEntries, int maxAttachmentsPerMessage,
                       boolean feedToolImages, String imageDeclareKey,
                       List<String> imageDataKeys, int maxParseKB) {
        this.manifestMaxEntries = manifestMaxEntries;
        this.maxAttachmentsPerMessage = maxAttachmentsPerMessage;
        this.feedToolImages = feedToolImages;
        this.imageDeclareKey = imageDeclareKey;
        this.imageDataKeys = imageDataKeys;
        this.maxParseKB = maxParseKB;
    }

    // ==================== 归一化 ====================

    /** 兼容旧调用：userId 缺省按 sessionId（框架约定）。 */
    List<TLAttachmentRef> normalize(TLMsg msg, String sessionId) {
        return normalize(msg, sessionId, null);
    }

    /** 把调用方传的 AI_P_ATTACHMENTS（List<Map> 四形态）归一化成 ref 列表；解析失败逐条忽略 */
    @SuppressWarnings("unchecked")
    List<TLAttachmentRef> normalize(TLMsg msg, String sessionId, String userId) {
        List<TLAttachmentRef> out = new ArrayList<>();
        Object raw = msg.getParam(AI_P_ATTACHMENTS);
        if (!(raw instanceof List)) return out;
        String uid = userId != null ? userId : sessionId;
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) continue;
            // 配额只算"已接受"的条目（out）：被校验拒绝的畸形条目不应挤掉其后的有效附件
            if (out.size() >= maxAttachmentsPerMessage) break;
            Map<String, Object> m = (Map<String, Object>) o;
            try {
                String url = strOf(m.get("url"));
                String fileId = strOf(m.get("fileId"));
                String b64 = strOf(m.get("base64"));
                String path = strOf(m.get("path"));
                String name = strOf(m.get("name"));
                String origin = m.get("origin") == null ? "api" : strOf(m.get("origin"));
                if (!fileId.isEmpty()) {
                    out.add(TLAttachmentRef.forFileId(fileId, origin));
                } else if (!url.isEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        owner.putLog("[VISION] 附件 URL 非 http(s)，已忽略: " + url, LogLevel.WARN);
                        continue;
                    }
                    if (url.length() > 8192) {
                        owner.putLog("[VISION] 附件 URL 过长，已忽略", LogLevel.WARN);
                        continue;
                    }
                    out.add(TLAttachmentRef.forUrl(url, origin));
                } else if (!b64.isEmpty()) {
                    // IGNOREMODULEISNULL：attachmentStore 未配置时降级为"该附件忽略 + WARN"，
                    // 而不是让 putMsg 走 moduleFactory.shutdown(-1) 关停整个应用
                    TLMsg r = owner.putMsg(M_ATTACHMENTSTORE, owner.createMsg().setAction(ATTACH_STOREBASE64)
                            .setParam("base64", b64).setParam("mime", strOf(m.get("mime")))
                            .setParam("name", name.isEmpty() ? "image" : name)
                            .setParam("origin", origin).setParam(AI_P_USERID, uid)
                            .setSystemParam(IGNOREMODULEISNULL, true));
                    if (r != null && r.getParam("ref") != null)
                        out.add((TLAttachmentRef) r.getParam("ref"));
                    else owner.putLog("[VISION] 附件落盘失败（base64）", LogLevel.WARN);
                } else if (!path.isEmpty()) {
                    TLMsg r = owner.putMsg(M_ATTACHMENTSTORE, owner.createMsg().setAction(ATTACH_REGISTER)
                            .setParam("config", m).setParam(AI_P_USERID, uid)
                            .setSystemParam(IGNOREMODULEISNULL, true));
                    if (r != null && r.getParam("ref") != null)
                        out.add((TLAttachmentRef) r.getParam("ref"));
                    else owner.putLog("[VISION] 附件登记失败: " + path, LogLevel.WARN);
                }
            } catch (Exception e) {
                owner.putLog("[VISION] 附件解析失败: " + e, LogLevel.WARN);
            }
        }
        return out;
    }

    /** 构造本轮用户消息条目并挂上附件（三个入口共用） */
    TLConversationHistory buildUserEntry(String userMessage, List<TLAttachmentRef> atts) {
        TLConversationHistory e =
                new TLConversationHistory(TLConversationHistory.Role.user, userMessage);
        if (atts != null && !atts.isEmpty()) e.setAttachments(atts);
        return e;
    }

    // ==================== 清单 ====================

    /**
     * 把 attachments 渲染成清单文本拼在消息正文前（只在发送副本上做，不写回存储）。
     * 清单是"模型必须先知道有什么"的唯一来源；元数据全部本地抽取，不调 LLM。
     */
    void applyManifest(List<TLConversationHistory> list) {
        for (TLConversationHistory h : list) applyManifestToEntry(h);
    }

    /** 给单条消息拼接附件清单（发送视图专用；存储侧不得调用，否则会在下一轮重复叠加） */
    void applyManifestToEntry(TLConversationHistory h) {
        if (h == null || !h.hasAttachments()) return;
        String manifest = buildManifest(h.getAttachments());
        if (manifest.isEmpty()) return;
        String body = h.getContent() == null ? "" : h.getContent();
        h.setContent(manifest + "\n" + body);
    }

    private String buildManifest(List<TLAttachmentRef> refs) {
        if (refs == null || refs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[附件]\n");
        int shown = 0;
        boolean anyPath = false;
        for (TLAttachmentRef r : refs) {
            if (shown >= manifestMaxEntries) {
                sb.append("…还有 ").append(refs.size() - shown).append(" 个\n");
                break;
            }
            shown++;
            if (r.getPath() != null) anyPath = true;
            sb.append(shown).append(". ").append(sanitizeFileName(r.getName()))
              .append(" — ").append(r.getMime() == null ? "未知类型" : r.getMime())
              .append(", ").append(humanSize(r.getSize()));
            Map<String, Object> meta = r.getMeta();
            if (meta != null) {
                // ⚠ gson 往返后所有数字都变成 Double（ObjectTypeAdapter）——一律经 num() 取整，
                //   否则恢复会话后清单会渲染成 "1043.0 行"
                Integer w = num(meta.get("width")), hgt = num(meta.get("height"));
                // width/height 可能只有一个（如 gson 往返丢了 height）——两个都有效才渲染，避免 "800×null"
                if (w != null && w > 0 && hgt != null && hgt > 0) {
                    sb.append(", ").append(w).append("×").append(hgt);
                }
                Integer rows = num(meta.get("rows"));
                if (rows != null) sb.append(", ").append(rows).append(" 行");
                if (meta.get("columns") != null) sb.append(", 列: ").append(meta.get("columns"));
                Integer chars = num(meta.get("chars"));
                if (chars != null) sb.append(", ").append(chars).append(" 字符");
                Integer pages = num(meta.get("pages"));
                if (pages != null) sb.append(", ").append(pages).append(" 页");
            }
            // 按 kind 渲染引用位置：URL/FILE_ID 没有本地路径，不能统一写 "路径"（会渲染出 "路径: null"，
            // 且尾句会让模型对没有路径的附件调 view_image）
            TLAttachmentRef.Kind kind = r.getKind();
            if (kind == TLAttachmentRef.Kind.URL && r.getUrl() != null) {
                sb.append(" — 地址: ").append(r.getUrl()).append("（模型可直接读取该链接）");
            } else if (kind == TLAttachmentRef.Kind.FILE_ID && r.getFileId() != null) {
                sb.append(" — 文件ID: ").append(r.getFileId());
            } else if (r.getPath() != null) {
                sb.append(" — 路径: ").append(r.getPath());
            } else if (r.getUrl() != null) {          // kind 缺失（旧存档）时按字段兜底
                sb.append(" — 地址: ").append(r.getUrl());
            } else if (r.getFileId() != null) {
                sb.append(" — 文件ID: ").append(r.getFileId());
            }
            sb.append("\n");
        }
        if (anyPath) {
            sb.append(refs.size() == 1
                    ? "查看图片内容请调用 view_image(路径)；其他文件可直接用路径本地处理。"
                    : "需要查看某张图片时调用 view_image(路径)；其他文件可直接用路径本地处理。");
        } else {
            sb.append("以上附件没有本地路径，模型可直接读取链接/引用内容。");
        }
        return sb.toString();
    }

    // ==================== 工具产图 / 发送视图 ====================

    /**
     * 从工具输出里提取"技能表态要给模型看"的图片。
     * 缺省不给（保守默认）——技能必须在输出 JSON 里写 "image_for_model": true。
     * 返回 null 表示"未声明或解析不可用"，调用方保持原文本不动。
     */
    @SuppressWarnings("unchecked")
    List<TLAttachmentRef> extractDeclaredImages(String output, String userId,
                                                List<TLConversationHistory> history) {
        if (!feedToolImages || output == null) return null;
        if (output.length() > (long) maxParseKB * 1024) return null;         // 护栏 2
        if (output.isEmpty() || output.charAt(0) != '{') return null;        // 护栏 1
        if (output.indexOf(imageDeclareKey) < 0) return null;                // 护栏 1
        try {
            com.google.gson.JsonObject o = owner.gson.fromJson(output, com.google.gson.JsonObject.class);
            if (o == null || !o.has(imageDeclareKey)) return null;
            if (!o.get(imageDeclareKey).getAsBoolean()) return null;
            for (String key : imageDataKeys) {
                if (!o.has(key) || o.get(key).isJsonNull()) continue;
                String v = o.get(key).getAsString();
                if (key.endsWith("_path")) {
                    // 引用型：只能看"本会话已登记的附件"（白名单），防模型被诱导去读任意文件
                    if (!isRegisteredPath(history, v)) {
                        owner.putLog("[VISION] 路径不在本会话附件白名单内，拒绝注入: " + v, LogLevel.WARN);
                        return null;
                    }
                    TLMsg r = owner.putMsg(M_ATTACHMENTSTORE, owner.createMsg().setAction(ATTACH_REGISTER)
                            .setParam("config", new LinkedHashMap<>(Map.of(
                                    "path", v, "name", new File(v).getName(), "origin", "tool")))
                            .setParam(AI_P_USERID, userId)
                            .setSystemParam(IGNOREMODULEISNULL, true));   // 缺模块 → 降级不投图，不退进程
                    if (r != null && r.getParam("ref") != null)
                        return new ArrayList<>(List.of((TLAttachmentRef) r.getParam("ref")));
                    return null;
                }
                TLMsg r = owner.putMsg(M_ATTACHMENTSTORE, owner.createMsg().setAction(ATTACH_STOREBASE64)
                        .setParam("base64", v).setParam("name", "tool_image")
                        .setParam("origin", "tool").setParam(AI_P_USERID, userId)
                        .setSystemParam(IGNOREMODULEISNULL, true));   // 缺模块 → 降级不投图，不退进程
                if (r != null && r.getParam("ref") != null)
                    return new ArrayList<>(List.of((TLAttachmentRef) r.getParam("ref")));
                return null;
            }
        } catch (Exception e) {
            owner.putLog("[VISION] 工具产图解析失败（忽略，原文照旧）: " + e, LogLevel.DEBUG);  // 护栏 3
        }
        return null;
    }

    /** 提取 + 剥离的组合：返回 tool 消息文本 + 要挂到 user 消息的图片 */
    ToolImageBundle extractAndStrip(String output, String userId,
                                    List<TLConversationHistory> history) {
        if (output == null) return new ToolImageBundle(null, List.of());
        List<TLAttachmentRef> imgs = extractDeclaredImages(output, userId, history);
        String text = imgs != null ? stripImageKeys(output) : output;
        return new ToolImageBundle(text, imgs != null ? imgs : List.of());
    }

    /** 把已提取的图数据 key 从文本里换成占位符（避免同一份 base64 在上下文重复占位） */
    String stripImageKeys(String output) {
        if (output == null) return null;
        try {
            com.google.gson.JsonObject o = owner.gson.fromJson(output, com.google.gson.JsonObject.class);
            if (o == null) return output;
            boolean changed = false;
            for (String key : imageDataKeys) {
                if (key.endsWith("_path")) continue;   // 路径保留：模型/其它工具还要用
                if (o.has(key) && !o.get(key).isJsonNull()) {
                    o.addProperty(key, "[已提取为图片]");
                    changed = true;
                }
            }
            return changed ? owner.gson.toJson(o) : output;
        } catch (Exception e) {
            return output;
        }
    }

    /** 白名单：本会话历史里出现过的附件路径（attachments ∪ images 两个字段的并集） */
    private static boolean isRegisteredPath(List<TLConversationHistory> history, String path) {
        if (history == null || path == null) return false;
        String target = canonical(path);
        for (TLConversationHistory h : history) {
            for (List<TLAttachmentRef> list : Arrays.asList(h.getAttachments(), h.getImages())) {
                if (list == null) continue;
                for (TLAttachmentRef r : list) {
                    if (r.getPath() != null && canonical(r.getPath()).equals(target)) return true;
                }
            }
        }
        return false;
    }

    private static String canonical(String p) {
        try { return new File(p).getCanonicalPath(); }
        catch (Exception e) { return new File(p).getAbsolutePath(); }
    }

    /** 注入图保留策略：只保留最近 K 条带图消息的图片，更早的置空（文本保留） */
    void applyImageRetention(List<TLConversationHistory> list) {
        int seen = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            TLConversationHistory h = list.get(i);
            if (!h.hasImages()) continue;
            seen++;
            if (seen > owner.maxImagesInContext) h.setImages(null);
        }
    }

    /** 发送视图兜底：截图 base64 载荷降级为占位符（同一轮内 tool 刚执行完、尚未入史裁剪的本地列表也挡） */
    static void stripImagePayloads(List<TLConversationHistory> list) {
        for (TLConversationHistory h : list) {
            TLAiContext.stripImagePayload(h);
        }
    }

    /** 整批工具结果写完后调用一次：图片只能挂 user 消息（DeepSeek 400），
     *  且必须落在整簇 tool 之后，保持 tool 消息连续（sanitizeToolPairs 的向后扫描才不误判孤儿）。 */
    void flushToolImages(List<TLConversationHistory> history,
                         List<TLAttachmentRef> pendingImages) {
        if (pendingImages.isEmpty()) return;
        TLConversationHistory m = new TLConversationHistory(
                TLConversationHistory.Role.user, "[工具结果：图片]");
        m.setImages(new ArrayList<>(pendingImages));
        history.add(m);
        pendingImages.clear();
    }

    // ==================== 内部工具 ====================

    private static String strOf(Object o) { return o == null ? "" : String.valueOf(o); }

    /** 文件名进清单前转义：防"文件名里写指令" */
    static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "未命名";
        String s = name.replaceAll("[\\r\\n\\t\\u0000-\\u001f\\[\\]]", " ");
        return s.length() > 64 ? s.substring(0, 64) + "…" : s;
    }

    static String humanSize(long size) {
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return (size / 1024) + "KB";
        return String.format("%.1fMB", size / 1024.0 / 1024.0);
    }

    /** meta 值本轮是 Integer、gson 反序列化后是 Double，统一经 Number 取整 */
    static Integer num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : null;
    }

    record ToolImageBundle(String text, List<TLAttachmentRef> images) {
    }
}
