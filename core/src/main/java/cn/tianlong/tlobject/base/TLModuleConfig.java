package cn.tianlong.tlobject.base;


import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.utils.TLMsgUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 创建日期：2018/4/11 on 8:50
 * 描述:
 * 作者:tianlong
 */

public  class TLModuleConfig extends TLBaseModule {
    protected String configDir ;
    protected ConcurrentHashMap<String, HashMap<String, String>> modulesClass = new ConcurrentHashMap<>();
    protected ConcurrentHashMap<String, HashMap<String, String>> modulesParams = new ConcurrentHashMap<>();
    protected ConcurrentHashMap<String, HashMap<String, String>> paramsModules = new ConcurrentHashMap<>();
    protected ArrayList<TLMsg> initMsgTable ;          //初始化时的消息队列
    protected ArrayList<TLMsg> startMsgTable ;
    protected HashMap<String, String> params ;
    protected ConcurrentHashMap<String, ArrayList<TLMsg>> beforeMsgTable ;           //前期执行msg
    protected ConcurrentHashMap<String, ArrayList<TLMsg>> afterMsgTable ;          //后期执行msg
    protected ConcurrentHashMap<String, HashMap<String, Object>> msgTable ;  // entry: {"msglist":ArrayList<TLMsg>, "mode":"parallel", ...}
    protected String configFile;
    public TLModuleConfig(String configFile ,String configDir) {
        this.configFile = configFile;
        this.configDir = configDir ;
    }
    public TLModuleConfig() {        ;
    }
    public TLModuleConfig parse(String configFile){
        if(configFile !=null)
            this.configFile=configFile;
         return (TLModuleConfig) init();
    }
    protected TLBaseModule init(){
        InputStream xmlData= setFileInputStream(null);
        if(xmlData ==null)
            return null ;
        try {
            parseconfig(xmlData,null);
        } finally {
            try { xmlData.close(); } catch (IOException ignored) {}
        }
        return  this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        return null;
    }

