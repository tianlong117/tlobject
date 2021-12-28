package cn.tianlong.tlobject.db;


import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import java.util.*;


/**
 * 创建日期：2018/3/21 on 8:48
 * 描述:
 * 作者:tianlong
 */

public abstract class TLBaseTriggerForSplitTable extends TLDBTrigger {

    protected String[] splitTables;
    protected String splitKey;
    public TLBaseTriggerForSplitTable() {
        super();
    }
    public TLBaseTriggerForSplitTable(String name ) {
        super(name);
    }
    public TLBaseTriggerForSplitTable(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty() {
         super.initProperty();
        if(params !=null  )
        {
           if(params.get("splitTables")!=null && !params.get("splitTables").isEmpty())
           {
               splitTables = params.get("splitTables").split(";");
               for(int i=0;i< splitTables.length;i++) {
                   splitTables[i] = splitTables[i].trim();
               }
           }
            if(params.get("splitKey")!=null && !params.get("splitKey").isEmpty()){
                splitKey=params.get("splitKey");
            }
        }
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        if(tableName == null)
            tableName=((TLTable)fromWho).getName();
        TLMsg nmsg=(TLMsg) msg.getParam(DOWITHMAG);
        if( nmsg ==null)
            nmsg =msg ;
        String table = (String) nmsg.getParam("dbtable");
        if(table !=null && !table.isEmpty())
        {
            msg.removeParam("dbtable");
            return changeTable(table,nmsg);
        }
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "onInsert":
                returnMsg=onInsert( fromWho,  msg);
                break;
            case "onUpdate":
                returnMsg=onUpdate( fromWho,  msg);
                break;
            case "onDelete":
                returnMsg=onDelete( fromWho,  msg);
                break;
            case "onQuery":
                returnMsg=onQuery( fromWho,  msg);
                break;
            case "onBatch":
                returnMsg=onBatch( fromWho,  msg);
                break;
            default:
                returnMsg=null;
        }
        return returnMsg;
    }

    protected TLMsg onQuery(Object fromWho, TLMsg msg) {
        return  selectTable(fromWho,msg);
    }

    protected TLMsg onDelete(Object fromWho, TLMsg msg) {
        return  selectTable(fromWho,msg);
    }

    protected TLMsg onUpdate(Object fromWho, TLMsg msg) {
        return  selectTable(fromWho,msg);
    }

    protected TLMsg onInsert(Object fromWho, TLMsg msg) {
       return  selectTable(fromWho,msg);
    }

    protected TLMsg onBatch(Object fromWho, TLMsg msg) {
        return  null;
    }
    protected abstract TLMsg selectTable(Object fromWho, TLMsg msg);
    protected TLMsg doAllTable(TLMsg msg)   {
        ArrayList<Object> totaldatas=new ArrayList<>();
        if (splitTables ==null || splitTables.length==0)
            return null ;
        TLMsg returnMsg;
        for(int i=0 ; i<splitTables.length ;i++)
        {
           TLMsg dbMsg =createMsg().copyFrom(msg);
            returnMsg =changeTable(splitTables[i],dbMsg);
            List splistDatas;
            if(returnMsg!=null && returnMsg.getParam("result")!=null && returnMsg.getParam("result") instanceof List)
            {
                splistDatas = (List) returnMsg.getParam("result");
                if(splistDatas !=null &&  !splistDatas.isEmpty())
                   totaldatas.addAll(splistDatas);
            }
        }
        if(!totaldatas.isEmpty() && msg.getParam(DB_P_ORDERBY)!=null)
        {
            List orderDatas=dataToOrder(totaldatas,msg.getParam(DB_P_ORDERBY));
            return createMsg().setParam("result",orderDatas).setParam(MODULE_DONEXTMSG,"false");
        }
        return createMsg().setParam("result",totaldatas).setParam(MODULE_DONEXTMSG,"false");
    }

    private List dataToOrder(ArrayList<Object> totaldatas, Object param) {
        Map<Object ,Map<String,Object>> orderData = new TreeMap<>();
        for (int i = 0; i < totaldatas.size(); i++) {
            Map<String,Object> unit= (Map<String, Object>) totaldatas.get(i);
            String orderParam =unit.get(param).toString();
            if(orderData.containsKey(orderParam))
                orderParam=orderParam+i;
            orderData.put(orderParam,unit);
        }
        List<Map>  orderList = new ArrayList<>();
        for(Map value : orderData.values()){
           orderList.add(value);
        }
        return orderList ;
    }

}
