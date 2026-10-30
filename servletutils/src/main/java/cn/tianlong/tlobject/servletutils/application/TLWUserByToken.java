package cn.tianlong.tlobject.servletutils.application;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.network.common.TLJWT;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
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
        HashMap<String ,Object> datas =getThreadData();
        if (datas == null)          // 非请求线程 / 无上下文
            return;
        datas.put(TOKENUSER_P_EXPTIME,expTime);
    }

    @Override
    protected void auToLogin(Object fromWho, TLMsg msg) {

    }

    protected TLMsg loginBeforeGsonMap(Object fromWho, TLMsg msg) {
        TLMsg doWithmsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
        if (doWithmsg == null) {     // 直接 putMsg 调用（未经 beforeMsgTable 注入）
            putLog("loginBeforeGsonMap 缺少 DOWITHMSG，拒绝登录", LogLevel.WARN, "login");
            return null;
        }
        TLMsg dmsg = (TLMsg) doWithmsg.getParam("domsg");
        return login(fromWho, dmsg);
    }

    protected TLMsg getExpTime(Object fromWho, TLMsg msg) {
        HashMap<String ,Object> datas =getThreadData();
        if (datas == null)
            return null ;
        String userid =(String) datas.get(USER_P_USERID);
        // 角色在 login 里存的是 List（roleStrToList）；原先强转 String 会 CCE，按原类型往下传
        Object userRole = datas.get(USER_P_ROLE);
        String expTime = (String) datas.get(TOKENUSER_P_EXPTIME);
        return createMsg().setParam(USER_P_USERID, userid)
                .setParam(TOKENUSER_P_EXPTIME, expTime)
                .setParam(USER_P_ROLE, userRole);
    }

    @Override
    protected TLMsg getLoginState(Object fromWho, TLMsg msg) {
        HashMap<String ,Object> datas =getThreadData();
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
            // 非请求线程 / 上下文未就绪时 requestMap、request 都可能为 null
            HttpServletRequest request = requestMap == null ? null : requestMap.get(threadName);
            if (request != null)
                token = request.getHeader("Access-User-Token");
        }
        if (token == null )
        {
            TLMsg domsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
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
        HashMap<String ,Object> datas =getThreadData();
        if (datas == null)
            putLog("login: 无请求线程上下文，用户状态未登记, userid:" + userid, LogLevel.WARN, "login");
        else
            datas.putAll(userInfo);
        putLog("user login:"+userid,LogLevel.DEBUG,"login");
        return createMsg().setParam(USER_P_USERID, userid).setParam(USER_P_ROLE, userRole).setParam(USER_P_USERNAME, username);
    }

    /** 登录时校验用户有效性（是否被禁用/删除）。
     *  默认实现放行任何签名有效的 token——纯内部/无用户表的场景可用，但有用户体系时应覆写。
     *  JWT 自身只保证签名与有效期，用户被删后旧 token 在过期前仍然有效。 */
    protected boolean checkUserValid(String userid) {
        if (!userValidWarned) {
            userValidWarned = true;
            putLog("TLWUserByToken.checkUserValid 未覆写：不校验用户是否被禁用/删除，"
                    + "旧 token 在过期前均有效（有用户表时应覆写本方法）", LogLevel.WARN, "checkUserValid");
        }
        return true ;
    }
    private volatile boolean userValidWarned = false;

    private TLMsg getToken(Object fromWho, TLMsg msg) {
        Object role = msg.getParam(USER_P_ROLE);
        List<String> roleList = roleToRoleList( role);
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
        HashMap<String ,Object> datas =getThreadData();
        if (datas == null)
            return createMsg().setParam(TOKENUSER_R_TOKEN, null);
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
                ArrayList<String> roleList = (ArrayList<String>) datas.get(USER_P_ROLE);
                String username =(String) datas.get(USER_P_USERNAME);
                String group =(String) datas.get(USER_P_GROUP);
                return createMsg().setParam(TOKENUSER_R_TOKEN, createToken(userid, roleList,username,group));
            }
        } catch (ParseException e) {
            return createMsg().setParam(TOKENUSER_R_TOKEN, null);
        }
        return createMsg().setParam(TOKENUSER_R_TOKEN, null);
    }
    private List<String> roleToRoleList(Object role){
        if(role ==null)
            return null ;
        List<String> roleList = null;
        if( role instanceof  String)
        {
            roleList =new ArrayList<>() ;
            roleList.add((String) role);
        }
        else  if( role instanceof List)
            roleList = (List<String>) role;
        return roleList ;
    }
    private HashMap<String ,Object> getThreadData(){
        String threadName=Thread.currentThread().getName();
        Map<String,HashMap<String ,Object>> threadDatas = (Map<String, HashMap<String, Object>>) getModule("threadDatas");
        if (threadDatas == null)
            return null ;
        return threadDatas.get(threadName);
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
