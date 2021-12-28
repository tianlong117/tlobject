package cn.tianlong.tlobject.db.dbdata;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.utils.TLDataUtils;
import java.util.LinkedHashMap;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

public class TLModuleFieldInDb extends TLBaseModule {
    protected String tableName ="dataunits";
    public TLModuleFieldInDb(){
        super();
    }
    public TLModuleFieldInDb(String name ){
        super(name);
    }
    public TLModuleFieldInDb(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null && params.get("tableName") != null)
            tableName = params.get("tableName");
    }
    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "getFieldValue":
                returnMsg =getFieldValue( fromWho,  msg);
                break;
            default:
                ;
        }
        return returnMsg;
    }
    protected TLMsg getFieldValue(Object fromWho, TLMsg msg)
    {
        String fieldName =msg.getStringParam("fieldName",null);
        if(fieldName ==null)
            return null ;
        String fieldType = msg.getStringParam("fieldType","") ;
        Object value ;
        if(  fieldType.indexOf("Map") !=-1)
        {
            Object dataIndb = TLDataInDbUtils.getHashMapFromDB(fieldName,moduleFactory,0L);
            value =TLDataUtils.LinkMapToMap((LinkedHashMap) dataIndb,fieldType) ;
        }
        else if(fieldType.indexOf("List") !=-1)
            value= TLDataInDbUtils.getListFromDB(fieldName,moduleFactory,0L);
        else
            value= TLDataInDbUtils.getVariableFromDB(fieldName,moduleFactory,0L);
        return createMsg().setParam("value",value) ;
    }

}
