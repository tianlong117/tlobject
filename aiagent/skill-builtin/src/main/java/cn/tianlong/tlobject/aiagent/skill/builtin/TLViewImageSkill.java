package cn.tianlong.tlobject.aiagent.skill.builtin;

import cn.tianlong.tlobject.aiagent.TLBaseSkill;
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
 *       allowedRootPath/{uid}/ 之内，防越权读取。
 *
 * 创建日期：2026/10/5
 * 作者:tianlong
 */
public class TLViewImageSkill extends TLBaseSkill {

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    /** 附件根目录（其下的 {uid}/ 子目录才是可读范围） */
    private String allowedRootPath = "./data";

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

        File f = new File(path);
        if (!f.isAbsolute()) f = f.getAbsoluteFile();
        if (!f.isFile()) return err("Error: file not found: " + path);
        // 技能内兜底：只允许 当前用户 allowedRootPath/{uid}/ 下的文件
        //（agent 侧白名单更严：只放行本会话已登记的附件路径）
        String userId = String.valueOf(msg.getSystemParam(AI_P_USERID, "default"));
        if (!underUserData(f, userId)) {
            putLog("view_image 拒绝 " + allowedRootPath + "/" + userId + " 之外的路径: "
                    + f.getAbsolutePath(), LogLevel.WARN);
            return err("Error: path is outside the allowed directory");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("image_path", f.getAbsolutePath());
        out.put("image_for_model", true);          // 技能表态：这张图给模型看
        out.put("name", f.getName());
        out.put("ok", true);
        return createMsg().setParam(RESULT, true).setParam(AI_P_SKILLOUTPUT, gson.toJson(out));
    }

    private TLMsg err(String text) {
        return createMsg().setParam(RESULT, false).setParam(AI_P_SKILLOUTPUT, text);
    }

    private boolean underUserData(File f, String userId) {
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