    public   InputStream setFileInputStream(String file) {
        if(file ==null)
            file =configFile ;
        if (file == null)
            return null;
     //   return TLModuleConfig.class.getResourceAsStream(file);
        InputStream InputStream;
        try {
            InputStream= new FileInputStream(file);
        } catch (FileNotFoundException e) {
           // e.printStackTrace();
            InputStream =TLModuleConfig.class.getResourceAsStream(file);
        }
        return InputStream ;
    }
    public Object parseParamFromConfig (String paramName ){
        return  parseFile(paramName,null) ;
    }
    public void parseFile(File file){
        try (InputStream in = new FileInputStream(file)) {
            parseconfig(in,null);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public Object parseFile (String paramName ,String file){
        if(file ==null)
            file = configFile ;
        InputStream xmlData= setFileInputStream(file);
        try {
            return parseconfig(xmlData,paramName);
        } finally {
            if (xmlData != null) {
                try { xmlData.close(); } catch (IOException ignored) {}
            }
        }
    }
    public ConcurrentHashMap getModulesClass() {
        return modulesClass;
    }
    public ConcurrentHashMap getModulesParams() {
        return modulesParams;
    }
    public ConcurrentHashMap getParamsModules() {
        return paramsModules;
    }
    public HashMap getParams() {
        return params;
    }
    public ArrayList<TLMsg> getInitMsg() {
        return initMsgTable;
    }
    public ArrayList<TLMsg> getStartMsgTable() {
        return startMsgTable;
    }
    public ConcurrentHashMap<String, HashMap<String, Object>> getMsgTable() {
        return msgTable;
    }
    public ConcurrentHashMap getBeforeMsgTable() {
        return beforeMsgTable;
    }
    public ConcurrentHashMap getAfterMsgTable() {
        return afterMsgTable;
    }
    protected Object parseconfig(InputStream xmlData ,String paramName) {
        try {
            Object returnObj =null;
            /* 步骤2：获取xml文件，并给给出XmlPullParser对象*/
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(xmlData, "UTF-8");
            /* 步骤3：通过循环，逐步解析XML，直至xml文件结束，对应第1点和第2点*/
            while (xpp.getEventType() != XmlPullParser.END_DOCUMENT) {
                /* 步骤4：获取目标ListItems的解析，并将之用method：ListItems来处理，对应第3点 */
                if (xpp.getEventType() == XmlPullParser.START_TAG)
                {
                    String tagName = xpp.getName();
                    if(paramName !=null && !tagName.equals(paramName))
                    {
                        xpp.next();
                        continue;
                    }
                   else if (tagName.equals("params")) {
                       HashMap cparams=getParam(xpp,"params");
                       if(cparams !=null)
                       {
                           if(paramName !=null )
                               returnObj =cparams ;
                           else {
                               if(params ==null)
                                   params =new HashMap<>();
                               params.putAll(cparams);
                           }

                       }
                    }
                    else if (tagName.equals("initMsg")) {
                        ArrayList cinitMsgTable=getMsgList(xpp,"initMsg");
                        if(cinitMsgTable !=null)
                        {
                            if(paramName !=null )
                                returnObj =cinitMsgTable ;
                            else
                            {
                                if(initMsgTable==null)
                                    initMsgTable = new ArrayList<>();
                                initMsgTable.addAll(cinitMsgTable);
                            }

                        }

                    }
                    else if (tagName.equals("startMsg")) {
                        ArrayList cstartMsgTable=getMsgList(xpp,"startMsg");
                        if(cstartMsgTable !=null)
                        {
                            if(paramName !=null )
                                returnObj = cstartMsgTable ;
                            else {
                                if(startMsgTable==null)
                                    startMsgTable = new ArrayList<>();
                                startMsgTable.addAll(cstartMsgTable);
                            }

                        }
                    }
                    else if (tagName.equals("msgTable")) {
                        ConcurrentHashMap<String, HashMap<String, Object>> cmsgTable=getMsgTable(xpp,"msgTable","msgid");
                        if(cmsgTable!=null)
                        {
                            if(paramName !=null )
                                returnObj = cmsgTable ;
                            else
                            {
                                if(msgTable ==null)
                                    msgTable =new ConcurrentHashMap<>();
                                msgTable.putAll(cmsgTable);
                            }

                        }
                    }
                    else if (tagName.equals("beforeMsgTable")) {
                        LinkedHashMap<String, ArrayList<TLMsg>> cbeforeMsgTable=getLinkHashMsgList(xpp,"beforeMsgTable","action");
                        if(cbeforeMsgTable !=null)
                        {
                            if(paramName !=null )
                                returnObj =cbeforeMsgTable ;
                            else {
                                if(beforeMsgTable ==null)
                                    beforeMsgTable =new ConcurrentHashMap<>();
                                beforeMsgTable.putAll(cbeforeMsgTable);
                            }

                        }
                    }
                    else if (tagName.equals("afterMsgTable")) {
                        LinkedHashMap<String, ArrayList<TLMsg>> cafterMsgTable= getLinkHashMsgList(xpp,"afterMsgTable","action");
                        if(cafterMsgTable !=null)
                        {
                            if(paramName !=null )
                                returnObj =  cafterMsgTable ;
                            else{
                                if(afterMsgTable ==null)
                                    afterMsgTable =new ConcurrentHashMap<>();
                                afterMsgTable.putAll(cafterMsgTable);
                            }

                        }
                    }
                    else if (tagName.equals("modules")) {
                        HashMap cmodulesClass = getHashMap(xpp, "modules", "module");
                        if(cmodulesClass !=null)
                        {
                            if(paramName !=null )
                                returnObj =  cmodulesClass ;
                            else {
                                modulesClass.putAll(cmodulesClass);
                            }
                        }
                    }
                    else if (tagName.equals("modulesParams")) {
                        HashMap cmodulesParams= getHashMap(xpp, "modulesParams", "module");
                        if(cmodulesParams !=null)
                        {
                            if(paramName !=null )
                                returnObj =  cmodulesParams ;
                            else {
                                modulesParams.putAll(cmodulesParams);
                            }

                        }
                    }
                    else if (tagName.equals("paramsModules")) {
                        HashMap cparamModules= getHashMap(xpp, "paramsModules", "param");
                        if(cparamModules !=null)
                        {
                            if(paramName !=null )
                                returnObj =  cparamModules ;
                            else {
                                paramsModules.putAll(cparamModules);
                            }

                        }
                    }
                    else if (tagName.equals("include")) {
                       include(xpp,"include");
                    }
                    else
                    {
                        myConfig(xpp);
                        returnObj= moduleConfig(xpp,paramName) ;
                    }
                    if(paramName !=null && tagName.equals(paramName))
                        return  returnObj ;
                }
               xpp.next();
            }
        } catch (XmlPullParserException e) {
            putLog("解析配置错误,Xml解析错误:"+configFile,LogLevel.ERROR);
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
            putLog("解析配置错误，IO错误:"+configFile,LogLevel.ERROR);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            putLog("解析配置错误:"+configFile,LogLevel.ERROR);
        }
        return null ;
    }

    protected Object moduleConfig(XmlPullParser xpp, String paramName) {
        return null ;
    }

    protected  void   include(XmlPullParser xpp, String tag) throws IOException, XmlPullParserException {
        String[] sysunits ={"modules","params","initMsg","modulesParams","paramsModules","msgTable","beforeMsgTable","afterMsgTable"};
        TLModuleConfig includeConfig = null;
        String[] units = null;
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            String name =xpp.getAttributeName(i);
            if (name.equals("file")) {
                String fileName = xpp.getAttributeValue(i);
                fileName =moduleFactory.getConfigRealPath(fileName) ;
                if (fileName ==null)
                    return;
                includeConfig = new TLModuleConfig(fileName,configDir);
                includeConfig.setFactory(moduleFactory);
                includeConfig.parse(fileName);
            }
            else if(name.equals("includeUnit")){
                String includeUnit = xpp.getAttributeValue(i);
                String[] rawUnits = includeUnit.split(";");
                units = new String[rawUnits.length];
                for(int j=0; j < rawUnits.length; j++) {
                    units[j] = rawUnits[j].trim();
                }
            }
        }
        if(includeConfig ==null)
            return;
        if(units ==null || units.length==0)
            includeParams(includeConfig,sysunits);
        else
            includeParams(includeConfig,units);
    }

    private void includeParams(TLModuleConfig config,String[] units) {
        for(String s :units){
            String[] array =s.split(":");
            String includeUnits =(array.length==1)?null:array[1];
            switch (array[0].trim()) {
                case "modules":
                    String[] includeModules=checkUnit(includeUnits);
                    modulesClass =(ConcurrentHashMap<String, HashMap<String, String>>) mapCopy(
                        modulesClass != null ? modulesClass : new ConcurrentHashMap<>(),
                        config.getModulesClass(),includeModules);
                    break;
                case "modulesParams":
                    String[] includeModulesParams=checkUnit(includeUnits);
                    modulesParams =(ConcurrentHashMap<String, HashMap<String, String>>) mapCopy(
                        modulesParams != null ? modulesParams : new ConcurrentHashMap<>(),
                        config.getModulesParams(),includeModulesParams);
                    break;
                case "paramsModules":
                    String[] includeParamsModules=checkUnit(includeUnits);
                    paramsModules =(ConcurrentHashMap<String, HashMap<String, String>>) mapCopy(
                        paramsModules != null ? paramsModules : new ConcurrentHashMap<>(),
                        config.getParamsModules(),includeParamsModules);
                    break;
                case "params":
                    String[] includeParams=checkUnit(includeUnits);
                    params =(HashMap<String, String>) mapCopy(params,config.getParams(),includeParams);
                    break;
                case "initMsg":
                    if(initMsgTable ==null)
                        initMsgTable=new ArrayList<>();
                    if(config.getInitMsg()!=null)
                        initMsgTable.addAll(config.getInitMsg());
                    break;
                case "startMsg":
                    if(startMsgTable ==null)
                        startMsgTable=new ArrayList<>();
                    if(config.getStartMsgTable()!=null)
                        startMsgTable.addAll(config.getStartMsgTable());
                    break;
                case "msgTable":
                    if(msgTable ==null)
                        msgTable=new ConcurrentHashMap<>();
                    if(config.getMsgTable()!=null)
                        msgTable.putAll(config.getMsgTable());
                    break;
                case "beforeMsgTable":
                    if(beforeMsgTable ==null)
                        beforeMsgTable=new ConcurrentHashMap<>();
                    if(config.getBeforeMsgTable()!=null)
                        beforeMsgTable.putAll(config.getBeforeMsgTable());
                    break;
                case "afterMsgTable":
                    if(afterMsgTable ==null)
                        afterMsgTable=new ConcurrentHashMap<>();
                    if(config.getAfterMsgTable()!=null)
                        afterMsgTable.putAll(config.getAfterMsgTable());
                    break;
                default:
                   ;
            }
        }
    }

    private String[]  checkUnit(String str){
        if(str==null || str.isEmpty())
            return null ;
        String[] array =str.split(",");
        if(array == null || array.length==0)
            return null ;
        for(int i=0 ;i<array.length ; i++)
            array[i]=array[i].trim();
        return array;
    }
    private Map mapCopy( Map map , Map source , String[] keys){
        if(map ==null)
            map =new HashMap();
        if(source ==null)
            return map ;
        if(keys==null || keys.length==0)
            map.putAll(source);
        else{
            for(String key : keys){
                map.put(key,source.get(key));
            }
        }
        return map ;
    }
    protected HashMap<String,String> getMap(XmlPullParser xpp)throws Throwable {

        HashMap<String,String>Map = new HashMap<>();
        if (xpp.getEventType() == XmlPullParser.START_TAG) {
            String name = xpp.getName();
            if (name.equals("Map")) {
                for (int i = 0; i < xpp.getAttributeCount(); i++) {
                    String arrName =xpp.getAttributeName(i) ;
                    String arrValue =xpp.getAttributeValue(i) ;
                    if(arrName !=null)
                      Map.put(arrName, arrValue);
                }
            }
        }
        return  Map;
    }

    protected HashMap<String,HashMap<String,String>> getHashMap(XmlPullParser xpp, String firstTag, String tag)throws Throwable {
        HashMap<String,HashMap<String,String>> hashMap=null;
        String findex = null;
        HashMap<String,String> sonMap = null;
        while (true) {
            xpp.next();
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(firstTag))
                    || xpp.getEventType() == XmlPullParser.END_DOCUMENT)
                break;
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(tag)))
            {
                if (findex != null && sonMap!= null)
                    if(hashMap ==null)
                        hashMap =new HashMap<>();
                    hashMap.put(findex, sonMap);
            }
            if (xpp.getEventType() == XmlPullParser.START_TAG) {
                String name = xpp.getName();
                if (name.equals(tag)) {
                    sonMap = new HashMap<>();
                    // 按属性名取值，不依赖属性顺序（DOM Transformer 会按字母重排）
                    findex = xpp.getAttributeValue(null, "name");
                }
                for (int i = 0; i < xpp.getAttributeCount(); i++) {
                    String attrName = xpp.getAttributeName(i);
                    if (!"name".equals(attrName)) {
                        sonMap.put(attrName, xpp.getAttributeValue(i));
                    }
                }
            }
        }
        return  hashMap;
    }

    protected void myConfig(XmlPullParser xpp) {
    }

    protected  ConcurrentHashMap<String, HashMap<String, Object>> getMsgTable(XmlPullParser xpp, String table, String tag) throws Throwable {
        LinkedHashMap<String, HashMap<String, Object>> linkedHashMap =getLinkHashMsgListWithParams(xpp,table,tag);
        if(linkedHashMap ==null)
            return null ;
        ConcurrentHashMap<String, HashMap<String, Object>> msgTable=new ConcurrentHashMap<>();
        msgTable.putAll(linkedHashMap);
        return  msgTable;
    }
    /** 与 getLinkHashMsgList 同构，额外解析 <msgid> 的运行参数（mode/waitTime 等），存入 entry map */
    private LinkedHashMap<String, HashMap<String, Object>> getLinkHashMsgListWithParams(XmlPullParser xpp, String table, String tag) throws Throwable {
        LinkedHashMap<String, HashMap<String, Object>> msgTable=null;
        HashMap<String, String> params = null;   // 当前 msgid 的额外属性（mode 等）
        String msgid = null;
        ArrayList<TLMsg> msglist = null;
        while (true) {
            xpp.next();
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(table))
                    || xpp.getEventType() == XmlPullParser.END_DOCUMENT)
                break;
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(tag)))
            {
                if (msgid != null && msglist != null) {
                    if(msgTable ==null)
                        msgTable =new LinkedHashMap<>();
                    HashMap<String, Object> entry = new HashMap<>();
                    entry.put("msglist", msglist);
                    if (params != null)
                        entry.putAll(params);
                    msgTable.put(msgid, entry);
                }
                params = null;
            }
            if (xpp.getEventType() == XmlPullParser.START_TAG) {
                String name = xpp.getName();
                if (name.equals(tag)) {
                    msglist = new ArrayList<>();
                    // 读全部属性：第一个→msgid（key），其余→运行参数（mode/waitTime 等）
                    msgid = xpp.getAttributeValue(0);
                    if (xpp.getAttributeCount() > 1) {
                        params = new HashMap<>();
                        for (int i = 1; i < xpp.getAttributeCount(); i++) {
                            String key = xpp.getAttributeName(i);
                            String value = xpp.getAttributeValue(i);
                            if (value != null && !value.isEmpty())
                                params.put(key, value);
                        }
                    }
                }
                if (name.equals("msg")) {
                    TLMsg msg = getMsg(xpp);
                    if(msg !=null)
                        msglist.add(msg);
                }
            }
        }
        return  msgTable;
    }
    /** 原版：只解析 msgid→msglist，不读 msgid 的运行参数（向后兼容，供 paramsTable 等非 msgTable 段使用） */
    protected  LinkedHashMap<String, ArrayList<TLMsg>> getLinkHashMsgList(XmlPullParser xpp, String table, String tag) throws Throwable {
        LinkedHashMap<String, ArrayList<TLMsg>> msgTable=null;
        String msgid = null;
        ArrayList<TLMsg> msglist = null;
        while (true) {
            xpp.next();
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(table))
                    || xpp.getEventType() == XmlPullParser.END_DOCUMENT)
                break;
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(tag)))
            {
                if (msgid != null && msglist != null)
                    if(msgTable ==null)
                        msgTable =new LinkedHashMap<>();
                msgTable.put(msgid, msglist);
            }
            if (xpp.getEventType() == XmlPullParser.START_TAG) {
                String name = xpp.getName();
                if (name.equals(tag)) {
                    msglist = new ArrayList<>();
                    msgid = xpp.getAttributeValue(0);
                }
                if (name.equals("msg")) {
                    TLMsg msg = getMsg(xpp);
                    if(msg !=null)
                        msglist.add(msg);
                }
            }
        }
        return  msgTable;
    }

    protected  HashMap<String, String>  getParam(XmlPullParser xpp, String tag) throws IOException, XmlPullParserException {
        HashMap<String, String> params=null;
        while (true) {
            xpp.next();
			/*<ListItems> ...</ListItems>的内容已经检索完毕，或者文件结束，都退出处理*/
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(tag))
                    || xpp.getEventType() == XmlPullParser.END_DOCUMENT)
                break;
            if (xpp.getEventType() == XmlPullParser.START_TAG) {
				/*读出属性的名字和数值*/
                if(params ==null)
                    params=new HashMap<>();
                String key = xpp.getName();
                String value = xpp.getAttributeValue(0);
                params.put(key, value);
            }
        }
        return  params;
    }

    protected ArrayList<TLMsg> getMsgList(XmlPullParser xpp, String tag) throws Throwable {
        ArrayList<TLMsg> fMsgTable=null;
        while (true) {
            xpp.next();
			/*<ListItems> ...</ListItems>的内容已经检索完毕，或者文件结束，都退出处理*/
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(tag))
                    || xpp.getEventType() == XmlPullParser.END_DOCUMENT)
                break;
            if (xpp.getEventType() == XmlPullParser.START_TAG) {

                String name = xpp.getName();
                if (name.equals("msg")) {
                    TLMsg msg = getMsg(xpp);
                    if(msg !=null)
                    {
                        if(fMsgTable ==null)
                            fMsgTable =new ArrayList<>();
                        fMsgTable.add(msg);
                    }
                }
            }
        }
        return  fMsgTable;
    }
    protected  ArrayList<HashMap<String,String>>  getListMap(XmlPullParser xpp, String tag) throws Throwable {
        ArrayList<HashMap<String,String>> fMsgTable=null;
        while (true) {
            xpp.next();
            /*<ListItems> ...</ListItems>的内容已经检索完毕，或者文件结束，都退出处理*/
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals(tag))
                    || xpp.getEventType() == XmlPullParser.END_DOCUMENT)
                break;
            if (xpp.getEventType() == XmlPullParser.START_TAG) {

                String name = xpp.getName();
                if (name.equals("Map")) {
                   HashMap<String ,String> map = getMap(xpp);
                    if(map !=null && !map.isEmpty())
                    {
                        if(fMsgTable ==null)
                            fMsgTable =new ArrayList<>();
                        fMsgTable.add(map);
                    }
                }
            }
        }
        return  fMsgTable;
    }

    protected TLMsg getMsg(XmlPullParser xpp) throws Throwable {
        TLMsg msg =null;
        while (true) {
            if ((xpp.getEventType() == XmlPullParser.END_TAG && xpp.getName().equals("msg")))
                return msg;
            if (xpp.getEventType() == XmlPullParser.START_TAG) {
                String name = xpp.getName();
                if (name.equals("msg")) {
                    msg = new TLMsg();
                    for (int i = 0; i < xpp.getAttributeCount(); i++)
                    {
                        String key =xpp.getAttributeName(i) ;
                        String value =xpp.getAttributeValue(i) ;
                        if(value==null || value.isEmpty())
                            continue;
                        msg=TLMsgUtils.strToMsg(msg,key,value);
                    }
                }
            }
            xpp.next();
        }
    }

}
