package cn.tianlong.tlobject.base;


import cn.tianlong.tlobject.utils.TLDataUtils;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 创建日期：2018/3/4 on 8:47
 * 描述:
 * 作者:tianlong
 */
/**
 *  消息对象编程的消息定义类。消息对象通过发送消息类来传递指令。
 *
 */
public class TLMsg implements Serializable , Cloneable{
    private static final long serialVersionUID = 2631590509760908280L;
    protected String source;  //消息产生源
    protected String previous;  //上一个处理消息的对象
    protected String nowObject;  //当前处理消息的对象
    protected String destination;  //消息目的地
    protected HashMap<String, Object> args;   //消息参数
    protected HashMap<String, Object> systemArgs;   //系统消息参数
    protected String action;
    protected String msgId ;       //消息id，对象可根据消息id 查询信息路由表或信息表中获取相关处理信息
    protected TLMsg  nextMsg=null;          //形成msg链，下一个msg
    protected Boolean waitFlag =true;   //异步put标志，默认非异步
    public TLMsg( ){

    }
    public TLMsg(String action){
        this.action = action;
    }
    public TLMsg(String action, HashMap args){
        this.action = action;
        if(args !=null)
           this.args=args;
    }
    public TLMsg(String action, HashMap args, String msgId){
        this.action = action;
        if(args !=null)
           this.args=args;
        this.msgId=msgId;
    }

    public TLMsg(String action, String param, Object value){
        this.action = action;
        args.put(param,value);
    }
    public String getSource()
    {
        return source ;
    }
    public TLMsg setSource(String source)
    {
        this.source=source ;
        return this;
    }
    public String getNowObject()
    {
        return nowObject ;
    }
    public TLMsg setNowObjcect(String nowObjcect)
    {
        this.nowObject=nowObjcect ;
        return this;
    }
    public Boolean getWaitFlag()
    {
        return waitFlag ;
    }
    public TLMsg setWaitFlag(Boolean value)
    {
        this.waitFlag=value ;
        return this;
    }
    public String getPrevious()
    {
        return previous ;
    }
    public TLMsg setPrevious(String previous)
    {
        this.previous=previous;
        return this;
    }

