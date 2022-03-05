package cn.tianlong.tlobject.servletutils;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDataUtils;

import javax.servlet.http.HttpServletRequest;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLWWebSite extends TLWServModule{
    protected  boolean ifOpen=true ;
    private String[] prohibitIp;
    private String[] permitIp;
    private String[] serverName;
    public TLWWebSite(){
        super();
    }
    public TLWWebSite(String name ){
        super(name);
    }
    public TLWWebSite(String name ,TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void setModuleParams()
    {
        if(params !=null)
        {
            if(params.get("prohibitIp") !=null && !params.get("prohibitIp").isEmpty())
                prohibitIp =TLDataUtils.splitStrToArray(params.get("prohibitIp"),";");
            if(params.get("permitIp") !=null && !params.get("permitIp").isEmpty())
                permitIp = TLDataUtils.splitStrToArray(params.get("permitIp"),";");
            if(params.get("serverName") !=null && !params.get("serverName").isEmpty())
                serverName = TLDataUtils.splitStrToArray(params.get("serverName"),";");
            if(params.get("status")!=null){
                if(params.get("status").equals("on"))
                    ifOpen = true ;
                else
                    ifOpen = false ;
            }
        }
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }
    @Override
    public TLMsg getMsg(Object fromWho, TLMsg msg) {
        return  runAction(fromWho,msg);
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "setup":
                returnMsg=setup(fromWho,msg);
                break;
            case "stop":
                stop(fromWho,msg);
                break;
            default:                ;
        }
        return returnMsg ;
    }

    private void stop(Object fromWho, TLMsg msg) {
        ifOpen =false ;
    }

    private TLMsg setup(Object fromWho, TLMsg msg) {

        if(prohibitIp !=null )
        {
            HttpServletRequest request = getRequest();
            if(checkIp(prohibitIp,request.getRemoteAddr())==true)
            {
                putError("ip禁止");
                return  createMsg().setSystemParam(MODULE_DONEXTMSG,false);
            }
        }
        if(serverName!=null )
        {
            HttpServletRequest request = getRequest();
            String server =request.getServerName();
            boolean isServerName =false;
            for(int i=0;i<serverName.length;i++)
            {
                if(serverName[i].equals(server))
                {
                    isServerName =true ;
                    break;
                }
            }
            if(isServerName == false)
            {
                putError("server名字错误");
                return  createMsg().setSystemParam(MODULE_DONEXTMSG,false);
            }
        }
        if(ifOpen ==false)
        {
            if(permitIp !=null)
            {
                HttpServletRequest request = getRequest();
                if(checkIp(permitIp,request.getRemoteAddr())==false)
                {
                    putError("服务关闭");
                    return  createMsg().setSystemParam(MODULE_DONEXTMSG,false);
                }
            }
            else
                return  createMsg().setSystemParam(MODULE_DONEXTMSG,false);
        }
        return null ;
    }
    private boolean checkIp(String[] iptables ,String ip){

        for(int i=0;i<iptables.length;i++)
        {
            int index =iptables[i].indexOf("*");
            if(index <0)
            {
                if(iptables[i].equals(ip))
                    return true ;
            }
            else{
                String subiptable =iptables[i].substring(0,index-1);
                String subip =ip.substring(0,index-1);
                if(subiptable.equals(subip))
                    return true ;
            }
        }
        return false;
    }
}
