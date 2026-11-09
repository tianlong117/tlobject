package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
import cn.tianlong.tlobject.aiagent.TLUserWorkspace;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

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
            skillDescription = "View an image attachment's actual content. Call with the path shown in the "
                    + "attachment manifest. Only images that were uploaded or produced in this conversation can be viewed.";
        if (parameterSchema == null || parameterSchema.isEmpty()) {
            parameterSchema = new LinkedHashMap<>();
            Map<String, Object> pathProp = new LinkedHashMap<>();
            pathProp.put("type", "string");
            pathProp.put("description", "Image file path (as shown in the [附件] manifest)");
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
        if (resolved == null) return err("Error: file not found: " + path);
        File f = resolved.toFile();
        if (!underUserData(f, userId)) {
            putLog("view_image 拒绝用户 " + userId + " 的 workspace/data 之外的路径: "
                    + f.getAbsolutePath(), LogLevel.WARN);
            return err("Error: path is outside the allowed directory");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("image_path", path);
        out.put("image_for_model", true);          // 技能表态：这张图给模型看
        out.put("name", f.getName());
        out.put("ok", true);
        return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, gson.toJson(out));
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
