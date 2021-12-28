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
        HashMap<String,String> fileNames = new HashMap<>();
        HashMap<String,String> saveFiles = new HashMap<>();
        List<FileItem> items = (List<FileItem>) msg.getParam(UPLOADFILE_R_UPLOADITEMS);
        Object newFileName = msg.getParam(UPLOADFILE_P_NEWFILENAME);
        boolean changeName =msg.parseBoolean(UPLOADFILE_P_NOCHANGENAME,true);
        int i = 0;
        int fileNums = items.size();
        TLMsg returnMsg = createMsg();
        for (FileItem fileItem : items) {
            if (!fileItem.isFormField()) {
                String fieldName = fileItem.getFieldName();
                String fileName = fileItem.getName();// 获取文件名
                String fileType = fileItem.getContentType();// 获取文件类型
                if(changeName){
                    String type = StringUtils.substringAfterLast(fileName,  ".");
                    if (newFileName != null) {
                        if (newFileName instanceof String) {
                            if (fileNums == 1)
                                fileName = newFileName + "." + type;
                            else
                                fileName = newFileName + "_" + i + "." + type;
                        } else if (newFileName instanceof ArrayList)
                            fileName = ((ArrayList) newFileName).get(i) + "." + type;
                    }
                    else
                    {
                        if(fileName.indexOf("/") !=-1)
                            fileName =StringUtils.substringAfterLast(fileName,  "/") ;
                        if(fileName.indexOf("\\") !=-1)
                            fileName =StringUtils.substringAfterLast(fileName,  "\\") ;
                        if(fileName.indexOf(".") !=-1)
                            fileName=StringUtils.substringBeforeLast(fileName,  ".") ;
                        if(type !=null && !type.isEmpty())
                            fileName=fileName+"_"+TLDateUtils.getNowDateStr("yyyyMMddHHmmss") + "." + type;
                        else
                            fileName=fileName+"_"+TLDateUtils.getNowDateStr("yyyyMMddHHmmss") ;
                    }
                }
                String fileTypesStr = (String) msg.getParam(UPLOADFILE_P_FILETYPES);
                HttpServletRequest request = getRequest();
                ServletContext ctx = request.getServletContext();// 获取上下文应用
                String filepath = (String) msg.getParam(UPLOADFILE_P_FILEPATH);
                if (filepath == null)
                    filepath = this.filePath;
                String realPath = ctx.getRealPath(filepath);// 设置存储路径
                if (realPath == null)
                    return returnMsg.setParam(UPLOADFILE_R_ERROR, true).setParam(UPLOADFILE_P_FILEPATH, "error");
                File dir = new File(realPath);
                if (!dir.exists()) {
                    if (!dir.mkdirs())
                        return returnMsg.setParam(UPLOADFILE_R_ERROR, true).setParam(UPLOADFILE_P_FILEPATH, "error");
                }
                if (fileTypesStr != null && !fileTypesStr.isEmpty())
                {
                    String[] fileTypes = fileTypesStr.split(FENHAO);
                    if (fileTypes != null && fileTypes.length > 0 && !ifFileTypePermit(fileTypes, fileType))
                        return returnMsg.setParam(UPLOADFILE_R_ERROR, true).setParam(UPLOADFILE_R_FILETYPE, fileType);
                }
                String saveFilename = realPath + File.separator + fileName;
                File file = new File(saveFilename);// 创建文件实例
                try {
                    fileItem.write(file);// 写入数据到文件
                    fileNames.put(fieldName,fileName);
                    saveFiles.put(fieldName,saveFilename);
                    i++;
                } catch (Exception e) {
                    return returnMsg.setParam(UPLOADFILE_R_ERROR, true).setParam(UPLOADFILE_R_FILENAME, fileName);
                }
            }
        }
        return returnMsg.setParam(UPLOADFILE_R_FILENAMES, fileNames).setParam(UPLOADFILE_R_SAVEFILES, saveFiles);
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

}
