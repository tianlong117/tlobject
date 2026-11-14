package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.aiagent.TLUserWorkspace;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * view_image：把已落盘的图片"给模型看"。
 * 技能只返回引用（image_path）+ 表态（image_for_model），由 agent 侧注入为独立 user 消息；
 * 与工具截图回灌走的是同一条机制。
 *
 * 安全：agent 侧会用当前上下文的附件路径白名单校验（更严）；技能内再兜底限定在
 *       本用户 workspace/data 之内（显式配置 allowedRootPath 时再附加该旧根），防越权读取。
 *
 * 创建日期：2026/10/5
 * 作者:tianlong
 */
public class TLViewImageSkill extends TLBaseSkill {

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    /** 附加允许根（null = 仅本用户 workspace/data，由 TLUserWorkspace 判定） */
    private String allowedRootPath = null;

    public TLViewImageSkill() { super(); }
    public TLViewImageSkill(String name) { super(name); }
    public TLViewImageSkill(String name, TLObjectFactory modulefactory) { super(name, modulefactory); }

    @Override
    protected void setModuleParams() {
        super.setModuleParams();
        if (params != null && params.get("allowedRootPath") != null)
            allowedRootPath = params.get("allowedRootPath");
        if (skillName == null || skillName.isEmpty() || skillName.equals(name))
            skillName = "view_image";
        if (skillDescription == null || skillDescription.isEmpty() || skillDescription.endsWith(" skill"))
            skillDescription = "View an image file in the user's workspace: pass an absolute or workspace-relative "
                    + "path (e.g. uploads/a.png), or just the file name when it is unique. Works for images uploaded "
                    + "in chat or via the management page, and images produced in this conversation.";
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> pathProp = new LinkedHashMap<>();
            pathProp.put("type", "string");
            pathProp.put("description", "Image path: workspace-relative (uploads/a.png), absolute, or a unique file name");
            pathProp.put("required", true);
            parameterSchema.put("path", pathProp);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TLMsg execute(Object fromWho, TLMsg msg) {
        Map<String, Object> input =
                (Map<String, Object>) msg.getMapParam(AI_P_SKILLINPUT, new LinkedHashMap<>());
        String path = input.get("path") == null ? "" : String.valueOf(input.get("path"));
        if (path.isEmpty()) return err("Error: path is required");

        String userId = TLUserWorkspace.userId(msg);
        // 旧 data 绝对路径在迁移后按文件名回退到 workspace；仍保留调用方路径供附件白名单比对
        java.nio.file.Path resolved = TLUserWorkspace.resolveExistingUpload(userId, path);
        String imagePath = path;
        if (resolved == null) {
            // 管理页上传的文件可能在 workspace 子目录里，用户/模型只给文件名——唯一同名才采用
            List<java.nio.file.Path> hits = findByBaseName(userId, path);
            if (hits.isEmpty()) return err("Error: file not found: " + path);
            if (hits.size() > 1)
                return err("Error: multiple files named '" + path + "', pass a workspace-relative path: "
                        + relativeNames(userId, hits));
            resolved = hits.get(0);
            imagePath = relativeName(userId, resolved);   // 模型下一轮/附件白名单都用这个相对路径
        }
        File f = resolved.toFile();
        if (!underUserData(f, userId)) {
            putLog("view_image 拒绝用户 " + userId + " 的 workspace/data 之外的路径: "
                    + f.getAbsolutePath(), LogLevel.WARN);
            return err("Error: path is outside the allowed directory");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("image_path", imagePath);
        out.put("image_for_model", true);          // 技能表态：这张图给模型看
        out.put("name", f.getName());
        out.put("ok", true);
        return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, gson.toJson(out));
    }

    /**
     * 裸文件名兜底：在 workspace 内按文件名查找图片（带路径分隔符不查）。
     * 跳过 symlink；最多扫描 20000 个条目（超出即停，避免大 workspace 拖慢）。
     */
    private List<Path> findByBaseName(String userId, String path) {
        List<Path> hits = new ArrayList<>();
        String name = path == null ? "" : path.trim();
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return hits;
        Path root = TLUserWorkspace.userRoot(userId);
        if (!Files.isDirectory(root)) return hits;
        try (Stream<Path> s = Files.walk(root, 12)) {
            s.limit(20000).forEach(p -> {
                try {
                    if (Files.isSymbolicLink(p) || !Files.isRegularFile(p)) return;
                    Path fn = p.getFileName();
                    if (fn != null && isImageName(fn.toString())
                            && name.equalsIgnoreCase(fn.toString())) hits.add(p);
                } catch (Exception ignored) {
                    // 扫描期间被删/无权限：跳过
                }
            });
        } catch (Exception ignored) {
            // 遍历失败：按未找到处理
        }
        return hits;
    }

    private static boolean isImageName(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp");
    }

    /** workspace 相对路径（统一 /，供模型与附件白名单使用）。 */
    private static String relativeName(String userId, Path p) {
        try {
            return TLUserWorkspace.userRoot(userId).toAbsolutePath().normalize()
                    .relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.toString();
        }
    }

    private static String relativeNames(String userId, List<Path> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size() && i < 5; i++) {
            if (i > 0) sb.append(", ");
            sb.append(relativeName(userId, hits.get(i)));
        }
        if (hits.size() > 5) sb.append(", …");
        return sb.toString();
    }

    private TLMsg err(String text) {
        return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT, text);
    }

    private boolean underUserData(File f, String userId) {
        // 主判据：本用户 workspace 或 data（与执行器路径守卫同口径）
        if (TLUserWorkspace.isTrusted(f.toPath().toAbsolutePath().normalize(), userId)) return true;
        // 显式配置的 allowedRootPath 作为附加旧根
        if (allowedRootPath == null || allowedRootPath.isEmpty()) return false;
        try {
            File base = new File(allowedRootPath).getCanonicalFile();
            File root = new File(base, userId).getCanonicalFile();
            // 防御：userId 为 "." / ".." 时 root 会塌陷成 base 或上跳——
            // 必须严格落在 base 的下一级子目录内
            if (root.equals(base) || !root.getPath().startsWith(base.getPath() + File.separator)) return false;
            return f.getCanonicalPath().startsWith(root.getPath() + File.separator);
        } catch (Exception e) {
            return false;
        }
    }
}
