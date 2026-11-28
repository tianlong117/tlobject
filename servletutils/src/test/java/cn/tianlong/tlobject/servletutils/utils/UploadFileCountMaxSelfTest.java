package cn.tianlong.tlobject.servletutils.utils;

import cn.tianlong.tlobject.base.TLMsg;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItem;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_P_FILECOUNTMAX;
import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_R_ERROR;
import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_R_UPLOADITEMS;
import static cn.tianlong.tlobject.servletutils.TLParamString.UPLOADFILE_SAVEFILE;

/**
 * uploadFile 单次上传文件数上限自测（runnable main，无 JUnit——仓库惯例）：
 * ① resolveFileCountMax / countFileItems 纯函数行为；
 * ② saveFile 在写盘前整批拒绝超限批次（超限返回早于请求上下文访问，无请求也能验证）。
 * 失败退出码 1。
 */
public class UploadFileCountMaxSelfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        // ---------- ① 参数解析 ----------
        check("请求参数 Number 优先", uploadFile.resolveFileCountMax(50, 10) == 10);
        check("请求参数字符串优先", uploadFile.resolveFileCountMax(50, "7") == 7);
        check("非法参数回落模块配置", uploadFile.resolveFileCountMax(50, "abc") == 50);
        check("未传参数用模块配置", uploadFile.resolveFileCountMax(50, null) == 50);
        check("0 表示不限", uploadFile.resolveFileCountMax(0, null) == 0);
        check("负数表示不限", uploadFile.resolveFileCountMax(-1, null) == -1);

        // ---------- ② 文件项计数（表单字段不计） ----------
        check("3 文件 + 3 表单字段 → 3", uploadFile.countFileItems(items(3, 3)) == 3);
        check("空列表 → 0", uploadFile.countFileItems(new ArrayList<>()) == 0);
        check("null → 0", uploadFile.countFileItems(null) == 0);

        // ---------- ③ saveFile 写盘前整批拒绝 ----------
        // 超限时应在访问请求上下文之前返回；若检查位置不对，这里会因无请求抛错而不是返回错误
        uploadFile module = new uploadFile("uploadFileCountTest");
        Method save = uploadFile.class.getDeclaredMethod("saveFile", Object.class, TLMsg.class);
        save.setAccessible(true);
        TLMsg msg = module.createMsg().setAction(UPLOADFILE_SAVEFILE)
                .setParam(UPLOADFILE_R_UPLOADITEMS, items(3, 1))   // 3 个文件 + 1 个表单字段
                .setParam(UPLOADFILE_P_FILECOUNTMAX, 2);
        TLMsg r = (TLMsg) save.invoke(module, null, msg);
        check("3 文件超上限 2：返回 ERROR", r != null && r.parseBoolean(UPLOADFILE_R_ERROR, false));
        check("错误携带上限值 2",
                r != null && String.valueOf(r.getParam(UPLOADFILE_P_FILECOUNTMAX)).equals("2"));

        // 表单字段多但文件不超限：不应命中文件数上限（会继续走到请求上下文检查）
        check("表单字段不触发超限（3 字段 + 2 文件，上限 2）",
                !overLimit(items(2, 3), 2, save, module));

        System.out.println("UploadFileCountMaxSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** 调用 saveFile，判断结果是否为「文件数超限」。无请求上下文时 saveFile 会先于上限返回错误或
     *  在越过上限检查后因缺请求上下文抛错——两种都不算超限。 */
    private static boolean overLimit(List<FileItem> items, int max, Method save, uploadFile module) {
        TLMsg msg = module.createMsg().setAction(UPLOADFILE_SAVEFILE)
                .setParam(UPLOADFILE_R_UPLOADITEMS, items)
                .setParam(UPLOADFILE_P_FILECOUNTMAX, max);
        try {
            TLMsg r = (TLMsg) save.invoke(module, null, msg);
            return r != null && r.containsParam(UPLOADFILE_P_FILECOUNTMAX);
        } catch (Exception e) {
            return false;   // 已越过上限检查（缺请求上下文等后续失败）
        }
    }

    private static List<FileItem> items(int fileCount, int formCount) {
        List<FileItem> list = new ArrayList<>();
        for (int i = 0; i < fileCount; i++)
            list.add(new DiskFileItem("file_" + i, "text/plain", false, "f" + i + ".txt", 1024, null));
        for (int i = 0; i < formCount; i++)
            list.add(new DiskFileItem("k" + i, null, true, null, 1024, null));
        return list;
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("[PASS] " + name); }
        else { failed++; System.out.println("[FAIL] " + name); }
    }
}
