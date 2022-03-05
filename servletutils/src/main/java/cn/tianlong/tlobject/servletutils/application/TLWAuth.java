package cn.tianlong.tlobject.servletutils.application;

import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.xmlpull.v1.XmlPullParser;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLWAuth extends TLBaseModule {
    protected String msgNameForCheck="domsg";
    protected String defaultPolicy ;
    protected String defaultDenyMsg ;
    protected String defaultSuperUser ;
    protected String defaultSuperRole ;
    protected ConcurrentHashMap<String, HashMap<String, String>> authModules ;
    protected ConcurrentHashMap<String, HashMap<String, String>> policies ;
    protected ConcurrentHashMap<String, HashMap<String, Object>> policiesSet =new ConcurrentHashMap<>();
    protected ConcurrentHashMap<String, HashMap<String, String>> authMsgids ;
    protected HashMap<String, HashMap<String, String>> authTags ;
    public TLWAuth(){
        super();
    }
    public TLWAuth(String name ){
        super(name);
    }
    public TLWAuth(String name ,TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void setModuleParams() {
        if(params !=null &&  params.get("fieldsInModule")!=null)
        {
            Map configPolicies =((myConfig)mconfig).getPolicies() ;
            if(configPolicies !=null)
            {
               if(policies !=null)
                  policies.putAll(configPolicies);
            }
        }
    }
    @Override
    protected TLBaseModule init() {
        makePolicy();
        return this ;
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if(params !=null && params.get("msgNameForCheck")!=null)
            msgNameForCheck=params.get("msgNameForCheck");
        defaultPolicy= params.get("defaultPolicy") ;
        defaultDenyMsg= params.get("defaultDenyMsg") ;
        defaultSuperUser =params.get(AUTH_P_DEFAULTSUPERUSER) ;
        defaultSuperRole =params.get(AUTH_P_DEFAULTSUPERROLE) ;
    }

    protected void makePolicy() {
        if(policies ==null)
            return;
        for(String policyName : policies.keySet()){
            addParentPolicy(policyName);
        }
        for(String policyName : policies.keySet()){
            HashMap<String,String> policy =policies.get(policyName);
            HashMap<String, Object> newMap= policyToSet(policy);
            policiesSet.put(policyName,newMap) ;
        }
    }

    private HashMap<String, Object> policyToSet(HashMap<String,String> policy) {
        HashMap<String, Object> newMap =new HashMap<>();
        for(String key : policy.keySet()){
            String value =policy.get(key).trim();
           if(value ==null || value.isEmpty() )
               continue;
           if(key.equals("role")|| key.equals("user")|| key.equals("group"))
           {
               String[] valueArray =TLDataUtils.splitStrToArray(value,";");
               HashSet<String> set = new HashSet<>(Arrays.asList(valueArray));
               newMap.put(key, set);
           }
           else if(key.equals("ip"))
           {
               String[]  ipPolicy = value.split(";");
               newMap.put(key, ipPolicy);
           }
           else
               newMap.put(key, value);
        }
        return  newMap ;
    }

    protected  HashMap<String, String> addParentPolicy (String policyName){
        HashMap<String, String>  policy =policies.get(policyName);
        String parentName= policy.get("parent");
        if(parentName ==null || parentName.isEmpty())
            return policy;
        HashMap<String, String>  parentPolicy =addParentPolicy(parentName);
        if(parentPolicy ==null || parentPolicy.isEmpty())
            return policy;
        HashMap<String, String> tempMap =new HashMap<>();
        tempMap.putAll(parentPolicy);
        tempMap.putAll(policy);
        policy.putAll(tempMap);
        policy.remove("parent");
        return policy ;
    }
    @Override
    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());
        mconfig=config;
        super.setConfig();
        Map configAuthModules =config.getAuthModules();
        if(configAuthModules !=null)
        {
            authModules =new ConcurrentHashMap<>() ;
            authModules.putAll(configAuthModules);
        }
        Map configPolicies =config.getPolicies() ;
        if(configPolicies !=null)
        {
            policies =new ConcurrentHashMap<>() ;
            policies.putAll(configPolicies);
        }
        Map configAuthMsgids = config.getAuthMsgids();
        if(configAuthMsgids !=null)
        {
            authMsgids =new ConcurrentHashMap<>() ;
            authMsgids.putAll(configAuthMsgids);
        }
        authTags=config.getAuthTags();
        return config;
    }

    protected HashMap<String, Object> getPolicies(String policy){
        if(policy ==null || policiesSet==null)
            return null ;
       return policiesSet.get(policy) ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case AUTH_AUTHINURLMAP:
                returnMsg=authInUrlMap( fromWho, msg);
                break;
            case AUTH_AUTHINMODULE:
                returnMsg=authInModule( fromWho, msg);
                break;
            case AUTH_AUTHTAG:
                returnMsg=authTag( fromWho, msg);
                break;
            case AUTH_SETPOLICY:
                setProliy( fromWho, msg);
                break;
            case AUTH_DELETEPOLICY:
                deleteProliy( fromWho, msg);
                break;
            case AUTH_GETPOLICY:
                returnMsg=getProliy( fromWho, msg);
                break;
            case "deny":
                deny( fromWho, msg);
                break;
            default:

        }
        return returnMsg ;
    }

    private void deny(Object fromWho, TLMsg msg) {
        String content ="no auth";
        TLMsg  emsg =createMsg().setAction(OUTINTERFACE_PUTCONTENTTOUSER).setParam(CLIENT_P_CONTENT,content);
        putMsg(M_DIRECTOUTNTERFACE, emsg);
    }

    private void deleteProliy(Object fromWho, TLMsg msg) {
        String policyName = (String) msg.getParam(AUTH_P_POLICYNAME);
        if(policyName ==null || policyName.isEmpty())
            return  ;
        policies.remove(policyName);
        policiesSet.remove(policyName) ;
    }

    private TLMsg getProliy(Object fromWho, TLMsg msg) {
        String policyName = (String) msg.getParam(AUTH_P_POLICYNAME);
        if(policyName ==null || policyName.isEmpty())
          return  createMsg().addMap(policies);
        HashMap<String ,String> policy=policies.get(policyName);
        return  createMsg().addMap( policy);
    }

    private void setProliy(Object fromWho, TLMsg msg) {
        String policyName = (String) msg.getParam(AUTH_P_POLICYNAME);
        if(policyName ==null || policyName.isEmpty())
            return;
        HashMap<String ,String> policy = (HashMap<String, String>) msg.getParam(AUTH_P_POLICYVALUE);
        policies.put(policyName, policy);
        HashMap<String, Object> newMap =policyToSet(policy);
        policiesSet.put(policyName,newMap);

    }
    private void addProliy(Object fromWho, TLMsg msg) {
        String policyName = (String) msg.getParam(AUTH_P_POLICYNAME);
        if(policyName ==null || policyName.isEmpty())
            return;
        Object policyUnit =  msg.getParam(AUTH_P_POLICYVALUE);
        if(policyUnit instanceof Map)
        {
            HashMap<String ,String> policy=policies.get(policyName);
            if(policy ==null)
                policies.put(policyName, (HashMap<String, String>) policyUnit);
            else
                policy.putAll((Map<? extends String, ? extends String>) policyUnit);
            policy=policies.get(policyName);
            HashMap<String, Object> newMap =policyToSet(policy);
            policiesSet.put(policyName,newMap);
        }
    }
    protected TLMsg authTag(Object fromWho, TLMsg msg) {
        HashMap<String ,Object> policy = (HashMap<String, Object>) msg.getParam(AUTH_P_POLICY);
        if(policy ==null){
            String policyName =(String) msg.getParam(AUTH_P_POLICYNAME);
            if(policyName == null || policyName.isEmpty())
            {
                String tag = (String) msg.getParam(AUTH_P_TAG);
                policyName = getPolicyNameByTag( tag);
            }
            policy =getPolicies(policyName);
        }
        if(policy==null )
        {
            StringBuffer logBuffer = new StringBuffer().append("没有对应认证策略:") .append(AUTH_P_POLICYNAME).append((String) msg.getParam(AUTH_P_POLICYNAME))
                    .append(AUTH_P_TAG).append((String) msg.getParam(AUTH_P_TAG));
            putLog(logBuffer.toString(),LogLevel.ERROR,"auth");
            return createMsg().setSystemParam(MODULE_DONEXTMSG,false);
        }
        if(policy.isEmpty())
            return null ;
        String[] policyResult =checkPolicy(policy);
        if(policyResult !=null)
        {
            String checkModule = ((IObject)fromWho).getName();
            StringBuffer logBuffer = new StringBuffer().append("模块: ").append(checkModule) .append(AUTH_P_POLICYNAME).append((String) msg.getParam(AUTH_P_POLICYNAME))
                    .append(AUTH_P_TAG).append((String) msg.getParam(AUTH_P_TAG));
            logBuffer.append("访问拒绝");
            putLog(logBuffer.toString(),LogLevel.DEBUG,"auth");
            String denyMsgId =getDenyMsg(null,null,(String) msg.getParam(AUTH_P_TAG),policy);
            if(denyMsgId !=null)
               putMsg(this,createMsg().setMsgId(denyMsgId).setParam("type",policyResult[0])
                    .setParam("value",policyResult[1]));
            return createMsg().setSystemParam(MODULE_DONEXTMSG,false);
        }
        return null ;
    }

    protected TLMsg authInModule(Object fromWho, TLMsg msg) {
        TLMsg dmsg = (TLMsg) msg.getSystemParam(DOWITHMAG);
        if(dmsg==null)
            return createMsg().setSystemParam(MODULE_DONEXTMSG,false);
        String checkModule=((IObject)fromWho).getName();
        return authForMsg(checkModule,  dmsg) ;
    }

    protected TLMsg authInUrlMap(Object fromWho, TLMsg msg) {
        TLMsg doWithmsg = (TLMsg) msg.getSystemParam(DOWITHMAG);
        TLMsg dmsg = (TLMsg) doWithmsg.getParam(msgNameForCheck);
        if(dmsg==null)
            return createMsg().setSystemParam(MODULE_DONEXTMSG,false);
        String checkModule=dmsg.getDestination();
        return authForMsg( checkModule,  dmsg) ;
    }
    protected TLMsg authForMsg(String checkModule, TLMsg dmsg) {
        String checkAction=dmsg.getAction();
        String checkMsgid=dmsg.getMsgId();
        return auth( checkModule,checkAction,checkMsgid);
    }
    protected TLMsg auth(String checkModule,String checkAction,String checkMsgid) {
        String policyName;
        if(checkModule !=null && !checkModule.isEmpty())
            policyName = getPolicyNameByModule(checkModule,checkAction);
        else {
            if(checkMsgid !=null && !checkMsgid.isEmpty())
                policyName = getPolicyNameByMsgid(checkMsgid);
            else
                policyName=defaultPolicy;
        }
        HashMap<String ,Object> policy =getPolicies(policyName);
        if(policy==null)
        {
            StringBuffer logBuffer = new StringBuffer();
            logBuffer.append("没有对应认证策略:模块: ").append(checkModule).append("动作:").append(checkAction)
                    .append("认证策略:").append(policyName);
            putLog(logBuffer.toString(),LogLevel.ERROR,"auth");
            return createMsg().setSystemParam(MODULE_DONEXTMSG,false);
        }
        if(policy.isEmpty())
            return null ;
        String[] policyResult =checkPolicy(policy);
        if(policyResult !=null)
        {
            StringBuffer logBuffer = new StringBuffer().append("模块: ").append(checkModule).append("动作:")
                    .append(checkAction).append("访问拒绝");
            putLog(logBuffer.toString(),LogLevel.WARN,"auth");
            String denyMsgId =getDenyMsg(checkModule,checkMsgid,null,policy);
            if(denyMsgId !=null)
               putMsg(this,createMsg().setMsgId(denyMsgId).setParam("type",policyResult[0])
                                               .setParam("value",policyResult[1]));
            return createMsg().setSystemParam(MODULE_DONEXTMSG,false);
        }
        return null ;
    }

    protected String getUserObjName() {
        TLMsg returnMsg =putMsg(M_APPCENTER,createMsg().setAction("getUserObj"));
        return (String) returnMsg.getParam("userObj");
    }

    protected String getPolicyNameByMsgid(String checkMsgid) {
        String msgPolicy ="MSGID:"+checkMsgid;
        Map policyMap =getPolicies(msgPolicy);
        if(policyMap !=null )
            return msgPolicy ;
        if(authMsgids ==null || authMsgids.isEmpty())
            return defaultPolicy;
        HashMap<String ,String> moduleHash =authMsgids.get(checkMsgid);
        if(moduleHash ==null)
            return defaultPolicy;
        String policy =moduleHash.get("policy");
        if(policy ==null || policy.isEmpty())
             policy =defaultPolicy;
        return  policy ;
    }
    protected String getPolicyNameByTag(String tag) {
        String tagPolicy ="TAG:"+tag;
        Map policyMap =getPolicies(tagPolicy);
        if(policyMap !=null )
            return tagPolicy ;
        if(authTags ==null || authTags .isEmpty())
            return defaultPolicy;
        HashMap<String ,String> moduleHash =authTags .get(tag);
        if(moduleHash ==null)
            return defaultPolicy;
        String policy =moduleHash.get("policy");
        if(policy ==null || policy.isEmpty())
            policy =defaultPolicy;
        return  policy ;
    }
    protected String getPolicyNameByModule(String checkModule, String checkAction)
    {
        String actionPolicy =checkModule+":"+checkAction;
        Map policyMap =getPolicies(actionPolicy);
        if(policyMap !=null )
            return actionPolicy ;
        if(authModules ==null || authModules.isEmpty())
            return defaultPolicy;
        HashMap<String ,String> moduleHash =authModules.get(checkModule);
        if(moduleHash ==null)
            return defaultPolicy;
        String policy =moduleHash.get(checkAction);
        if(policy ==null || policy.isEmpty())
        {
            policy =moduleHash.get("defaultPolicy") ;
            if(policy ==null)
                policy =defaultPolicy;
        }
        return  policy ;
    }
    protected String getDenyMsg(String checkModule, String checkMsgid, String tag ,HashMap<String ,Object> policy) {
        String denyMsgId= (String) policy.get("denyMsg");
        if(denyMsgId !=null && !denyMsgId.isEmpty())
            return denyMsgId ;
        if(checkModule !=null  && authModules !=null )
        {
            HashMap<String ,String> moduleHash =authModules.get(checkModule);
            if(moduleHash !=null)
                denyMsgId =moduleHash.get("denyMsg");
        }
        else if(checkMsgid !=null  && authMsgids !=null )
            {
                HashMap<String ,String> msgidHash =authMsgids.get(checkMsgid);
                if(msgidHash !=null)
                  denyMsgId =msgidHash.get("denyMsg");
            }
        else if( tag !=null && authTags !=null )
        {
            HashMap<String ,String> msgidHash =authTags.get(tag);
            if(msgidHash !=null)
                denyMsgId =msgidHash.get("denyMsg");
        }
        if(denyMsgId ==null || denyMsgId.isEmpty())
            denyMsgId = defaultDenyMsg ;
        return denyMsgId ;
    }

    protected String[] checkPolicy(HashMap<String ,Object> policy) {
        String  sameAsPolicyName = (String) policy.get("sameAs");
        if(sameAsPolicyName !=null && !sameAsPolicyName.isEmpty()) {
            HashMap<String, Object> sameAsPolicy = getPolicies(sameAsPolicyName);
            if(sameAsPolicy ==null)
                return new String[]{"policy","no exit"};
            if(sameAsPolicy.isEmpty())
                return null ;
            return checkPolicy(sameAsPolicy);
        }
        boolean ifGetLoginState =false ;
        String[] ipPolicy = (String[]) policy.get("ip");
        if(ipPolicy!=null && ipPolicy.length >0)
        {
            boolean result =false ;
            for(int i=0;i<ipPolicy.length;i++){
                if(ipPolicy[i].trim().equals("*"))
                {
                    result =true;
                    break;
                }
            }
            if(result ==false)
            {
                String policyResult =checkIP(ipPolicy);
                if(policyResult !=null)
                    return new String[]{"ip",policyResult};
            }
        }
        TLMsg userStateMsg = null;
        String tokenExpirePolicy = (String) policy.get("tokenExpire");
        if(tokenExpirePolicy!=null && tokenExpirePolicy.equals("check"))
        {
            if(ifGetLoginState == false)
            {
                ifGetLoginState =true;
                String userobj= getUserObjName();
                userStateMsg =putMsg(userobj,createMsg().setAction(USER_GETLOGINSTATE));
            }
            Boolean tokenExpire = userStateMsg.parseBoolean("tokenExpire",true);
            if (tokenExpire == true)
                return new String[]{"tokenExpire","true"};
        }
        if(defaultSuperUser !=null)
        {
            if(ifGetLoginState == false)
            {
                ifGetLoginState =true;
                String userobj= getUserObjName();
                userStateMsg =putMsg(userobj,createMsg().setAction(USER_GETLOGINSTATE));
            }
            if(userStateMsg != null && userStateMsg.getParam(USER_P_USERID) !=null )
            {
                String userid = (String) userStateMsg.getParam(USER_P_USERID);
                if(userid.equals(defaultSuperUser))
                    return null ;
            }
        }
        if(defaultSuperRole !=null)
        {
            if(ifGetLoginState == false)
            {
                ifGetLoginState =true;
                String userobj= getUserObjName();
                userStateMsg =putMsg(userobj,createMsg().setAction(USER_GETLOGINSTATE));
            }
            if(userStateMsg != null && userStateMsg.getParam(USER_P_ROLE) !=null )
            {
                List userRoles = (List) userStateMsg.getParam(USER_P_ROLE);
                if(userRoles !=null && userRoles.contains(defaultSuperRole))
                    return null ;
            }
        }
        HashSet<String> userPolicy = (HashSet<String>) policy.get("user");
        if(userPolicy!=null && !userPolicy.isEmpty())
        {
            if(ifGetLoginState == false) {
                ifGetLoginState = true;
                String userobj= getUserObjName();
                userStateMsg = putMsg(userobj, createMsg().setAction(USER_GETLOGINSTATE));
            }
            String userid=null ;
            if(userStateMsg != null && userStateMsg.getParam(USER_P_USERID) !=null )
                userid = (String)userStateMsg.getParam(USER_P_USERID);
            if( userid ==null  )
                return new String[]{"user",""};
            if( userPolicy.contains(userid))
                return null ;
            if( !userPolicy.contains("*"))
                return new String[]{"user",userid};
        }

        HashSet<String> rolePolicy = (HashSet<String>) policy.get("role");
        if(rolePolicy!=null && !rolePolicy.isEmpty() )
        {
            if(ifGetLoginState == false) {
                ifGetLoginState = true;
                String userobj= getUserObjName();
                userStateMsg = putMsg(userobj, createMsg().setAction(USER_GETLOGINSTATE));
            }
            List userRoles=null ;
            if(userStateMsg != null && userStateMsg.getParam(USER_P_ROLE) !=null )
                userRoles = (List) userStateMsg.getParam(USER_P_ROLE);
            if(userRoles ==null  &&  !rolePolicy.contains(USER_V_ROLE_GUEST ))
                 return new String[]{"role",USER_V_ROLE_GUEST };
            boolean result =false;
            for(int i =0 ; i< userRoles.size() ; i++)
            {
                if(rolePolicy.contains(userRoles.get(i)))
                {
                    result =true ;
                    break;
                }
            }
            if(result ==false)
               return new String[]{"role",userRoles.toString()};
        }

        HashSet<String> groupPolicy = (HashSet<String>) policy.get("group");
        if(groupPolicy!=null && !groupPolicy.isEmpty())
        {
            if(ifGetLoginState == false) {
                ifGetLoginState = true;
                String userobj= getUserObjName();
                userStateMsg = putMsg(userobj, createMsg().setAction(USER_GETLOGINSTATE));
            }
            String userGroup=null ;
            if(userStateMsg != null && userStateMsg.getParam(USER_P_ROLE) !=null )
                userGroup = (String)userStateMsg.getParam(USER_P_GROUP);
            if(userGroup ==null )
                return new String[]{"group",""};
            if(!groupPolicy.contains("*") && !groupPolicy.contains(userGroup))
                return new String[]{"role",userGroup};
        }

        String refusedUserMsg = (String) policy.get("refusedUserMsg");
        if(refusedUserMsg!=null && !refusedUserMsg.isEmpty())
        {
            if(ifGetLoginState == false)
            {
                ifGetLoginState = true;
                String userobj= getUserObjName();
                userStateMsg = putMsg(userobj, createMsg().setAction(USER_GETLOGINSTATE));
            }
            String userid=null ;
            if(userStateMsg != null && userStateMsg.getParam(USER_P_USERID) !=null )
                userid = (String)userStateMsg.getParam(USER_P_USERID);
            if( userid ==null )
                return new String[]{"user",""};
            String policyResult =checkRefusedUser(refusedUserMsg,userid);
            if(policyResult !=null)
                return new String[]{"user",policyResult};
        }
        return null;
    }

    protected String checkRefusedUser(String refusedUserMsg, String userid) {
        TLMsg rmsg =createMsg().setMsgId(refusedUserMsg).setParam("userid",userid);
        TLMsg resultMsg =getMsg(this,rmsg);
        if(resultMsg!=null)
            return userid;
        else
            return null ;
    }

    protected String checkIP(String[]  iptables) {
        String threadName=Thread.currentThread().getName();
        Map<String,HttpServletRequest> requestMap = (Map<String, HttpServletRequest>) getModuleInFactory("servletRequest");
        HttpServletRequest request =requestMap.get(threadName);
        String ip =request.getRemoteAddr();
        for(int i=0;i<iptables.length;i++)
        {
            int index =iptables[i].trim().indexOf("*");
            if(index <0)
            {
                if(iptables[i].trim().equals(ip))
                    return null ;
            }
            else{
                String subiptable =iptables[i].substring(0,index-1);
                String subip =ip.substring(0,index-1);
                if(subiptable.equals(subip))
                    return null ;
            }
        }
        return ip ;
    }

    public static class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> authModules ;
        protected HashMap<String, HashMap<String, String>> policies ;
        protected HashMap<String, HashMap<String, String>> authMsgids ;
        protected HashMap<String, HashMap<String, String>> authTags ;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }
        public myConfig() {

        }
        public HashMap getAuthModules() {
            return authModules;
        }
        public HashMap getPolicies() {
            return policies;
        }
        public HashMap getAuthMsgids() {
            return authMsgids;
        }
        public HashMap getAuthTags() {
            return authTags;
        }
        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("authModules")) {
                    authModules= getHashMap(xpp,"authModules","module");
                }
                if (xpp.getName().equals("authMsgids")) {
                    authMsgids= getHashMap(xpp,"authMsgids","msgid");
                }
                if (xpp.getName().equals("authTags")) {
                    authTags= getHashMap(xpp,"authTags","tag");
                }
                if (xpp.getName().equals("policies")) {
                    policies= getHashMap(xpp,"policies","policy");
                }
            } catch (Throwable t) {

            }
        }

    }
}
