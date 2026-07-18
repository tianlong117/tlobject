package cn.tianlong.tlobject.modules;

import cn.tianlong.tlobject.base.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

/**
 * 当模块创立时动态加入before 和after msgTable
 * <moduleConfig>
 *     <msgTable>
 *      <msgid  value="urlMap" >
 *          <msg action="addBeforeMsg"  ddestination="msglog" daction="transferLog" postion="1" />
 *      </msgid>
 *
 *      <msgid  value="velocity" >
 *          <msg action="addAfterMsg"  maction="putDataToUser"
 *               daction="writeCache" ddestination="servletCache" />
 *      </msgid>
 *      <msgid  value="appCenter" >
 *          <msg action="addAfterMsg" maction="index" ddestination="tllog" daction="startLog" postion="1"/>
 *          <msg action="addBeforeMsg"  maction="velocity" daction="getCache" ddestination="servletCache" />
 *      </msgid>
 *     </msgTable>
 * </moduleConfig>
 */
public  class TLABMsgTableAdd extends TLBaseModule {
    protected   static ScheduledExecutorService executor ;

    public TLABMsgTableAdd(String name ){
        super(name);
    }
    public TLABMsgTableAdd(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected TLBaseModule init() {
        return  this ;
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "addMsg":
                addMsg(fromWho,msg);
                break;
            case "fromFactoryGetModule" :
                returnMsg=fromFactoryGetModule(fromWho,msg);
                break;
            default:
        }
        return returnMsg;
    }

    private TLMsg fromFactoryGetModule(Object fromWho, TLMsg msg) {
        if (msgTable == null || msgTable.isEmpty()) return msg;
        TLMsg  returnMsg = (TLMsg) msg.getSystemParam(PRERESULT);
        if(returnMsg ==null || returnMsg.getParam("new")==null) return msg;
        String moduleName= (String) returnMsg.getParam("moduleName");
        if(moduleName ==null || moduleName==name) return msg;
        HashMap<String, Object> entry = msgTable.get(moduleName);
        ArrayList<TLMsg> msgList = entry != null ? (ArrayList<TLMsg>) entry.get("msglist") : null;
        if (msgList == null) return msg;
        for (int i = 0; i < msgList.size(); i++) {
            TLMsg lmsg=msgList.get(i);
            String ddes=(String) lmsg.getParam("ddestination");
            String daction=(String)lmsg.getParam("daction");
            String dmsgid=(String)lmsg.getParam("dmsgid");
            if(ddes==null || (daction==null && dmsgid==null))
                continue;
            TLMsg logmsg=createMsg().setDestination(ddes)
                    .setAction(daction).setMsgId(dmsgid).addArgs(lmsg.getArgs());
            String position = (String) lmsg.getParam("postion");
            String maction= (String) lmsg.getParam("maction");
            TLMsg addlogMsg =createMsg();
            String msgtype=lmsg.getAction();
            addlogMsg.setAction(msgtype);
            addlogMsg.setParam("msg",logmsg);
            addlogMsg.setParam("position",position);
            addlogMsg.setParam("action",maction);
            putMsg((IObject) returnMsg.getParam("instance"),addlogMsg);
            putLog("msgTableAdd :"
                    +moduleName+"->"+maction
                    +"表："+msgtype+" "+"目的:"+ddes+"->"+daction,LogLevel.DEBUG);
        }
        return msg;
    }
    private void addMsg(Object fromWho, TLMsg msg) {
        TLMsg logmsg=new TLMsg().setDestination(name)
                .setAction("fromFactoryGetModule");
        TLMsg fmsg=createMsg().setAction("addAfterMsg").setParam("action","getModule")
                .setParam("msg",logmsg).setParam("position",0);
        putMsg(moduleFactory,fmsg);
    }
}
