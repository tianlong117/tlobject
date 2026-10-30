package cn.tianlong.tlobject.db;


import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLDataUtils;

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

    public TLBaseTriggerForSplitTable(String name) {
        super(name);
    }

    public TLBaseTriggerForSplitTable(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null) {
            if (params.get("splitTables") != null && !params.get("splitTables").isEmpty()) {
                splitTables = params.get("splitTables").split(";");
                for (int i = 0; i < splitTables.length; i++) {
                    splitTables[i] = splitTables[i].trim();
                }
            }
            if (params.get("splitKey") != null && !params.get("splitKey").isEmpty()) {
                splitKey = params.get("splitKey");
            }
        }
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        // fromWho 不保证是 TLTable（触发器也可能被其他模块转发调用）：盲转会 CCE
        if (tableName == null && fromWho instanceof TLTable)
            tableName = ((TLTable) fromWho).getName();
        TLMsg nmsg = (TLMsg) msg.getSystemParam(DOWITHMSG);
        if (nmsg == null)
            nmsg = msg;
        String table = (String) nmsg.getParam("dbtable");
        if (table != null && !table.isEmpty()) {
            msg.removeParam("dbtable");
            return changeTable(table, nmsg);
        }
        TLMsg returnMsg;
        switch (msg.getAction()) {
            case "onInsert":
                returnMsg = onInsert(fromWho, msg);
                break;
            case "onUpdate":
                returnMsg = onUpdate(fromWho, msg);
                break;
            case "onDelete":
                returnMsg = onDelete(fromWho, msg);
                break;
            case "onQuery":
                returnMsg = onQuery(fromWho, msg);
                break;
            case "onBatch":
                returnMsg = onBatch(fromWho, msg);
                break;
            default:
                returnMsg = otherMsgAction( fromWho,  msg);
        }
        return returnMsg;
    }

    protected TLMsg otherMsgAction(Object fromWho, TLMsg msg) {
        return null ;
    }

    protected TLMsg onQuery(Object fromWho, TLMsg msg) {
        return selectTable(fromWho, msg);
    }

    protected TLMsg onDelete(Object fromWho, TLMsg msg) {
        return selectTable(fromWho, msg);
    }

    protected TLMsg onUpdate(Object fromWho, TLMsg msg) {
        return selectTable(fromWho, msg);
    }

    protected TLMsg onInsert(Object fromWho, TLMsg msg) {
        return selectTable(fromWho, msg);
    }

    protected TLMsg onBatch(Object fromWho, TLMsg msg) {
        return null;
    }

    protected abstract TLMsg selectTable(Object fromWho, TLMsg msg);

    protected TLMsg doAllTable(TLMsg msg) {

        if (splitTables == null || splitTables.length == 0)
            return null;
        if (splitTables.length == 1)
            return changeTable(splitTables[0], msg);
        ArrayList<TLMsg> msgList = new ArrayList<>();
        for (int i = 0; i < splitTables.length; i++) {
            TLMsg dbMsg = createMsg().copyFrom(msg);
            TLBaseDataUnit tb = getTable(splitTables[i]);
            dbMsg.setSystemParam(MSG_P_TOWHO, tb);
            msgList.add(dbMsg);
        }
        TLMsg returnMsg = putMsgGroupByThread(msgList, 60 * 1000);   // 多表并行查询
        ArrayList totaldatas = new ArrayList<>();
        if (returnMsg == null)
            return createMsg().setParam(RESULT, false)
                    .setParam("error", "分表并行查询失败")
                    .setSystemParam(MODULE_DONEXTMSG, false);
        List<TLMsg> dbResultList = (List<TLMsg>) returnMsg.getParam(RESULT, List.class);
        int failedTables = 0;
        if (dbResultList != null) {
            for (TLMsg rmsg : dbResultList) {
                // 超时/提交失败的分表在 RESULT 里是 null，直接解引用会 NPE
                if (rmsg == null) {
                    failedTables++;
                    continue;
                }
                Object dbresult = rmsg.getParam(DB_R_RESULT);
                if(dbresult !=null){
                    if (dbresult instanceof List)
                        totaldatas.addAll((List) dbresult);
                    else
                        totaldatas.add(dbresult);
                }
            }
        }
        Object uncompleted = returnMsg.getParam(UNCOMPLETEDMSGLIST);
        if (uncompleted instanceof List && !((List<?>) uncompleted).isEmpty())
            failedTables = Math.max(failedTables, ((List<?>) uncompleted).size());
        if (failedTables > 0) {
            // 分表是并行的：部分表超时/失败时结果不完整，必须留痕而不是静默少数据
            putLog("分表查询未完成 " + failedTables + " 张表，返回数据可能不完整", LogLevel.WARN, "doAllTable");
            if (totaldatas.isEmpty())
                return createMsg().setParam(RESULT, false)
                        .setParam("error", "分表查询全部失败")
                        .setSystemParam(MODULE_DONEXTMSG, false);
        }
        if (!totaldatas.isEmpty() && !msg.isNull(DB_P_ORDERBY)) {
            Object orderDatas = null;
            if (totaldatas instanceof List)
                orderDatas = TLDataUtils.listMapDataByOrder((ArrayList) totaldatas, (String) msg.getParam(DB_P_ORDERBY));
            else if (totaldatas instanceof Map)
                orderDatas = TLDataUtils.mapDataToOrder((Map) totaldatas);
            return createMsg().setParam(DB_R_RESULT, orderDatas).setSystemParam(MODULE_DONEXTMSG, false);
        }
        return createMsg().setParam(DB_R_RESULT, totaldatas).setSystemParam(MODULE_DONEXTMSG, false);
    }


}
