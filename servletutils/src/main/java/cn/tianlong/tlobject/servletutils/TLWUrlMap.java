package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import org.xmlpull.v1.XmlPullParser;
import java.util.ArrayList;
import java.util.HashMap;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

public class TLWUrlMap extends TLWServModule {

    protected HashMap<String, ArrayList<TLMsg>> urlMapTable;
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
        mconfig.parse(configFile);
        HashMap<String, ArrayList<TLMsg>> urlMap =((myConfig)mconfig).getUrlMapTable() ;
        return createMsg().setArgs(urlMap);
    }

    private TLMsg getPrefixUrl(Object fromWho, TLMsg msg) {
        return createMsg().setParam("prefixUrl",prefixUrl);
    }

    protected TLMsg doWithUrl(Object fromWho, TLMsg msg) {

        if(urlMapTable ==null || urlMapTable.isEmpty())
           return putError("no urlMap, Configure urlMap");
        String url=(String) msg.getParam("url");
        if(prefixUrl!=null)
          url=url.substring(prefixUrl.length());
        ArrayList msgList = urlMapTable.get(url);
        if (msgList == null || msgList.isEmpty())
        {
            String  mappath =url.substring(0,url.lastIndexOf("/")+1)+"*";
            msgList = urlMapTable.get(mappath);
            if (msgList == null || msgList.isEmpty())
            {
                mappath =url.substring(0,url.indexOf("/")+1)+"*";
                msgList = urlMapTable.get(mappath);
                if (msgList == null || msgList.isEmpty()) {
                   return   putError("no urlmap");
                }
            }
            String  action =url.substring(url.lastIndexOf("/")+1);
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
            msg.removeParam("url");
            dmsg.setDestination(smsg.getDestination());
            dmsg.setWaitFlag(smsg.getWaitFlag());
            HashMap map =msg.getArgs();
            if(!map.isEmpty())
              dmsg.addArgs(map);
            return doWithUrlMsg(dmsg);
        }
        else
        {
            setThreadData("url" ,url);
            msg.removeParam("url");
            HashMap map =msg.getArgs();
            TLMsg returnMsg = null;
            int msgListSize = msgList.size();
            for(int i = 0;i < msgListSize; i ++)
            {
                TLMsg dmsg =createMsg().copyFrom(((TLMsg) msgList.get(i)));
                if(!map.isEmpty())
                    dmsg.addArgs(map);
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
            HashMap inputVars=getInputVar(dmsg);
            if(inputVars !=null)
                dmsg.addArgs(inputVars);
            if(beforeAction.equals("direct"))
                return putMsg(dmsg.getDestination(),dmsg);
            else {
                TLMsg smg =createMsg().setAction(beforeAction).setParam("domsg",dmsg);
                TLMsg returnMsg= getMsg(this,smg);
                if( ifDoNextMsg(returnMsg))
                   return putMsg(dmsg.getDestination(),dmsg);
                else
                    return returnMsg ;
            }
        }
        else
        {
            HashMap inputVars=getInputVar(dmsg);
            if(inputVars !=null)
               dmsg.addArgs(inputVars);
            TLMsg returnMsg=putMsg(this,createMsg().setMsgId(beforeMsgId).setParam("domsg",dmsg));
            if( ifDoNextMsg(returnMsg))
               return putMsg(dmsg.getDestination(),dmsg);
            else
                return returnMsg ;
        }
    }

    private TLMsg toServlet(Object fromWho, TLMsg msg) {
        TLMsg dmsg =(TLMsg) msg.getParam("domsg");
        HashMap inputVars=getInputVar(dmsg);
        if(inputVars !=null)
            dmsg.addArgs(inputVars);
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
        String inInterface =(String) dmsg.getAndRemoveParam("inInterface");
        TLMsg inputs ;
        if(inInterface !=null && !inInterface.isEmpty())
            inputs=putMsg(inInterface,createMsg().setAction(ININTERFACE_GETDATAFROMUSER).setParam(CLIENT_P_VARNAME,clientVars ));
        else{
            String clientType =(String) dmsg.getAndRemoveParam("clientType");
            if(clientType !=null && !clientType.isEmpty())
                inputs=putMsg(clientType,createMsg().setAction(WEBCLIENT_GETINDATA).setParam(CLIENT_P_VARNAME,clientVars ));
            else {
                String clientUser= (String) dmsg.getAndRemoveParam("clientUser",defaultClientUser);
                TLMsg returnMsg =putMsg(clientUser,createMsg().setAction(USER_GETCLIENT));
                String client= (String) returnMsg.getParam(USER_R_CLIENT);
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
                    urlMapTable= getMsgTable(xpp,"url-mapping","url");
                }

            } catch (Throwable t) {

            }
        }

    }
}
