package cn.tianlong.java.application.webmanager;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.dbdata.ListInDB;
import cn.tianlong.tlobject.servletutils.utils.CapchaHelper;
import cn.tianlong.tlobject.utils.RSAUtils;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.apache.commons.codec.digest.DigestUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.tianlong.tlobject.servletutils.TLParamString.AUTH_P_DEFAULTSUPERUSER;

public class managerControl extends adminCommon {

    protected String superUser;
    protected String loginUrl ;
    public managerControl() {
        super();
    }

    public managerControl(String name) {
        super(name);
    }

    public managerControl(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        superUser =  getModuleParam("adminauth",AUTH_P_DEFAULTSUPERUSER);
        return this ;
    }
    @Override
    protected void setModuleParams() {
        loginUrl =params.get("loginUrl");
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "index":
                if(checkIfLogin())
                   index(fromWho,msg);
                break;
            case "captchaImg":
                    captchaImg();
                break;
            case "homePage":
                if(checkIfLogin())
                   homePage(fromWho,msg);
                break;
            case "initInfo":
                if(checkIfLogin())
                   initInfo(fromWho,msg);
                break;
            case "getMenus":
                if(checkIfLogin())
                  getMenus(fromWho,msg);
                break;
            case "regist":
                regist(fromWho, msg);
                break;
            case "login":
                login(fromWho, msg);
                break;
            case "loginInput":
                loginInput(fromWho, msg);
                break;
            case "noAuth":
                noAuth(fromWho, msg);
                break;
            case "changePassword":
                if(checkIfLogin())
                   changePassword(fromWho, msg);
                break;
            case "logOut":
                if(checkIfLogin())
                    logOut(fromWho, msg);
                break;
            case "updateUsername":
                updateUsername(fromWho, msg);
                break;
            case "error":
                error(fromWho, msg);
                break;
            case "errorInput":
                errorInput(fromWho, msg);
                break;
            default:
                putMsg("error", creatOutMsg().setAction("setError").setParam("content", "no action"));
        }
        return returnMsg;
    }


    protected boolean checkIfLogin(){
        String userid=getUserid();
         return (userid==null) ? false :true ;
    }
    private void error(Object fromWho, TLMsg msg) {
        String  message = (String) msg.getParam("msg");
        String content="" ;
        if(message ==null)
            content="错误";
        else if(message.equals("noauth"))
            content="没有权限";
        String client =getClient();
        if(client.equals("jsonClient"))
        {
            putResultToClient( false,"error",content);
            return;
        }
        outData odata =  creatOutDataMsg("error");
        odata.addData("<html><head><title>RequestInfo</TITLE></head>");
        odata.addData("<body>");
        odata.addData(content);
        odata.addData("</body></html>");
        putMsg("velocity", createMsg().setAction("putDataToUser").setParam("outData",odata));

    }

    private void initInfo(Object fromWho, TLMsg msg) {
        HashMap<String,Object> homeiInfo =new HashMap<>() ;
        homeiInfo.put("title","首页");
        homeiInfo.put("href","homepage");
        HashMap<String,Object> logoInfo =new HashMap<>() ;
        logoInfo.put("title","亲亲管理");
        logoInfo.put("href","index");
        logoInfo.put("image", "/static/images/logo.png");
        TLMsg returnMsg =putMsg("menuManagerControl",createMsg().setAction("getAllMenus"));
        List<Map> menuList    = (List<Map>) returnMsg.getParam(RESULT);
        List<Map> nobuttonList = new ArrayList<Map>();
        for (Map map : menuList) {
            if ((int)map.get("type")!=1) {
                nobuttonList.add(map);
            }
        }
        ArrayList menuInfo =new ArrayList( ) ;
        if(menuList !=null)
             menuInfo = (ArrayList) TLDataUtils.toTree(nobuttonList,0L);
        outData odata =  creatOutDataMsg("initInfo");
        odata.addData("homeInfo",homeiInfo) ;
        odata.addData("logoInfo",logoInfo) ;
        odata.addData("menuInfo",menuInfo);
        putOutData(odata);
    }


    private void getMenus(Object fromWho, TLMsg msg) {
        ListInDB listInDB =new ListInDB("managermenus",moduleFactory);
        ArrayList list =listInDB.getList() ;
        HashMap<String,Object> menus =new HashMap<>() ;
        menus.put("code","0");
        menus.put("msg","");
        menus.put("count",String.valueOf(list.size()));
        menus.put("data",list);
        outData odata =  creatOutDataMsg("getMenus");
        odata.addData(menus);
        putOutData(odata);
    }

    private void login(Object fromWho, TLMsg msg) {
        List userRole=getUserRole();
        if( userRole ==null)
           putVar("", "login");
        else
            sendRedirect("index");
        return;
    }

    private void noAuth(Object fromWho, TLMsg msg) {
         List userRole=getUserRole();
        if( userRole !=null)
        {
            String url=params.get("errorUrl")+"?msg=noauth";
            sendRedirect(url);
        }
        else
            sendRedirect(loginUrl);
    }

    protected void index(Object fromWho, TLMsg msg) {
        outData odata =  creatOutDataMsg("index");
        putOutData(odata);
    }
    protected void homePage(Object fromWho, TLMsg msg) {
        outData odata =  creatOutDataMsg("homePage");
        putOutData(odata);
    }
    private void updateUsername(Object fromWho, TLMsg msg) {
        String username = (String) msg.getParam("username");
        TLMsg umsg = createMsg().setAction("updateUsername")
                .setParam("username", username)
                .setParam("userid", getUserid());
        TLMsg returnMsg = putMsg("memberModule", umsg);
        putVar("updateUsername ", null);
    }

    private void changePassword(Object fromWho, TLMsg msg) {
        String encryptedpasswd = (String) msg.getParam("encryptedpasswd");
        String password = decryptPasswd(encryptedpasswd);
        if (password == null) {
            putVar("1002", "密码解密错误");
            return;
        }
        String newEncodedpasswd = (String) msg.getParam("newEncodedpasswd");
        String newPassword = decryptPasswd(newEncodedpasswd);
        if (newPassword == null) {
            putVar("1002", "密码解密错误");
            return;
        }
        String userid = (String) msg.getParam("userid");
        TLMsg usermsg = createMsg().setAction("changePassword")
                .setParam("userid", userid)
                .setParam("password", password)
                .setParam("newPassword", newPassword);
        TLMsg returnMsg = putMsg("memberModule", usermsg);
        if (returnMsg.getParam("status").equals("0"))
            putVar("1003", "密码错误");
        else {
            HashMap<String, Object> result = new HashMap<>();
            result.put("userid", userid);
            putVar("0000", "change password ok ");
        }
    }

    private void logOut(Object fromWho, TLMsg msg) {
        putMsg(getUserObjName(), createMsg().setAction("logOut"));
        sendRedirect(loginUrl);
    }
    protected void  captchaImg() {
        HttpServletRequest req =getRequest();
        HttpServletResponse resp =getResponse() ;
        CapchaHelper.putCaptchaImg(req,resp);
    }
    private void loginInput(Object fromWho, TLMsg msg) {
        outData outData =creatOutDataMsg("loginInput");
        HttpServletRequest req =getRequest();
        String captcha = (String) msg.getParam("captcha");
        Boolean result =   CapchaHelper.checkCaptcha(captcha,req);
        if( result ==false)
        {
            outData.addData("code","1");
            outData.addData("message","验证码错误");
            putOutData(outData);
            return;
        }
        String userid = (String) msg.getParam("userid");
        TLMsg usermsg = createMsg().setAction("findUser").setParam("userid", userid);
        TLMsg returnMsg = putMsg("managerModle", usermsg);
        if(returnMsg==null)
        {
             outData.addData("code","2");
             outData.addData("message","没有该用户");
             putOutData(outData);
             return;
        }
        int status= (int) returnMsg.getParam("status");
        if (status==0)
        {
            outData.addData("code","3");
            outData.addData("message","账号禁止");
            putOutData(outData);
            return;
        }
        String loginpasswd = (String) msg.getParam("password");
        String loginmd5 = DigestUtils.md5Hex(loginpasswd + userid);
        String dbpassword = (String) returnMsg.getParam("password");
        if (!loginmd5.equals(dbpassword))
        {
            outData.addData("code","4");
            outData.addData("message","密码错误");
            putOutData(outData);
            return;
        }
        String username = (String) returnMsg.getParam("username");
        String role = (String) returnMsg.getParam("roleid");
        HttpServletRequest request = (HttpServletRequest)getRequest();
        String userip = request.getRemoteAddr();
        putMsg(getUserObjName(), createMsg().setAction("login")
                .setParam("userName", username).setParam("role", role).
                        setParam("userid", userid));
        TLMsg loginmsg =createMsg().setAction("updateLoginState").setParam("loginip",userip).setParam("userid",userid);
        putMsg("managerModle", loginmsg);
        outData.addData("code","0");
        outData.addData("message","系统登录");
        putOutData(outData);
    //    sendRedirect("index");
    }

    private void regist(Object fromWho, TLMsg msg) {
        String encryptedpasswd = (String) msg.getParam("encryptedpasswd");
        String password = decryptPasswd(encryptedpasswd);
        if (password == null) {
            putVar("1001", "密码解密错误");
            return;
        }
        String userid = (String) msg.getParam("userid");
        String username = (String) msg.getParam("username");
        HttpServletRequest request = (HttpServletRequest) getModuleInFactory("servletRequest");
        String userip = request.getRemoteAddr();
        TLMsg usermsg = createMsg().setAction("userRegist")
                .setParam("userid", userid)
                .setParam("password", password)
                .setParam("registip", userip)
                .setParam("username", username);
        TLMsg returnMsg = putMsg("memberModule", usermsg);
        if (returnMsg.getParam("status") != null)
            putVar("1002", "用户名已存在");
        else {
            HashMap<String, Object> result = new HashMap<>();
            result.put("token", createToken(userid, "member",username));
            result.put("userid", userid);
            result.put("role", "member");
            putVar("0000", "regist ok ");
        }
    }

    private String decryptPasswd(String encryptedpasswd) {
        String password;
        try {
            RSAPrivateKey privatekey = RSAUtils.getPrivateKey(params.get("privateKey"));
            password = RSAUtils.privateDecrypt(encryptedpasswd, privatekey);
        } catch (NoSuchAlgorithmException e) {
            //  e.printStackTrace();
            return null;
        } catch (InvalidKeySpecException e) {
            //   e.printStackTrace();
            return null;
        }
        return password;
    }

    private String createToken(String userid, String role,String username) {
        TLMsg tokenMsg = putMsg(getUserObjName(), createMsg()
                .setAction("getToken")
                .setParam("userid", userid)
                .setParam("username", username)
                .setParam("role", role));
        return (String) tokenMsg.getParam("token");
    }
}
