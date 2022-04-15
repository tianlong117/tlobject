package cn.tianlong.tlobject.network.server.distributed;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTableModle;
import cn.tianlong.tlobject.db.TLDataBase;
import cn.tianlong.tlobject.utils.TLDateUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLServerConfigInDB extends TLBaseTableModle {

    public TLServerConfigInDB(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);

    }
    @Override
    protected void initProperty() {
        super.initProperty();
       if(tableName==null)
           tableName="servers";
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case DB_INSERT:
                returnMsg=insert( fromWho,  msg);
                break;
            case DB_DELETE:
                returnMsg=delete( fromWho,  msg);
                break;
            case "getServerForUser":
                returnMsg=getServerForUser( fromWho,  msg);
                break;
            case "getOnLineServer":
                returnMsg=getOnLineServer( fromWho,  msg);
                break;
            case "getServer":
                returnMsg=getServer( fromWho,  msg);
                break;
            case "authServer":
                returnMsg=authServer( fromWho,  msg);
                break;
            case "updateServerStatus":
                returnMsg=updateServerStatus( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    protected TLMsg authServer(Object fromWho, TLMsg msg) {
        String servername = (String) msg.getParam("username");
        TLMsg returnMsg =getServer(fromWho,msg.setParam("server",servername));
        Map<String,Object> serverInfo = returnMsg.getMapParam(DB_R_RESULT,null);
        String inputPasswd =msg.getStringParam("passwd","");
        if(inputPasswd.equals(serverInfo.get("password")))
            return createMsg().setParam("userid",servername);
        else
            return null ;
    }

    private TLMsg getServerForUser(Object fromWho, TLMsg msg) {
        String sql = "select server ,ip ,url,servertype from  [table] where group_name=?  and status=1 ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        String group= (String) msg.getParam("group");
        sqlparams.put("group_name", group);
        TLMsg qmsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        return  putMsg(table, qmsg);
    }

    private TLMsg updateServerStatus(Object fromWho, TLMsg msg) {
        String dateStr = TLDateUtils.getNowDateStr(null);
        int status = (int) msg.getParam("status");
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("status", status);
        String sql = "update [table] set status =? ";
        if(status ==1)
        {
            sql =sql + ",logintime =? ,logoutime=NULL " ;
            sqlparams.put("logintime", dateStr);
        }
        else
        {
            sql =sql + ",logoutime =?" ;
            sqlparams.put("logoutime", dateStr);
        }
        sql =sql + " where server =?" ;
        sqlparams.put("server", msg.getParam("server"));
        TLMsg idmsg=createMsg().setAction(DB_UPDATE)
                .setParam(DB_P_SQL,sql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, idmsg);
    }
    protected TLMsg getServer(Object fromWho, TLMsg msg) {
        String sql = "select * from  [table] where  server =?   ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        String server= (String) msg.getParam("server");
        sqlparams.put("server", server);
        TLMsg qmsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP)
                .setParam(DB_P_PARAMS, sqlparams);
        return  putMsg(table, qmsg);
    }
    protected TLMsg getOnLineServer(Object fromWho, TLMsg msg) {
        String serverType = (String) msg.getStringParam("serverType","socket");
        String sql ="select server ,url,serverType from  [table] " ;
        if(serverType.equals("web"))
           sql = sql+" where serverType='socket'  ";
        else if(serverType.equals("socket"))
            sql = sql+" where ( serverType='socket' or serverType='web')  ";
        else
            return null ;
        sql = sql +"  and status=1 and  server <>?   ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        String server= (String) msg.getParam("server");
        sqlparams.put("server", server);
        TLMsg qmsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
       return  putMsg(table, qmsg);
    }

    protected TLMsg delete(Object fromWho, TLMsg msg) {
        String sql = "delete from  [table] where module=?  and param_key =?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("module", msg.getParam("module"));
        sqlparams.put("param_key", msg.getParam("param_key"));
        TLMsg dmsg = createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, dmsg);
    }
    protected TLMsg insert(Object fromWho, TLMsg msg) {

        String sql = "insert into  [table]  (module,param_key,param_value,param_type,remark) values(?,?,?,?,?)  ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("module", msg.getParam("module"));
        sqlparams.put("param_key", msg.getParam("param_key"));
        sqlparams.put("param_value", msg.getParam("param_value"));
        sqlparams.put("param_type", msg.getParam("param_type"));
        sqlparams.put("remark", msg.getParam("remark"));
        TLMsg insertmsg = createMsg().setAction(DB_INSERT)
                    .setParam(DB_P_SQL, sql)
                    .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, insertmsg);
    }

}
