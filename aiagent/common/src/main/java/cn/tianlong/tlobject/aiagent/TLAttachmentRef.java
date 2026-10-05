package cn.tianlong.tlobject.aiagent;

import java.io.Serializable;
import java.util.Map;

/**
 * 附件引用。表示一条消息上挂着的资源（图片或文件），历史里只存引用不存内容。
 * attachments 字段驱动"清单"文本；images 字段驱动 provider 的图片块。
 * 作为POJO在TLMsg.args中传输并随会话存档 gson 序列化，不继承TLMsg。
 *
 * 创建日期：2026/10/5
 * 作者:tianlong
 */
public class TLAttachmentRef implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 磁盘上的图片文件 */
        IMAGE,
        /** 磁盘上的普通文件（CSV/文本/…），不产生图片块 */
        FILE,
        /** 外链图片 URL，透传给模型自行下载 */
        URL,
        /** DeepSeek Files API 的 file-api-xxx，仅透传 */
        FILE_ID
    }

    private Kind kind;
    /** 绝对路径（IMAGE/FILE） */
    private String path;
    /** 原始文件名，清单展示与模型引用用 */
    private String name;
    private String mime;
    private long size;
    /**
     * 本地抽取的元数据：width/height · rows/columns · chars · pages。
     * ⚠ 值必须是 JSON 原生类型；读取方一律经 {@code Number} 取数（如 ((Number) v).intValue()），
     * 因为 gson 反序列化后所有数字都变成 Double——直接强转 Integer 会在发送路径抛 ClassCastException。
     */
    private Map<String, Object> meta;
    private String url;
    private String fileId;
    /** low | high | original | auto（图片可选） */
    private String detail;
    /** user | tool | api —— webui 渲染区分气泡样式 */
    private String origin;

    public TLAttachmentRef() {}

    public static TLAttachmentRef forFile(String path, String name, String mime, String origin) {
        return forFile(path, name, mime, origin, Kind.FILE);
    }

    public static TLAttachmentRef forImage(String path, String name, String mime, String origin) {
        return forFile(path, name, mime, origin, Kind.IMAGE);
    }

    private static TLAttachmentRef forFile(String path, String name, String mime,
                                           String origin, Kind kind) {
        TLAttachmentRef r = new TLAttachmentRef();
        r.kind = kind;
        r.path = path;
        r.name = name;
        r.mime = mime;
        r.origin = origin;
        return r;
    }

    public static TLAttachmentRef forUrl(String url, String origin) {
        TLAttachmentRef r = new TLAttachmentRef();
        r.kind = Kind.URL;
        r.url = url;
        r.origin = origin;
        return r;
    }

    public static TLAttachmentRef forFileId(String fileId, String origin) {
        TLAttachmentRef r = new TLAttachmentRef();
        r.kind = Kind.FILE_ID;
        r.fileId = fileId;
        r.origin = origin;
        return r;
    }

    /** 是否会作为图片块发给模型 */
    public boolean isImageLike() {
        return kind == Kind.IMAGE || kind == Kind.URL || kind == Kind.FILE_ID;
    }

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
}
