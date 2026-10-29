package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import org.xmlpull.v1.XmlPullParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

public class TLWUrlMap extends TLWServModule {

    /** reload 会整体替换引用，请求线程并发读：volatile 保证可见性（旧表只读不写） */
    protected volatile HashMap<String, ArrayList<TLMsg>> urlMapTable;
    protected String prefixUrl;
    protected String defaultClientUser ="webUser";
    public TLWUrlMap(){
        super();
    }
    public TLWUrlMap(String name ){
        super(name);
    }
    public TLWUrlMap(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }
    @Override
    protected void initProperty(){
        super.initProperty();
        if (params != null && params.get("prefixUrl") != null)
            prefixUrl = params.get("prefixUrl");
        if (params != null && params.get("defaultClientUser") != null)
            defaultClientUser = params.get("defaultClientUser");
        setClientVarToArray() ;
    }
    protected void setClientVarToArray(){
        if(urlMapTable ==null || urlMapTable.isEmpty())
            return;
        for(String url : urlMapTable.keySet()){
            ArrayList<TLMsg> msgList = urlMapTable.get(url);
            for (int i = 0; i <  msgList.size(); i++)
            {
                TLMsg msg =msgList.get(i) ;
                if( !msg.isNull("varType") && msg.getParam("varType").equals(CLIENT_V_JSON) )
                {
                    msg.setParam("inInterface",M_JSONININTERFACE) ;
                    msg.removeParam("varType");
                }
                if(!msg.isNull("clientVars") )
                {
                    String[] paramskey =((String)msg.getParam("clientVars")).trim().split(";");
                    if(paramskey !=null && paramskey.length >0)
                        msg.setParam("clientVars",paramskey) ;
                }
                else {
                    if((!msg.isNull("clientType") && msg.getParam("clientType").equals("jsonClient"))
                            ||(!msg.isNull("inInterface") && msg.getParam("inInterface").equals(M_JSONININTERFACE)))
                    {
                        String[] clientVars ={"*"} ;
                        msg.setParam("clientVars",clientVars) ;
                    }
                }

            }
        }
    }
    @Override
    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());
        mconfig=config;
        super.setConfig();
        urlMapTable=config.getUrlMapTable();
        return config ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case"doWithUrl":
                returnMsg=doWithUrl( fromWho, msg);
                break;
            case"toServlet":
                returnMsg=toServlet( fromWho, msg);
                break;
            case"getPrefixUrl":
                returnMsg=getPrefixUrl( fromWho, msg);
                break;
            case "addUrlMapTable":
                if (urlMapTable == null)
                    urlMapTable = new HashMap<>();
                addMsgTable(urlMapTable, "url", msg);
                returnMsg=msg;
                break;
            case"getUrlMapTable":
                returnMsg=getUrlMapTable( fromWho, msg);
                break;
            default:

        }
        return returnMsg ;
    }

    private TLMsg getUrlMapTable(Object fromWho, TLMsg msg) {
        // 重新解析配置后必须把结果写回模块字段，否则"热刷新"只返回新表、路由仍用旧表
        synchronized (this) {
            mconfig.parse(configFile);
            HashMap<String, ArrayList<TLMsg>> urlMap = ((myConfig) mconfig).getUrlMapTable();
            if (urlMap != null)
                urlMapTable = urlMap;
            return createMsg().setArgs(urlMap);
        }
    }

    private TLMsg getPrefixUrl(Object fromWho, TLMsg msg) {
        return createMsg().setParam("prefixUrl",prefixUrl);
    }

    protected TLMsg doWithUrl(Object fromWho, TLMsg msg) {

        if(urlMapTable ==null || urlMapTable.isEmpty())
           return putError("no urlMap, Configure urlMap");
        String url=(String) msg.getParam("url");
        putLog("user url"+url,LogLevel.DEBUG,"doWithUrl");
        if(url ==null || url.isEmpty())
        {
            url= (String) getThreadData("url");
            if(url ==null || url.isEmpty())
             return putError("no url");
        }
        if(prefixUrl!=null && url.length() >= prefixUrl.length())
          url=url.substring(prefixUrl.length());
        ArrayList msgList = urlMapTable.get(url);
        if (msgList == null || msgList.isEmpty())
        {
            int lastSlashIdx = url.lastIndexOf("/");
            if (lastSlashIdx < 0) {
                return putError("no urlmap");
            }
            String  mappath =url.substring(0,lastSlashIdx+1)+"*";
            msgList = urlMapTable.get(mappath);
            if (msgList == null || msgList.isEmpty())
            {
                int firstSlashIdx = url.indexOf("/");
                if (firstSlashIdx < 0) {
                    return putError("no urlmap");
                }
                mappath =url.substring(0,firstSlashIdx+1)+"*";
                msgList = urlMapTable.get(mappath);
                if (msgList == null || msgList.isEmpty()) {
                   return   putError("no urlmap");
                }
            }
            String  action =url.substring(lastSlashIdx+1);
            if(action==null || action.isEmpty())
                action="default";
            TLMsg smsg=(TLMsg) msgList.get(0);
            TLMsg dmsg=createMsg();
            if((smsg.getAction()==null || smsg.getAction().isEmpty()) && smsg.getMsgId()==null  )
            {
                String maction = (String) smsg.getParam(action);
                if(maction==null || maction.isEmpty() || action.equals("clientUser"))
                {
                    maction = (String) smsg.getParam("default");
                    if(maction!=null)
                        dmsg.setAction(maction);
                    else
                       return putError("no urlmap");
                }
                else
                    dmsg.setAction(maction);
            }
            setThreadData("url" ,url);
            putLog("url"+url,LogLevel.DEBUG,"doWithUrl");
            msg.removeParam("url");
            dmsg.setDestination(smsg.getDestination());
            dmsg.setWaitFlag(smsg.getWaitFlag());
            dmsg.addArgs(msg.getArgs());
            return doWithUrlMsg(dmsg);
        }
        else
        {
            setThreadData("url" ,url);
            msg.removeParam("url");
            TLMsg returnMsg = null;
            int msgListSize = msgList.size();
            for(int i = 0;i < msgListSize; i ++)
            {
                TLMsg dmsg =createMsg().copyFrom(((TLMsg) msgList.get(i)));
                dmsg.addArgs(msg.getArgs());
                returnMsg= doWithUrlMsg(dmsg);
                if(msgListSize >1 && ifDoNextMsg(returnMsg))
                    continue;
            }
            return returnMsg ;
        }
    }

    private TLMsg doWithUrlMsg(TLMsg dmsg) {
        String clientUser= (String) dmsg.getParam("clientUser",defaultClientUser);
        setThreadData("userObj" ,clientUser);
        String clientType =(String) dmsg.getParam("clientType");
        if(clientType!=null && !clientType.isEmpty())
            setThreadData("client" ,clientType);
        String beforeMsgId= (String) dmsg.getAndRemoveParam("beforeMsgId",null);
        if(beforeMsgId ==null || beforeMsgId.isEmpty())
        {
            String beforeAction= (String) dmsg.getAndRemoveParam("beforeAction","toServlet");
            if(beforeAction.equals("toServlet")){
                TLMsg smg =createMsg().setAction("toServlet").setParam("domsg",dmsg);
                return getMsg(this,smg);
            }
            dmsg.addArgs(getInputVar(dmsg));
            // 配置里 <url> 没写 destination 时 putMsg(null,...) 会在 getModule 里 NPE；
            // toServlet 分支自带判空，这里补齐另外两条路径
            String destination = dmsg.getDestination();
            if (destination == null || destination.isEmpty()) {
                putLog("urlmap 未配置 destination，丢弃消息: " + dmsg.getAction(), LogLevel.ERROR);
                return null;
            }
            if(beforeAction.equals("direct"))
                return putMsg(destination,dmsg);
            else {
                TLMsg smg =createMsg().setAction(beforeAction).setParam("domsg",dmsg);
                TLMsg returnMsg= getMsg(this,smg);
                if( ifDoNextMsg(returnMsg))
                   return putMsg(destination,dmsg);
                else
                    return returnMsg ;
            }
        }
        else
        {
            dmsg.addArgs(getInputVar(dmsg));
            TLMsg returnMsg=putMsg(this,createMsg().setMsgId(beforeMsgId).setParam("domsg",dmsg));
            if( ifDoNextMsg(returnMsg))
               return putMsg(dmsg.getDestination(),dmsg);
            else
                return returnMsg ;
        }
    }

    private TLMsg toServlet(Object fromWho, TLMsg msg) {
        TLMsg dmsg =(TLMsg) msg.getParam("domsg");
        dmsg.addArgs(getInputVar(dmsg));
        String destionation =dmsg.getDestination();
        if(destionation!=null && !destionation.isEmpty())
            return   putMsg(destionation, dmsg);
        else
        {
            putLog("no destionation",LogLevel.ERROR);
            return null ;
        }
    }
    protected HashMap getInputVar(TLMsg dmsg)
    {
        String[] clientVars = (String[]) dmsg.getAndRemoveParam("clientVars",null);
        if(clientVars ==null)
        {
            dmsg.removeParam("inInterface");
            dmsg.removeParam("clientType");
            dmsg.removeParam("clientUser");
            return null ;
        }
        Object inInterfaceObj =dmsg.getAndRemoveParam("inInterface");
        String inInterface = inInterfaceObj instanceof String ? (String) inInterfaceObj : null;
        TLMsg inputs ;
        if(inInterface !=null && !inInterface.isEmpty())
            inputs=putMsg(inInterface,createMsg().setAction(ININTERFACE_GETDATAFROMUSER).setParam(CLIENT_P_VARNAME,clientVars ));
        else{
            Object clientTypeObj =dmsg.getAndRemoveParam("clientType");
            String clientType = clientTypeObj instanceof String ? (String) clientTypeObj : null;
            if(clientType !=null && !clientType.isEmpty())
                inputs=putMsg(clientType,createMsg().setAction(WEBCLIENT_GETINDATA).setParam(CLIENT_P_VARNAME,clientVars ));
            else {
                Object clientUserObj = dmsg.getAndRemoveParam("clientUser", defaultClientUser);
                String clientUser = clientUserObj instanceof String ? (String) clientUserObj : defaultClientUser;
                if (clientUser == null || clientUser.isEmpty())   // 无用户模块可寻址
                    return null;
                TLMsg returnMsg =putMsg(clientUser,createMsg().setAction(USER_GETCLIENT));
                if (returnMsg == null)
                    return null;
                Object clientObj = returnMsg.getParam(USER_R_CLIENT);
                if (!(clientObj instanceof String))
                    return null;
                String client= (String) clientObj;
                inputs=putMsg(client,createMsg().setAction(WEBCLIENT_GETINDATA).setParam(CLIENT_P_VARNAME,clientVars ));
            }
        }
        if(inputs !=null)
            return inputs.getArgs();
        return null;
    }

   public static class myConfig extends TLModuleConfig {
        protected HashMap<String, ArrayList<TLMsg>> urlMapTable;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }
        public myConfig() {

        }
        public HashMap getUrlMapTable() {
            return urlMapTable;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("url-mapping")) {
                    LinkedHashMap<String, ArrayList<TLMsg>> linked = getLinkHashMsgList(xpp, "url-mapping", "url");
                    if (linked != null) {
                        urlMapTable = new HashMap<>();
                        urlMapTable.putAll(linked);
                    }
                }

            } catch (Throwable t) {

            }
        }

    }
}
