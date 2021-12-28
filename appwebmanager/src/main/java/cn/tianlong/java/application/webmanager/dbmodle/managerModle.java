package cn.tianlong.java.application.webmanager.dbmodle;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLBaseTableModle;
import cn.tianlong.tlobject.db.TLDataBase;
import org.apache.commons.codec.digest.DigestUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.tianlong.tlobject.servletutils.TLParamString.USER_P_USERID;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class managerModle extends TLBaseTableModle {

    public managerModle(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        tableName="managers";
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "addUser":
                returnMsg=addUser( fromWho,  msg);
                break;
            case "changePassword":
                returnMsg=changePassword( fromWho,  msg);
                break;
            case "getByCreator":
                returnMsg=getByCreator(fromWho,  msg);
                break ;
            case "getUser":
                returnMsg=getUser(fromWho,  msg);
                break ;
            case "findUser":
                returnMsg=findUser(fromWho,  msg);
                break;
            case "countByRole":
                returnMsg=countByRole(fromWho,  msg);
                break;
            case "getAllUser":
                returnMsg=getAllUser(fromWho,  msg);
                break;
            case "getUserCount":
                returnMsg=getUserCount(fromWho,  msg);
                break;
            case "getUserByRole":
                returnMsg=getUserByRole(fromWho,  msg);
                break;
            case "updateLoginState":
                returnMsg=updateLoginState(fromWho,  msg);
                break;
            case "updatState":
                returnMsg=updateState(fromWho,  msg);
                break;
            case "deleteUser":
                returnMsg=deleteUser(fromWho,  msg);
                break;
            case "updateUser":
                returnMsg=updateUser(fromWho,  msg);
                break;
            case "checkDenyUser":
                returnMsg=checkDenyUser(fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    protected TLMsg checkDenyUser(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        if(userid==null || userid.isEmpty())
            return  null ;
        String sql =" select status  from [table] where userid=? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("userid", userid);
        TLMsg qmsg = createMsg().setAction("query")
                .setParam("sql", sql)
                .setParam("resultType", TLDataBase.RESULT_TYPE.MAP)
                .setParam("params", sqlparams);
        TLMsg resultMsg= putMsg(table, qmsg);
        Map infos = (Map)resultMsg.getParam("result");
        if(infos ==null || infos.isEmpty())
            return null ;
        TLMsg returnMsg =  createMsg().addArgs(infos);
        int status= (int) returnMsg.getParam("status");
        if (status==0)
            returnMsg.setParam(MODULE_DONEXTMSG,false).setParam("errorMessage","账户禁用");
        return returnMsg ;
    }

    protected TLMsg updateUser(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        String idsql ="update  [table]  set username=? ,roleid=? where userid=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("username", msg.getParam("username"));
        sqlparams.put("roleid", msg.getParam("roleid"));
        sqlparams.put("userid", userid);
        TLMsg idmsg=createMsg().setAction(DB_UPDATE).setParam(DB_P_SQL,idsql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, idmsg);
    }

    protected TLMsg getByCreator(Object fromWho, TLMsg msg) {
        String creator = (String) msg.getParam("creator");
        String userid = (String) msg.getParam("userid");
        String sql = " select username ,status ,roleid ,creator from [table] where userid=? and creator=? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("userid", userid);
        sqlparams.put("creator", creator);
        TLMsg qmsg = createMsg().setAction("query")
                .setParam("sql", sql)
                .setParam("resultType", TLDataBase.RESULT_TYPE.MAP)
                .setParam("params", sqlparams);
        return putMsg(table, qmsg);
    }

    protected TLMsg getUser(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        String sql =" select username ,status ,roleid ,creator from [table] where userid=? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("userid", userid);
        TLMsg qmsg = createMsg().setAction("query")
                .setParam("sql", sql)
                .setParam("resultType", TLDataBase.RESULT_TYPE.MAP)
                .setParam("params", sqlparams);
       return putMsg(table, qmsg);
    }

    protected TLMsg deleteUser(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        String sql =" delete from [table] where userid=? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("userid", userid);
        TLMsg qmsg = createMsg().setAction(DB_DELETE)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, qmsg);
    }
    protected TLMsg getUserCount(Object fromWho, TLMsg msg) {
        String creator = (String) msg.getParam("creator");
        String   sql =" select count(*) as number from [table]  ";
        if(creator !=null)
            sql =sql+"  where creator=?  ";
        TLMsg qmsg = createMsg().setAction(DB_QUERY).setParam(DB_P_SQL, sql).setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP);
        if(creator !=null){
            LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
            sqlparams.put("creator", creator);
            qmsg .setParam(DB_P_PARAMS, sqlparams);
        }
        TLMsg returnMsg= putMsg(table, qmsg);
        Map<String,Object> map = (Map<String, Object>) returnMsg.getParam(DB_R_RESULT);
        return  createMsg().addMap(map);
    }
    protected TLMsg getAllUser(Object fromWho, TLMsg msg) {
        int page = Integer.parseInt((String) msg.getParam("page"));
        String limit = (String) msg.getParam("limit");
        int pageSize =Integer.parseInt(limit);
        int start =(page-1)*pageSize;
        String limitStr =" limit "+start+","+pageSize;
        String orderStr =" order by m.registtime desc ";
        String creator = (String) msg.getParam("creator");
        String  sql =" select m.userid,m.username,m.roleid,m.status,m.registtime,m.creator  ";
        String  fromSql =" from [table] m  ";
        String   countsql =" select count(*) as number   " ;
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        String whereStr =" where " ;
        if(creator==null)
            whereStr = whereStr +" 1=1 ";
        else
        {
            whereStr = " creator=?  " ;
            sqlparams.put("creator", creator);
        }
        if(!msg.isNull("roleid"))
        {
            String roleid = (String) msg.getParam("roleid");
            fromSql=fromSql+" ,userroles r ";
            whereStr =whereStr +" and r.roleid =?  and m.userid=r.userid " ;
            sqlparams.put("roleid",  roleid);
        }
        if(!msg.isNull("depid") )
        {
            String depid = (String) msg.getParam("depid");
            sql =sql + ",d.sort ,d.did as did ";
            fromSql=fromSql+" ,department_user d ";
            orderStr =" order by d.sort desc ";
            whereStr =whereStr +" and d.did =?  and m.userid=d.userid " ;
            sqlparams.put("depid",  depid);
        }
        if(!msg.isNull("userid"))
        {
            String userid = (String) msg.getParam("userid");
            whereStr =whereStr +" and m.userid like ?   " ;
            sqlparams.put("userid",  "%"+userid+"%");
        }
        if(!msg.isNull("username"))
        {
            String username = (String) msg.getParam("username");
            whereStr =whereStr +" and m.username like ? " ;
            sqlparams.put("username",  "%"+username+"%");
        }
        sql =sql+fromSql+whereStr+orderStr+limitStr;
        TLMsg qmsg = createMsg().setAction(DB_QUERY).setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
       TLMsg resultMsg= putMsg(table, qmsg);
       if( page ==1)
       {
           countsql =countsql +fromSql+whereStr;
           TLMsg countmsg = createMsg().setAction(DB_QUERY).setParam(DB_P_SQL, countsql)
                   .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP)
                   .setParam(DB_P_PARAMS, sqlparams);
           TLMsg returnMsg= putMsg(table, countmsg);
           Map<String,Object> map = (Map<String, Object>) returnMsg.getParam(DB_R_RESULT);
           resultMsg.addMap(map) ;
       }
       return resultMsg ;
    }

    protected TLMsg getUserByRole(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("roleid");
        String sql =" select userid,username,roleid,status,registtime,creator from [table] where roleid=? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("roleid", roleid);
        TLMsg qmsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAPLIST)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, qmsg);
    }

    protected TLMsg countByRole(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("roleid");
        String sql =" select count(*) as number from [table] where roleid=? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("roleid", roleid);
        TLMsg qmsg = createMsg().setAction(DB_QUERY)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_RESULTTYPE, TLDataBase.RESULT_TYPE.MAP)
                .setParam(DB_P_PARAMS, sqlparams);
        TLMsg resultMsg= putMsg(table, qmsg);
        Map infos = (Map)resultMsg.getParam(DB_R_RESULT);
        return  createMsg().addArgs(infos);
    }

    protected TLMsg changePassword(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        if(userid==null || userid.isEmpty())
            return  null ;
        String passwdmd5 = DigestUtils.md5Hex(msg.getParam("password") + userid);
        String sql ="update  [table]  set password=?  where userid=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("password", passwdmd5);
        sqlparams.put("userid", userid);
        TLMsg idmsg=createMsg().setAction(DB_UPDATE).setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, idmsg);
    }

    protected TLMsg updateLoginState(Object fromWho, TLMsg msg) {
        Date date = new Date();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String dateStr = format.format(date);
        String userid = (String) msg.getParam("userid");
        if(userid==null || userid.isEmpty())
            return  null ;
        String idsql ="update  [table]  set loginip=? ,logintime=? where userid=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("loginip", msg.getParam("loginip"));
        sqlparams.put("logintime", dateStr);
        sqlparams.put("userid", userid);
        TLMsg idmsg=createMsg().setAction(DB_UPDATE).setParam(DB_P_SQL,idsql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, idmsg);
    }

    protected TLMsg updateState(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam(USER_P_USERID);
        if(userid==null || userid.isEmpty())
            return  null ;
        int status ;
        if(msg.isNull("status"))
            status = 1;
        else
            status = (int) msg.getParam("status");
        String idsql ="update  [table]  set status=? where userid=?";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("status", status);
        sqlparams.put("userid", userid);
        TLMsg idmsg=createMsg().setAction(DB_UPDATE).setParam(DB_P_SQL,idsql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, idmsg);
    }
    protected TLMsg findUser(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        if(userid==null || userid.isEmpty())
            return  null ;
        String sql =" select username ,password ,status ,roleid from [table] where userid=? ";
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("userid", userid);
        TLMsg qmsg = createMsg().setAction("query")
                .setParam("sql", sql)
                .setParam("resultType", TLDataBase.RESULT_TYPE.MAP)
                .setParam("params", sqlparams);
        TLMsg resultMsg= putMsg(table, qmsg);
        Map infos = (Map)resultMsg.getParam("result");
        if(infos ==null || infos.isEmpty())
            return null ;
        return  createMsg().addArgs(infos);
    }
    protected TLMsg addUser(Object fromWho, TLMsg msg) {
        Date date = new Date();
        String creator= (String) msg.getParam("creator");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String dateStr = format.format(date);
        String sql = "insert into  [table] (userid,username,password,roleid,creator,registtime) values(?,?,?,?,?,?)";
        String userid = (String) msg.getParam("userid");
        String passwdmd5 = DigestUtils.md5Hex(msg.getParam("password") + userid);
        LinkedHashMap<String, Object> sqlparams = new LinkedHashMap<>();
        sqlparams.put("userid", userid);
        sqlparams.put("username", msg.getParam("username"));
        sqlparams.put("password", passwdmd5);
        sqlparams.put("roleid", msg.getParam("roleid"));
        sqlparams.put("creator", creator);
        sqlparams.put("registtime", dateStr);
        TLMsg insertmsg = createMsg().setAction(DB_INSERT)
                .setParam(DB_P_SQL, sql)
                .setParam(DB_P_PARAMS, sqlparams);
        return putMsg(table, insertmsg);
    }


}
