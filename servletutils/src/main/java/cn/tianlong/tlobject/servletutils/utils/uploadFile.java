package cn.tianlong.tlobject.servletutils.utils;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDateUtils;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class uploadFile extends TLWServModule {
    protected String fileTypes[];
    protected String filePath = "/uploads";
    protected int fileSizeMax = 0;
    protected String charset = UTF8;

    public uploadFile() {
        super();
    }

    public uploadFile(String name) {
        super(name);
    }

    public uploadFile(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params == null || params.isEmpty())
            return;
        if (params.get(CHARSET) != null && !params.get(CHARSET).isEmpty())
            charset = params.get(CHARSET);
        if (params.get(UPLOADFILE_P_FILETYPES) != null && !params.get(UPLOADFILE_P_FILETYPES).isEmpty())
            fileTypes = params.get(UPLOADFILE_P_FILETYPES).split(FENHAO);
        if (params.get(UPLOADFILE_P_FILEPATH) != null && !params.get(UPLOADFILE_P_FILEPATH).isEmpty())
            filePath = params.get(UPLOADFILE_P_FILEPATH);
        if (params.get(UPLOADFILE_R_FILESIZEMAX) != null && !params.get(UPLOADFILE_R_FILESIZEMAX).isEmpty())
            fileSizeMax = Integer.parseInt(params.get(UPLOADFILE_R_FILESIZEMAX)) * 1024 * 1024;

    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case UPLOADFILE_UPLOAD:
                returnMsg = upload(fromWho, msg);
                break;
            case UPLOADFILE_SAVEFILE:
                returnMsg = saveFile(fromWho, msg);
                break;
            case UPLOADFILE_PARSEREQUEST:
                returnMsg = parseRequest(fromWho, msg);
                break;
            default:

        }
        return returnMsg;
    }

    private TLMsg parseRequest(Object fromWho, TLMsg msg) {
        HttpServletRequest request = getRequest();
        try {
            request.setCharacterEncoding(charset);// 设置获取字体
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        ServletContext ctx = request.getServletContext();// 获取上下文应用
        DiskFileItemFactory factory = new DiskFileItemFactory();// 自动导入类 使用工具 commons-fileupload
        File repository = (File) ctx.getAttribute("javax.servlet.context.tempdir");// 获取临时文件的存储路径
        factory.setRepository(repository);// 设置工程对象的仓库
        ServletFileUpload handler = new ServletFileUpload(factory);// 实例化 servletFileupload 上传
        int upfileSizeMax;
        if (msg.getParam(UPLOADFILE_P_FILESIZE) != null)
            upfileSizeMax = (int) msg.getParam(UPLOADFILE_P_FILESIZE);
        else
            upfileSizeMax = fileSizeMax;
        if (upfileSizeMax > 0)
            handler.setFileSizeMax(upfileSizeMax);
        List<FileItem> items = null;
        try {
            items = handler.parseRequest(request);
        } catch (FileUploadBase.FileSizeLimitExceededException e) {
            return createMsg().setParam(UPLOADFILE_R_ERROR, true).setParam(UPLOADFILE_R_FILESIZEMAX, upfileSizeMax);
        } catch (FileUploadException e) {
            e.printStackTrace();
        }
        if (items == null)
            return createMsg().setParam(UPLOADFILE_R_ERROR, true).setParam(UPLOADFILE_R_FILENAME, null);
        TLMsg returnMsg = createMsg();
        HashMap<String, String> datas = new HashMap<>();
        for (FileItem fileItem : items) {
            if (fileItem.isFormField()) {
                String fieldName = fileItem.getFieldName();
                String value = "";
                try {
                    value = fileItem.getString(charset);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                datas.put(fieldName, value);
            }
        }
        return returnMsg.setParam(UPLOADFILE_R_UPLOADITEMS, items).setParam(UPLOADFILE_R_UPLOADPARAMS, datas);
    }

    private TLMsg saveFile(Object fromWho, TLMsg msg) {
        HashMap<String, String> fileNames = new HashMap<>();
        HashMap<String, String> saveFiles = new HashMap<>();
        List<FileItem> items = (List<FileItem>) msg.getParam(UPLOADFILE_R_UPLOADITEMS);
        Object newFileName = msg.getParam(UPLOADFILE_P_NEWFILENAME);
        boolean changeName = msg.parseBoolean(UPLOADFILE_P_NOCHANGENAME, true);
        int i = 0;
        int fileNums = items.size();
        TLMsg returnMsg = createMsg();

        // 获取目标目录的规范路径（用于后续验证）
        String filepath = (String) msg.getParam(UPLOADFILE_P_FILEPATH);
        if (filepath == null) {
            filepath = this.filePath;
        }
        HttpServletRequest request = getRequest();
        ServletContext ctx = request.getServletContext();
        String realPath = ctx.getRealPath(filepath);
        if (realPath == null) {
            // Jetty 内嵌 ServletContextHandler 无 resourceBase → getRealPath 返回 null：
            // 绝对路径直接用；相对路径相对启动目录（user.dir）解析
            File fp = new File(filepath);
            realPath = fp.isAbsolute() ? fp.getPath()
                    : new File(System.getProperty("user.dir"), filepath).getPath();
        }
        File targetDir = new File(realPath);
        if (!targetDir.exists()) {
            if (!targetDir.mkdirs()) {
                return returnMsg.setParam(UPLOADFILE_R_ERROR, true)
                        .setParam(UPLOADFILE_P_FILEPATH, "mkdir failed");
            }
        }
        // 获取目标目录的规范路径（用于验证）
        String targetCanonicalPath;
        try {
            targetCanonicalPath = targetDir.getCanonicalPath();
        } catch (IOException e) {
            return returnMsg.setParam(UPLOADFILE_R_ERROR, true)
                    .setParam(UPLOADFILE_P_FILEPATH, "canonical path error");
        }

        for (FileItem fileItem : items) {
            if (!fileItem.isFormField()) {
                String fieldName = fileItem.getFieldName();
                String originalFileName = fileItem.getName();
                String fileName = sanitizeFileName(originalFileName);
                // 如果净化后文件名为空，使用默认名
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "unnamed_" + System.currentTimeMillis();
                }
                String fileType = fileItem.getContentType();

                // --- 文件名生成逻辑（保持不变） ---
                if (changeName) {
                    String type = StringUtils.substringAfterLast(fileName, ".");
                    if (newFileName != null) {
                        if (newFileName instanceof String) {
                            if (fileNums == 1) {
                                fileName = newFileName + "." + type;
                            } else {
                                fileName = newFileName + "_" + i + "." + type;
                            }
                        } else if (newFileName instanceof ArrayList) {
                            fileName = ((ArrayList) newFileName).get(i) + "." + type;
                        }
                    } else {
                        if (fileName.indexOf("/") != -1) {
                            fileName = StringUtils.substringAfterLast(fileName, "/");
                        }
                        if (fileName.indexOf("\\") != -1) {
                            fileName = StringUtils.substringAfterLast(fileName, "\\");
                        }
                        if (fileName.indexOf(".") != -1) {
                            fileName = StringUtils.substringBeforeLast(fileName, ".");
                        }
                        if (type != null && !type.isEmpty()) {
                            fileName = fileName + "_" + TLDateUtils.getNowDateStr("yyyyMMddHHmmss") + "." + type;
                        } else {
                            fileName = fileName + "_" + TLDateUtils.getNowDateStr("yyyyMMddHHmmss");
                        }
                    }
                }
                // 最终文件名只保留文件名部分（无路径）
                fileName = new File(fileName).getName(); // 再次净化，确保无路径分隔符

                // --- 文件类型检查（保持不变） ---
                String fileTypesStr = (String) msg.getParam(UPLOADFILE_P_FILETYPES);
                if (fileTypesStr != null && !fileTypesStr.isEmpty()) {
                    String[] fileTypes = fileTypesStr.split(FENHAO);
                    if (fileTypes.length > 0 && !ifFileTypePermit(fileTypes, fileType)) {
                        return returnMsg.setParam(UPLOADFILE_R_ERROR, true)
                                .setParam(UPLOADFILE_R_FILETYPE, fileType);
                    }
                }
                // 基于文件名后缀二次校验（保持不变）
                if (fileName != null && isDangerousExtension(fileName)) {
                    return returnMsg.setParam(UPLOADFILE_R_ERROR, true)
                            .setParam(UPLOADFILE_R_FILETYPE, "forbidden");
                }

                // --- 路径验证与保存 ---
                String saveFilename = realPath + File.separator + fileName;
                File file = new File(saveFilename);
                try {
                    // 验证最终路径是否在目标目录内
                    String fileCanonicalPath = file.getCanonicalPath();
                    if (!fileCanonicalPath.startsWith(targetCanonicalPath + File.separator)) {
                        // 如果不在目标目录内，拒绝保存
                        return returnMsg.setParam(UPLOADFILE_R_ERROR, true)
                                .setParam(UPLOADFILE_R_FILENAME, fileName)
                                .setParam("error", "Invalid file path");
                    }
                    // 写入文件
                    fileItem.write(file);
                    fileNames.put(fieldName, fileName);
                    saveFiles.put(fieldName, saveFilename);
                    i++;
                } catch (Exception e) {
                    return returnMsg.setParam(UPLOADFILE_R_ERROR, true)
                            .setParam(UPLOADFILE_R_FILENAME, fileName);
                }
            }
        }
        return returnMsg.setParam(UPLOADFILE_R_FILENAMES, fileNames)
                .setParam(UPLOADFILE_R_SAVEFILES, saveFiles);
    }
    private TLMsg upload(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = parseRequest(fromWho, msg);
        if (returnMsg.getParam(UPLOADFILE_R_ERROR) != null)
            return returnMsg;
        List<FileItem> items = (List<FileItem>) returnMsg.getParam(UPLOADFILE_R_UPLOADITEMS);
        msg.setParam(UPLOADFILE_R_UPLOADITEMS, items);
        TLMsg sreturnMsg = saveFile(fromWho, msg);
        return sreturnMsg.setParam(UPLOADFILE_R_UPLOADPARAMS, returnMsg.getParam(UPLOADFILE_R_UPLOADPARAMS));
    }

    private boolean ifFileTypePermit(String[] fileTypes, String filetyp) {
        for (int i = 0; i < fileTypes.length; i++) {
            if (fileTypes[i].equals(filetyp))
                return true;
        }
        return false;
    }

    private static final String[] DANGEROUS_EXTENSIONS = {
        "exe", "sh", "bat", "cmd", "com", "dll", "so", "js", "jsp", "php", "asp", "aspx", "war", "jar"
    };

    private boolean isDangerousExtension(String fileName) {
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) ext = fileName.substring(dot + 1).toLowerCase();
        for (String dangerous : DANGEROUS_EXTENSIONS) {
            if (ext.equals(dangerous)) return true;
        }
        return false;
    }

    /**
     * 净化文件名，移除路径分隔符等危险字符防止路径遍历攻击
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isEmpty())
            return name;
        // 移除路径分隔符
        String cleanName = name.replace('/', '_').replace('\\', '_');
        // 移除 parent 路径引用
        cleanName = cleanName.replace("..", "_");
        // 只保留文件名部分（如果有冒号分隔的 windows 盘符路径）
        int colonIdx = cleanName.lastIndexOf(':');
        if (colonIdx >= 0)
            cleanName = cleanName.substring(colonIdx + 1);
        return cleanName;
    }

}
