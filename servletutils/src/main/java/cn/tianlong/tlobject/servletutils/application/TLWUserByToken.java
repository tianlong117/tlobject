package cn.tianlong.tlobject.servletutils.application;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.network.common.TLJWT;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import com.google.gson.internal.LinkedTreeMap;
import org.apache.commons.lang3.time.DateUtils;

import javax.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLWUserByToken extends TLWAbstractUser {
    protected String tokenSecret ="tlwuserbytoken20190719";
    protected String tokenIssure ="cn.tianlong";
    protected int tokenExpireMinute =30;
    public TLWUserByToken() {
        super();
    }

    public TLWUserByToken(String name) {
        super(name);
    }

    public TLWUserByToken(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if(params!=null  ){
            if( params.get("tokenSecret")!=null)
                tokenSecret=params.get("tokenSecret");
            if( params.get("tokenIssure")!=null)
                tokenIssure=params.get("tokenIssure");
            if( params.get("tokenExpireMinute")!=null)
                tokenExpireMinute=Integer.parseInt(params.get("tokenExpireMinute"));
        }
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg  checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case TOKENUSER_LOGINBEFOREGSONMAP:
                returnMsg = loginBeforeGsonMap(fromWho, msg);
                break;
            case TOKENUSER_GETEXPTIME:
                returnMsg = getExpTime(fromWho, msg);
                break;
            case TOKENUSER_SETEXPTIME:
                setExptime(fromWho, msg);
                break;
            case TOKENUSER_REFLASHTOKEN:
                returnMsg = reflashToken(fromWho, msg);
                break;
            case TOKENUSER_GETTOKEN:
                returnMsg = getToken(fromWho, msg);
                break;
            default:
                returnMsg =super.checkMsgAction(fromWho, msg);
        }
        return returnMsg;
    }

    private void setExptime(Object fromWho, TLMsg msg) {
        String expTime = (String) msg.getParam(TOKENUSER_P_EXPTIME);
        HashMap<String ,Object> datas =getSessionData();
        datas.put(TOKENUSER_P_EXPTIME,expTime);
    }

    @Override
    protected void auToLogin(Object fromWho, TLMsg msg) {

    }

    protected TLMsg loginBeforeGsonMap(Object fromWho, TLMsg msg) {
        TLMsg doWithmsg = (TLMsg) msg.getParam(DOWITHMAG);
        TLMsg dmsg = (TLMsg) doWithmsg.getParam("domsg");
        return login(fromWho, dmsg);
    }

    protected TLMsg getExpTime(Object fromWho, TLMsg msg) {
        HashMap<String ,Object> datas =getSessionData();
        String userid =(String) datas.get(USER_P_USERID);
        String userRole =(String) datas.get(USER_P_ROLE);
        String expTime = (String) datas.get(TOKENUSER_P_EXPTIME);
        return createMsg().setParam(USER_P_USERID, userid)
                .setParam(TOKENUSER_P_EXPTIME, expTime)
                .setParam(USER_P_ROLE, userRole);
    }

    @Override
    protected TLMsg getLoginState(Object fromWho, TLMsg msg) {
        HashMap<String ,Object> datas =getSessionData();
        if(datas ==null)
            return null ;
        String userid =(String) datas.get(USER_P_USERID);
        ArrayList userRole = (ArrayList) datas.get(USER_P_ROLE);
        String username =(String) datas.get(USER_P_USERNAME);
        String token =(String) datas.get(TOKENUSER_R_TOKEN);
        Boolean tokenExpire = (Boolean) datas.get("tokenExpire");
        TLMsg returnMsg = createMsg().setParam(USER_P_USERID, userid).setParam(USER_P_USERNAME, username)
                .setParam(USER_P_ROLE, userRole).setParam("tokenExpire",tokenExpire).setParam(TOKENUSER_R_TOKEN,token);
        String userGroup= (String) datas.get(USER_P_GROUP);
        if( userGroup !=null)
            returnMsg.setParam(USER_P_GROUP,userGroup) ;
        return returnMsg ;
    }

    @Override
    protected void logOut(Object fromWho, TLMsg msg) {

    }

    @Override
    protected TLMsg login(Object fromWho, TLMsg msg) {

        boolean tokenExpire=false;
        String token = (String) msg.getParam(TOKENUSER_P_TOKEN);
        if (token == null )
        {
            String threadName=Thread.currentThread().getName();
            Map<String,HttpServletRequest> requestMap = (Map<String, HttpServletRequest>) getModuleInFactory("servletRequest");
            HttpServletRequest request = requestMap.get(threadName);
            token = request.getHeader("Access-User-Token");
        }
        if (token == null )
        {
            TLMsg domsg = (TLMsg) msg.getParam(DOWITHMAG);
            if(domsg ==null)
                return null ;
            token = (String) domsg.getParam(TOKENUSER_P_TOKEN);
        }
        if (token == null )
            return null ;
        HashMap<String, String> claims = TLJWT.parserToken(tokenSecret, token, tokenIssure);
        if (claims == null)
            return null;
        if(claims.get("decode")!=null)       //解码错误
            return null;
        if(claims.get("expire")!=null) //过期
            tokenExpire= true;
        String userid = claims.get(USER_P_USERID);
        if (!checkUserValid(userid))
            return null;
        HashMap<String ,Object> userInfo =new HashMap<>();
        String userRole = claims.get(USER_P_ROLE);
        String expTime = claims.get("exp");
        String username = claims.get(USER_P_USERNAME);
        String userGroup= claims.get(USER_P_GROUP);
        if(userGroup !=null)
            userInfo.put(USER_P_GROUP,userGroup);
        userInfo.put(TOKENUSER_P_TOKEN,token);
        userInfo.put(USER_P_USERNAME,username);
        userInfo.put(USER_P_USERID,userid);
        userInfo.put(USER_P_ROLE,roleStrToList(userRole));
        userInfo.put(TOKENUSER_P_EXPTIME,expTime);
        userInfo.put("tokenExpire",tokenExpire);
        HashMap<String ,Object> datas =getSessionData();
        datas.putAll(userInfo);
        putLog("user login:"+userid,LogLevel.DEBUG,"login");
        return createMsg().setParam(USER_P_USERID, userid).setParam(USER_P_ROLE, userRole).setParam(USER_P_USERNAME, username);
    }
    protected boolean checkUserValid(String userid) {
        return true ;
    }

    private TLMsg getToken(Object fromWho, TLMsg msg) {
        List<String> roleList = null;
        Object role = msg.getParam(USER_P_ROLE);
        if( role instanceof  String)
        {
            roleList =new ArrayList<>() ;
            roleList.add((String) role);
        }
        else  if( role instanceof List)
            roleList = (List<String>) role;
        String token = createToken((String) msg.getParam(USER_P_USERID),
                roleList,(String) msg.getParam(USER_P_USERNAME),(String) msg.getParam(USER_P_GROUP));
        return createMsg().setParam(TOKENUSER_R_TOKEN, token);
    }

    private String createToken(String userid, List<String> role, String username , String group) {
        String roleStr =TLDataUtils.listToString(role,";");
        HashMap<String, String> claims = new HashMap<>();
        claims.put(USER_P_USERID, userid);
        claims.put(USER_P_USERNAME, username);
        claims.put(USER_P_ROLE, roleStr);
        if(group !=null)
            claims.put(USER_P_GROUP, group);
        return TLJWT.getToken(claims, tokenSecret, tokenExpireMinute, tokenIssure);
    }

    private TLMsg reflashToken(Object fromWho, TLMsg msg) {
        HashMap<String ,Object> datas =getSessionData();
        String expTime = (String) datas.get(TOKENUSER_P_EXPTIME);
        if (expTime == null)
            return createMsg().setParam(TOKENUSER_R_TOKEN, null);
        Date nowExpTime = DateUtils.addMinutes(new Date(), 3); // 即将到期时间少于3分钟则刷新token
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            Date userExpTime = dateFormat.parse(expTime);
            if (nowExpTime.after(userExpTime)) {
                Date newExpTime = DateUtils.addMinutes(new Date(), tokenExpireMinute);
                expTime = dateFormat.format(newExpTime);
                datas.put(TOKENUSER_P_EXPTIME,expTime);
                String userid =(String) datas.get(USER_P_USERID);
                ArrayList<String> userRole = (ArrayList<String>) datas.get(USER_P_ROLE);
                String username =(String) datas.get(USER_P_USERNAME);
                String group =(String) datas.get(USER_P_GROUP);
                return createMsg().setParam(TOKENUSER_R_TOKEN, createToken(userid, userRole,username,group));
            }
        } catch (ParseException e) {
            return createMsg().setParam(TOKENUSER_R_TOKEN, null);
        }
        return createMsg().setParam(TOKENUSER_R_TOKEN, null);
    }
    private HashMap<String ,Object> getSessionData(){
        String threadName=Thread.currentThread().getName();
        Map<String,HashMap<String ,Object>> sessionDatas = (Map<String, HashMap<String, Object>>) getModule("sessionDatas");
        return sessionDatas.get(threadName);
    }
    @Override
    protected TLMsg getData(Object fromWho, TLMsg msg) {
        return null;
    }

    @Override
    protected TLMsg getAllData(Object fromWho, TLMsg msg) {
        return null;
    }

    @Override
    protected TLMsg saveData(Object fromWho, TLMsg msg) {
        return null;
    }

}
