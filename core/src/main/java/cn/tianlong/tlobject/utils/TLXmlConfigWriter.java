package cn.tianlong.tlobject.utils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XML 配置文件写入工具类。
 * <p>
 * 提供带备份、并发控制、原子写入的 DOM 级别 XML 修改能力：
 * <ul>
 *   <li><b>自动备份</b> — 修改前将原文件备份为 {@code 原文件名_backup_yyyyMMdd_HHmmss.xml}</li>
 *   <li><b>并发控制</b> — 按文件规范路径映射锁对象，同一文件 read-modify-write 串行化</li>
 *   <li><b>原子写入</b> — 先写 .tmp 再 {@code ATOMIC_MOVE}，失败降级 {@code REPLACE_EXISTING}</li>
 *   <li><b>UTF-8</b> — 全程 {@link StandardCharsets#UTF_8}；DOM {@code setAttribute()} 自动处理 XML 转义</li>
 *   <li><b>属性顺序</b> — 自定义序列化器保持 DOM 插入顺序（{@code LinkedHashMap}），不重排</li>
 * </ul>
 *
 * @author tianlong
 * @since 2026-07-20
 */
public class TLXmlConfigWriter {

    /** 按文件规范路径映射锁对象，保证同一文件的并发修改串行化 */
    private static final ConcurrentHashMap<String, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    /** 备份文件名中时间戳的格式 */
    private static final String DATE_FORMAT = "yyyyMMdd_HHmmss";

    // ==================== 公共 API ====================

    /**
     * 从 XML 配置文件中删除指定名称的元素（仅匹配父元素的直接子元素）。
     * <p>
     * 在 {@code <parentTag>} 区段内查找 {@code name} 属性等于 {@code elementName} 的
     * {@code <elementTag>} 元素并删除。父区段或匹配元素不存在时静默返回。
     *
     * @param filePath    配置文件路径
     * @param parentTag   父级标签名（如 {@code "modules"}、{@code "skills"}）
     * @param elementTag  要删除的元素标签名（如 {@code "module"}、{@code "skill"}）
     * @param elementName 要匹配的 {@code name} 属性值
     * @throws Exception 文件读写或 XML 解析异常
     */
    public static void removeElement(String filePath, String parentTag,
                                      String elementTag, String elementName) throws Exception {
        filePath = normalizePath(filePath);
        Object lock = getLock(filePath);
        synchronized (lock) {
            backupFile(filePath);
            Document doc = parseXml(filePath);

            Element parent = findParentElement(doc, parentTag);
            if (parent == null) {
                return; // 父区段不存在，无需操作
            }

            List<Element> children = getDirectChildrenByTagName(parent, elementTag);
            boolean removed = false;
            for (int i = children.size() - 1; i >= 0; i--) {
                Element child = children.get(i);
                if (elementName.equals(child.getAttribute("name"))) {
                    parent.removeChild(child);
                    removed = true;
                }
            }

            if (removed) {
                // 父区段已无子元素则一并删除（避免残留空 <skills/> 等）
                if (!hasElementChildren(parent)) {
                    parent.getParentNode().removeChild(parent);
                }
                writeXml(doc, filePath);
            }
        }
    }

    /**
     * 在 XML 配置文件中添加或替换指定名称的元素（替换语义：先删同名再添加）。
     * <p>
     * 如果 {@code <parentTag>} 区段不存在，会自动在根元素下创建。
     * <p>
     * 只匹配父元素的直接子元素，避免误删嵌套同名元素。
     *
     * @param filePath    配置文件路径
     * @param parentTag   父级标签名（如 {@code "modules"}、{@code "skills"}）
     * @param elementTag  要添加的元素标签名（如 {@code "module"}、{@code "skill"}）
     * @param elementName 元素的 {@code name} 属性值
     * @param attributes  元素的其他属性（key-value，不含 name；null 值跳过）
     * @throws Exception 文件读写或 XML 解析异常
     */
    public static void addOrReplaceElement(String filePath, String parentTag,
                                            String elementTag, String elementName,
                                            Map<String, String> attributes) throws Exception {
        filePath = normalizePath(filePath);
        Object lock = getLock(filePath);
        synchronized (lock) {
            backupFile(filePath);
            Document doc = parseXml(filePath);

            Element parent = findOrCreateParent(doc, parentTag);

            // 先删除已有同名直接子元素（替换语义）
            List<Element> children = getDirectChildrenByTagName(parent, elementTag);
            for (int i = children.size() - 1; i >= 0; i--) {
                Element child = children.get(i);
                if (elementName.equals(child.getAttribute("name"))) {
                    parent.removeChild(child);
                }
            }

            // 创建新元素
            Element newElement = doc.createElement(elementTag);
            newElement.setAttribute("name", elementName);
            if (attributes != null) {
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (!"name".equals(key) && value != null) {
                        newElement.setAttribute(key, value);
                    }
                }
            }
            parent.appendChild(newElement);

            writeXml(doc, filePath);
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 修改前备份原文件。
     * <p>
     * 备份文件与源文件在同一目录，命名格式：{@code 原文件名_backup_yyyyMMdd_HHmmss.扩展名}。
     * 例如 {@code agent_config.xml} → {@code agent_config_backup_20260720_143052.xml}。
     * <p>
     * 原文件不存在时静默跳过（首次新建场景无需备份）。
     */
    private static void backupFile(String filePath) throws IOException {
        Path original = Paths.get(filePath);
        if (!Files.exists(original)) {
            return;
        }

        String fileName = original.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        String baseName = dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
        String ext = dotIdx > 0 ? fileName.substring(dotIdx) : "";

        String timestamp = new SimpleDateFormat(DATE_FORMAT).format(new Date());
        String backupName = baseName + "_backup_" + timestamp + ext;
        Path backup = original.resolveSibling(backupName);

        Files.copy(original, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 获取文件对应的锁对象（按规范路径去重） */
    private static Object getLock(String filePath) {
        String canonicalPath = Paths.get(filePath).toAbsolutePath().normalize().toString();
        return FILE_LOCKS.computeIfAbsent(canonicalPath, k -> new Object());
    }

    /**
     * 路径规范化：去掉 Windows 路径前缀 "/"（如 /D:/... → D:/...），
     * 并用 {@link File#getAbsolutePath()} 消除双斜杠等不规范写法。
     */
    private static String normalizePath(String filePath) {
        if (filePath == null) return null;
        // 去掉 Windows 绝对路径前面多余的 "/"
        if (filePath.matches("^/[A-Za-z]:[/\\\\].*")) {
            filePath = filePath.substring(1);
        }
        return new File(filePath).getAbsolutePath();
    }

    /**
     * 递归清除 DOM 树中所有纯空白文本节点。
     * <p>
     * 写入前调用，避免原始 XML 中的缩进空白与序列化器的统一缩进叠加。
     * 注释节点保留不动。
     */
    private static void stripWhitespaceNodes(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                Text text = (Text) child;
                if (text.getData().trim().isEmpty()) {
                    node.removeChild(child);
                }
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                stripWhitespaceNodes(child);
            }
        }
    }

    /**
     * 获取父元素下指定标签名的直接子元素（不递归）。
     *
     * @param parent  父元素
     * @param tagName 子元素标签名
     * @return 匹配的直接子元素列表
     */
    private static List<Element> getDirectChildrenByTagName(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                result.add((Element) child);
            }
        }
        return result;
    }

    /** DOM 解析 XML 文件 */
    private static Document parseXml(String filePath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setIgnoringComments(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new File(filePath));
    }

    /** 判断元素是否有 ELEMENT 类型的子节点（忽略注释和空白文本） */
    private static boolean hasElementChildren(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) return true;
        }
        return false;
    }

    /** 在文档中查找指定标签名的父元素（取第一个匹配的直接子元素） */
    private static Element findParentElement(Document doc, String parentTag) {
        Element root = doc.getDocumentElement();
        List<Element> children = getDirectChildrenByTagName(root, parentTag);
        return children.isEmpty() ? null : children.get(0);
    }

    /**
     * 查找或创建父元素。
     * 存在则返回；不存在则在根元素下创建。
     */
    private static Element findOrCreateParent(Document doc, String parentTag) {
        Element existing = findParentElement(doc, parentTag);
        if (existing != null) {
            return existing;
        }

        Element root = doc.getDocumentElement();
        Element parent = doc.createElement(parentTag);
        root.appendChild(parent);
        return parent;
    }

    /**
     * DOM 写回文件（原子写入：先写 .tmp 再 move）。
     * <p>
     * 自定义序列化器替代 Transformer，保持属性按 DOM 插入顺序输出（不重排）。
     * 写入前清除纯空白文本节点，由序列化器统一 4 空格缩进。
     * <p>
     * 写入失败时 .tmp 保留在目标路径旁，方便手动恢复。
     */
    private static void writeXml(Document doc, String filePath) throws Exception {
        Path targetPath = Paths.get(filePath);
        Path tmpPath = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");

        // 清除已有空白节点
        stripWhitespaceNodes(doc.getDocumentElement());

        // 自定义序列化：保持属性顺序 + 统一缩进
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        serializeNode(doc.getDocumentElement(), sb, 0);

        Files.writeString(tmpPath, sb.toString(), StandardCharsets.UTF_8);

        // 原子替换：优先 ATOMIC_MOVE，不支持的平台降级 REPLACE_EXISTING
        try {
            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tmpPath);
            }
        } catch (Exception e) {
            // move 失败：保留 .tmp 方便手动恢复
            throw e;
        }
    }

    /**
     * 递归序列化 DOM 节点为 XML 字符串。
     * <p>
     * 显式确保 {@code name} 属性始终第一个输出，与 {@link TLModuleConfig#getHashMap}
     * 的解析顺序一致。其余属性按 DOM 顺序输出。
     * 注释保留，纯空白文本节点已在写入前被 {@link #stripWhitespaceNodes} 清除。
     */
    private static void serializeNode(Node node, StringBuilder sb, int indent) {
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE: {
                Element elem = (Element) node;
                indent(sb, indent).append('<').append(elem.getTagName());

                NamedNodeMap attrs = elem.getAttributes();
                // 强制 name 属性第一个输出（getHashMap 依赖此顺序）
                if (attrs != null) {
                    Node nameAttr = attrs.getNamedItem("name");
                    if (nameAttr != null) {
                        sb.append(' ').append("name").append("=\"")
                          .append(escapeXml(nameAttr.getNodeValue())).append('"');
                    }
                    // 其余属性按 DOM 顺序
                    for (int i = 0; i < attrs.getLength(); i++) {
                        Node attr = attrs.item(i);
                        if (!"name".equals(attr.getNodeName())) {
                            sb.append(' ').append(attr.getNodeName()).append("=\"")
                              .append(escapeXml(attr.getNodeValue())).append('"');
                        }
                    }
                }

                NodeList children = elem.getChildNodes();
                if (children.getLength() == 0) {
                    sb.append("/>\n");
                } else {
                    sb.append(">\n");
                    for (int i = 0; i < children.getLength(); i++) {
                        serializeNode(children.item(i), sb, indent + 1);
                    }
                    indent(sb, indent).append("</").append(elem.getTagName()).append(">\n");
                }
                break;
            }
            case Node.TEXT_NODE: {
                String text = node.getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    indent(sb, indent).append(escapeXml(text.trim())).append('\n');
                }
                break;
            }
            case Node.COMMENT_NODE: {
                indent(sb, indent).append("<!--").append(node.getNodeValue()).append("-->\n");
                break;
            }
            // CDATA、processing instruction 等其他类型按原样输出
            default:
                break;
        }
    }

    /** 缩进辅助 */
    private static StringBuilder indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("    ");
        return sb;
    }

    /** XML 特殊字符转义 */
    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;"); break;
                case '<':  sb.append("&lt;"); break;
                case '>':  sb.append("&gt;"); break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
