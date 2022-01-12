package cn.tianlong.tlobject.servletutils;

/**
 * 创建日期：2018/4/5 on 8:47
 * 描述:
 * 作者:tianlong
 */

public interface TLParamString {

    final String M_APPCENTER = "appCenter";
    final String CHARSET = "charset";
    final String UTF8 = "UTF-8";
    /**
     * 模块名 *
     */
    final String M_DIRECTININTERFACE = "directInInterface";
    final String M_DIRECTOUTNTERFACE = "directOutInterface";
    final String M_JSONININTERFACE = "jsonInInterface";
    final String M_UPLOADFILE = "uploadFile";
    final String M_WEBUSER = "webUser";
    final String M_TOKENUSER = "tokenUser";
    final String M_VELOCITY = "velocity";
    final String M_WEBCLIENT = "webClient";
    final String M_FILECACHE = "fileCache";
    final String M_AUTH= "webauth";

    final String AUTH_AUTHINURLMAP ="authInUrlMap";
    final String AUTH_AUTHINMODULE ="authInModule" ;
    final String AUTH_SETPOLICY = "setProliy";
    final String AUTH_GETPOLICY = "getProliy";
    final String AUTH_DELETEPOLICY = "deleteProliy";
    final String AUTH_AUTHTAG = "authTag";
    final String AUTH_P_TAG = "tag";
    final String AUTH_P_POLICY = "policy";
    final String AUTH_P_POLICYNAME = "policyName";
    final String AUTH_P_POLICYVALUE= "policyValue";
    final String AUTH_P_DEFAULTSUPERUSER= "defaultSuperUser";
    final String AUTH_P_DEFAULTSUPERROLE= "defaultSuperRole";

    final String ININTERFACE_GETDATAFROMUSER = "getDataFromUser";
    final String ININTERFACE_GETCONTENTFROMUSER = "getContentFromUser";
    final String OUTINTERFACE_PUTDATATOUSER = "putDataToUser";
    final String OUTINTERFACE_PUTCONTENTTOUSER = "putContentToUser";
    final String CLIENT_P_VARTYPE = "varType";
    final String CLIENT_P_VARNAME = "varname";
    final String CLIENT_P_CONTENT = "content";
    final String CLIENT_P_OUDDATA = "outData";
    final String CLIENT_R_OUTCONTENT = "outContent";
    final String CLIENT_R_CONTENT = "content";
    final String CLIENT_R_OUTMSG = "outmsg";
    final String CLIENT_V_JSON = "json";

    final String WEBCLIENT_SETININTERFACE = "setinInterface";
    final String WEBCLIENT_SETOUTINTERFACE = "setOutInterface";
    final String WEBCLIENT_RECOVERYINTERFACE = "recoveryInterface";
    final String WEBCLIENT_RECOVERYOUTINTERFACE = "recoveryOutInterface";
    final String WEBCLIENT_RECOVERYIINTERFACE = "recoveryInInterface";
    final String WEBCLIENT_GETINDATA = "getInData";
    final String WEBCLIENT_GETINJSON = "getInJson";
    final String WEBCLIENT_GETNIMSG = "getInMsg";
    final String WEBCLIENT_GETINCONTENT = "getInContent";
    final String WEBCLIENT_PUTOUTDATA = "putOutData";
    final String WEBCLIENT_PUTCONTENT = "putContent";
    final String WEBCLIENT_PUTUSERMSG = "putUserMsg";
    final String WEBCLIENT_GETOUTCONTENT = "getOutContent";

    final String USER_LOGIN = "login";
    final String USER_AUTOLOGIN = "auToLogin";
    final String USER_GETUSER = "getUser";
    final String USER_LOGOUT = "logOut";
    final String USER_GETLOGINSTATE = "getLoginState";
    final String USER_SAVEDATA = "saveData";
    final String USER_GETDATA = "getData";
    final String USER_REMOVEDATA = "removeData";
    final String USER_GETALLDATA = "getAllData";
    final String USER_GETCLIENT = "getClient";
    final String USER_GETONLINE = "getOnline";
    final String USER_SETOFFLINE = "setOffline";
    final String USER_ONOFFLINE = "onOffline";
    final String TOKENUSER_LOGINBEFOREGSONMAP = "loginBeforeGsonMap";
    final String TOKENUSER_GETEXPTIME = "getExpTime";
    final String TOKENUSER_SETEXPTIME = "setExptime";
    final String TOKENUSER_REFLASHTOKEN = "reflashToken";
    final String TOKENUSER_GETTOKEN = "getToken";

    final String USER_P_USEROBJ = "userObj";
    final String USER_P_USERNAME = "username";
    final String USER_P_USERID = "userid";
    final String USER_P_ROLE = "role";
    final String USER_P_GROUP = "group";
    final String USER_P_DATANAMES = "dataNames";
    final String USER_P_USERDATAS = "datas";
    final String USER_P_TIMEOUT = "timeout";
    final String USER_R_CLIENT = "client";
    final String USER_V_GUEST = "guest";
    final String USER_V_ROLE_GUEST = "guest";

    final String TOKENUSER_P_EXPTIME = "expTime";
    final String TOKENUSER_P_TOKEN = "token";
    final String TOKENUSER_R_TOKEN = "token";


    final String UPLOADFILE_UPLOAD = "upload";
    final String UPLOADFILE_SAVEFILE = "saveFile";
    final String UPLOADFILE_PARSEREQUEST = "parseRequest";
    final String UPLOADFILE_P_FILESIZE = "filesize";
    final String UPLOADFILE_P_FILETYPES = "fileTypes";
    final String UPLOADFILE_P_FILEPATH = "filePath";
    final String UPLOADFILE_P_NEWFILENAME = "newFileName";
    final String UPLOADFILE_P_NOCHANGENAME = "noChangeName";
    final String UPLOADFILE_R_FILENAME = "fileName";
    final String UPLOADFILE_R_FILESIZEMAX = "fileSizeMax";
    final String UPLOADFILE_R_UPLOADITEMS = "items";
    final String UPLOADFILE_R_UPLOADPARAMS = "params";
    final String UPLOADFILE_R_FILETYPE = "fileType";
    final String UPLOADFILE_R_SAVEFILES = "saveFiles";
    final String UPLOADFILE_R_FILENAMES = "filenames";
    final String UPLOADFILE_R_ERROR ="error";
}
