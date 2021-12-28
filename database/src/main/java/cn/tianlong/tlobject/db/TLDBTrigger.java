package cn.tianlong.tlobject.db;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public abstract class TLDBTrigger extends TLBaseModule {
    protected String tableName ;
    protected String database ;
    public TLDBTrigger() {
        super();
    }
    public TLDBTrigger(String name ) {
        super(name);
    }
    public TLDBTrigger(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
        autoConfig=false ;
    }
    @Override
    protected void initProperty() {
        if(params !=null )
            if( params.get("tableName")!=null && !params.get("tableName").isEmpty())
               tableName=params.get("tableName");
          if( params.get("database")!=null && !params.get("database").isEmpty())
              database=params.get("database");
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }

    protected TLBaseDataUnit getTable(String tableName){
         String mtable ="table_"+tableName;
        if (modules.get(mtable) != null)
            return (TLBaseDataUnit) modules.get(mtable);
        TLMsg tmsg = createMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME,tableName);
        TLMsg returnmsg=putMsg(database,tmsg);
        TLBaseDataUnit tb = (TLBaseDataUnit) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        modules.put(mtable,tb);
        return tb;
    }
    protected TLBaseDataUnit getTableForCopy(String tableName ,String copyTable){
        String mtable ="table_"+tableName;
        if (modules.get(mtable) != null)
            return (TLBaseDataUnit) modules.get(mtable);
        TLMsg tmsg = createMsg().setAction(DB_GETTABLE).setParam(DB_P_TABLENAME,tableName).setParam(DB_P_COPYTABLE,copyTable);
        TLMsg returnmsg=putMsg(database,tmsg);
        TLBaseDataUnit tb = (TLBaseDataUnit) returnmsg.getParam(TLObjectFactory.FACTORY_R_MODULEINSTANCE);
        modules.put(mtable,tb);
        return tb;
    }
    protected TLMsg changeTable(String table, TLMsg msg) {
        if(table==null || table.isEmpty())
            return null;
        TLBaseDataUnit tb=getTable(table);
        TLMsg returnmsg=putMsg(tb, msg);
        if(returnmsg!=null)
          return returnmsg.setParam(MODULE_DONEXTMSG,"false");
        else
            return createMsg().setParam(MODULE_DONEXTMSG,"false");
    }
    protected TLMsg changeTable(TLBaseDataUnit table, TLMsg msg) {
        TLMsg returnmsg=putMsg(table, msg);
        if(returnmsg!=null)
            return returnmsg.setParam(MODULE_DONEXTMSG,"false");
        else
            return createMsg().setParam(MODULE_DONEXTMSG,"false");
    }
}