    public String getDestination()
    {
        return destination ;
    }
    public TLMsg setDestination(String destination)
    {
        if(destination ==null || destination.isEmpty())
        {
            this.destination=null ;
            return this ;
        }
        this.destination=destination ;
        return this;
    }
    public String getMsgId()
    {
        return msgId ;
    }
    public TLMsg setMsgId(String msgId)
    {
        this.msgId=msgId ;
        return this;
    }
    public TLMsg getNextMsg()
    {
        return nextMsg;
    }
    public TLMsg setNextMsg(TLMsg nextMsg)
    {
        this.nextMsg=nextMsg ;
        return this;
    }
    public String getAction()
    {
        return action;
    }
    public TLMsg setAction(String action)
    {
        this.action = action;
        return this;
    }
    public TLMsg setSystemArgs(HashMap args)
    {
        this.systemArgs=args ;
        return  this ;
    }
    public TLMsg addSystemArgs(Map<String, Object> params)
    {
        if(params==null)
            return  this ;
        if(systemArgs ==null)
            systemArgs= new HashMap<>() ;
        systemArgs.putAll(params);
        return  this ;
    }
    public TLMsg setSystemParam(String param ,Object value)
    {
        if(systemArgs ==null)
            systemArgs= new HashMap<>() ;
        systemArgs.put(param,value);
        return this;
    }
    public Object getSystemParam(String param,Object defaultValue)
    {
        if(systemArgs ==null)
            return defaultValue ;
        Object value = systemArgs.get(param) ;
        if(value ==null)
            return defaultValue ;
        return value ;
    }
    public Object  getSystemParam(String param)
    {
        if(systemArgs ==null)
            return null ;
        return systemArgs.get(param) ;
    }
    public HashMap getSystemArgs()
    {
        return systemArgs ;
    }
    public boolean systemParamIsNull(String param )
    {
        if(systemArgs ==null ||systemArgs.isEmpty())
            return true ;
        Object value = systemArgs.get(param) ;
        if(value==null)
            return  true ;
        return false ;
    }
    public Boolean containsSystemParam(String param)
    {
        if(systemArgs ==null ||systemArgs.isEmpty())
            return false ;
        return systemArgs.containsKey(param) ;
    }
    public Object removeSystemParam(String param)
    {
        if(systemArgs ==null)
            return null ;
        return systemArgs.remove(param) ;
    }
    public TLMsg clearSystemArgs()
    {
        if(systemArgs !=null)
            systemArgs.clear();
        return this;
    }
    public HashMap getArgs()
    {
        return args ;
    }
    public TLMsg setArgs(HashMap args)
    {
        if(args !=null)
            this.args=args ;
        return  this ;
    }
    public  TLMsg  addMap(Map map){
        for(Object key : map.keySet() ){
            setParam(key.toString(),map.get(key));
        }
        return this ;
    }
    public TLMsg addArgs(Map<String, Object> params)
    {
        if(params==null)
            return  this ;
        if(args ==null)
            args= new HashMap<>() ;
        args.putAll(params);
        return  this ;
    }
    public Object getParam(String param)
    {
       if(args ==null)
           return null ;
        return args.get(param) ;
    }
    public Object getAndRemoveParam(String param)
    {
        if(args ==null)
            return null ;
        if(args.containsKey(param))
        {
            Object value =args.get(param);
            args.remove(param);
            return value;
        }
        return null  ;
    }
    public Object getAndRemoveParam(String param,Object defaultValue)
    {
        if(args ==null)
            return defaultValue ;
        Object value = null;
        if(args.containsKey(param))
        {
            value =args.get(param);
            args.remove(param);
        }
        if(value ==null)
            return defaultValue ;
        return value ;
    }
    public Object getParam(String param,Object defaultValue)
    {
        if(args ==null)
            return defaultValue ;
        Object value = args.get(param) ;
        if(value ==null)
            return defaultValue ;
        return value ;
    }
    public Boolean containsParam(String param)
    {
        if(args ==null ||args.isEmpty())
            return false ;
        return args.containsKey(param) ;
    }
    public Object removeParam(String param)
    {
        if(args ==null)
            return null ;
        return args.remove(param) ;
    }
    public TLMsg setParam(String param ,Object value)
    {
        if(args ==null)
            args= new HashMap<>() ;
        args.put(param,value);
        return this;
    }
    public Object setParamIfAbsent(String param ,Object value)
    {
        if(args ==null)
            args= new HashMap<>() ;
        return args.putIfAbsent(param,value);
    }
    public TLMsg setParam(Map<String,Object> params)
    {
        if(params ==null)
            return this ;
        if(args ==null)
            args= new HashMap<>() ;
        args.putAll(params);
        return this;
    }
    public TLMsg clearParam()
    {
        if(args !=null)
            args.clear();
        return this;
    }
    public TLMsg copyParam(String param ,TLMsg msg)
    {

        if(msg.containsParam(param))
        {
            if(args ==null)
                args= new HashMap<>() ;
            args.put(param,msg.getParam(param));
        }
        return this;
    }
    public TLMsg copyParams(String[] paramsKey ,TLMsg msg)
    {
        if(args ==null)
            args= new HashMap<>() ;
       if(paramsKey==null || paramsKey.length==0)
           addArgs(msg.getArgs());
        else {
           for (int i=0 ;i<paramsKey.length;i++)
           {
               if(msg.containsParam(paramsKey[i]))
                   args.put(paramsKey[i],msg.getParam(paramsKey[i]));
           }
       }
        return this;
    }
    public boolean isNull(String param )
    {
        if(containsParam(param) == false)
            return  true;
        Object value = getParam(param);
        if(value==null)
            return  true ;
        return false ;
    }
    public Long getLongParam(String param ,Long defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getLongParam(value,defaultValue);
    }
    public Double getDoubleParam(String param ,Double defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getDoubleParam(value,defaultValue);
    }
    public String getStringParam(String param ,String defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getStringParam(value,defaultValue);
    }
    public int getIntParam(String param ,int defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getIntParam(value,defaultValue);
    }
    public boolean getBooleanParam(String param ,boolean defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getBooleanParam(value,defaultValue);
    }
    public byte getByeParam(String param ,byte defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getByeParam(value,defaultValue);
    }
    public Map getMapParam(String param ,Map defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getMapParam(value,defaultValue);
    }
    public List getListParam(String param , List defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getListParam(value,defaultValue);
    }
    public Set getSetParam(String param , Set defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getSetParam(value,defaultValue);
    }
    public Object getArrayParam(String param , Object defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.getArrayParam(value,defaultValue);
    }
    public boolean parseBoolean(String param ,boolean defaultValue)
    {
        Object value = getParam(param);
        return TLDataUtils.parseBoolean(value,defaultValue);
    }
    public TLMsg clear()
    {
        this.action ="";
        this.args.clear();
        this.msgId=null;
        this.nextMsg=null;
        this.destination=null;
        this.waitFlag=true;
        return this ;
    }
    public TLMsg copyFrom(TLMsg msg){
        action =msg.getAction();
        addArgs(msg.getArgs());
        msgId=msg.getMsgId();
        nextMsg=msg.getNextMsg();
        destination=msg.getDestination();
        waitFlag=msg.getWaitFlag();
        source=msg.getSource();
        previous=msg.getPrevious();
        return this;
    }
    public TLMsg copyTo(TLMsg nmsg){
        if(nmsg ==null)
            nmsg= new TLMsg();
        nmsg.setAction(action);
        nmsg.addArgs(args);
        nmsg.setDestination(destination);
        nmsg.setSource(source) ;
        nmsg.setWaitFlag(waitFlag);
        nmsg.setNextMsg(nextMsg);
        nmsg.setPrevious(previous);
        return nmsg;
    }
    @Override
    public Object clone() {
        TLMsg copy = null;
        try{
            copy = (TLMsg) super.clone();   //浅复制
        }catch(CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return copy;
    }
}
