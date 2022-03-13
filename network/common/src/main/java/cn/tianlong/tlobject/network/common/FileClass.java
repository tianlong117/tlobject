package cn.tianlong.tlobject.network.common;



import java.io.*;
import java.util.HashMap;


import static cn.tianlong.tlobject.base.TLParamString.WEBSOCKET_P_SENDFILENAME;
import static cn.tianlong.tlobject.base.TLParamString.WEBSOCKET_R_SENDREALFILENAME;


public class FileClass {

    private  String fileName ;
    private  String saveFileName ;
    private  OutputStream fileOutputStream ;
    private  BufferedOutputStream bufferedOutputStream ;
    private  PipedInputStream  pipedInputStream ;
    private  OutputStream pipedOutputStream ;
    private  boolean ifEnd =false ;


    public FileClass(String fileName, int sessionId, String filePath)  {
        this.fileName =fileName ;
        saveFileName = filePath + File.separator + sessionId + "__" + fileName;
    }
    public HashMap<String,Object> getFileParam(){
        HashMap<String,Object> fileParams = new HashMap<>();
        fileParams.put(WEBSOCKET_R_SENDREALFILENAME,fileName);
        fileParams.put(WEBSOCKET_P_SENDFILENAME, saveFileName);
        return  fileParams ;
    }
    protected  boolean isIfEnd(){
        return  this.ifEnd ;
    }
    public String getFileName(){
         return fileName ;
    }
    public InputStream getInputStream(){
           return  pipedInputStream ;
    }
    public  Boolean  createFile(){
        if(fileOutputStream !=null)
            return  false ;
        File file = new File(saveFileName);
        try {
            fileOutputStream = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            return false;
        }
        //创建的一个写出的缓冲流
        bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
        return true;
    }
    public boolean createInputStream() {
        pipedInputStream = new PipedInputStream();
        pipedOutputStream=null;
        try {
            pipedOutputStream = new PipedOutputStream(pipedInputStream);
        } catch (IOException e) {
            return false ;
        }
        //创建的一个写出的缓冲流
        bufferedOutputStream = new BufferedOutputStream(pipedOutputStream);
        return true;
    }
    public boolean saveFileData( byte[] bytes) {
        if(bufferedOutputStream ==null )
            return false ;
        try {
            bufferedOutputStream.write(bytes, 0, bytes.length);//再从buffer中取出来保存到本地
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
    private boolean closeStream(){
        if(bufferedOutputStream !=null)
        {
            try {
                bufferedOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
                return false ;
            }
        }
        if(fileOutputStream !=null)
        {
            try {
                fileOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
                return false ;
            }
        }
        return true ;
    }
    public void  errorOnReceive(){
        if(closeStream()==false)
            return ;
        File file =new File(saveFileName);
        if(file.exists())
            file.delete() ;
    }
    public boolean   receiverOver(){
        if(closeStream()==false)
            return false ;
        this.ifEnd=true ;
        return true ;
    }

}
