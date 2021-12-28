package cn.tianlong.java.application.webmanager;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.TLDBSqlConditionExpression;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.db.dbdata.ViewTable;
import cn.tianlong.tlobject.servletutils.TLWUrlMap;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.apache.commons.collections.CollectionUtils;

import java.util.*;

import static cn.tianlong.tlobject.servletutils.TLParamString.AUTH_P_DEFAULTSUPERUSER;

public class menuManagerControl extends adminCommon {
    protected String urlMapconfigfile;
    protected String superUser;
    protected String defaultMenusTableName="menus";
    protected String defaultUrlsTableName="urls";
    protected String menusTableName;
    protected String urlsTableName;
    protected BeanTable menusIndb ;
     public menuManagerControl() {
        super();
    }

    public menuManagerControl(String name) {
        super(name);
    }

    public menuManagerControl(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        if(params !=null && params.get("menusTable")!=null)
            menusTableName = params.get("menusTable");
        else
            menusTableName = defaultMenusTableName;
        menusIndb =new BeanTable(menusTableName,"id",true,moduleFactory);
        if(params !=null && params.get("urlsTable")!=null)
            urlsTableName = params.get("urlsTable");
        else
            urlsTableName = defaultUrlsTableName;
        if(params !=null && params.get("urlMapconfigfile")!=null)
            urlMapconfigfile = params.get("urlMapconfigfile");
        superUser =  getModuleParam("adminauth",AUTH_P_DEFAULTSUPERUSER);
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "index":
                index(fromWho, msg);
                break;
            case "menuList":
                menuList(fromWho, msg);
                break;
            case "menuAdd":
                menuAdd(fromWho, msg);
                break;
            case "getUrls":
                getUrls(fromWho, msg);
                break;
            case "menuAddSubmit":
                returnMsg =menuAddSubmit(fromWho, msg);
                break;
            case "menuDelete":
                returnMsg =menuDelete(fromWho, msg);
                break;
            case "menuEdit":
                menuEdit(fromWho, msg);
                break;
            case "getMenuForEdit":
                getMenuForEdit(fromWho, msg);
                break;
            case "menuEditSubmit":
                returnMsg = menuEditSubmit(fromWho, msg);
                break;
            case "loadUrls":
                returnMsg =loadUrls(fromWho, msg);
                break;
            case "loadAppUrls":
                returnMsg =loadAppUrls(fromWho, msg);
                break;
            case "urls":
                urls(fromWho, msg);
                break;
            case "getAllMenus":
                returnMsg =getAllMenus(fromWho, msg);
                break;
            case "getRoleMenus":
                returnMsg =getRoleMenus(fromWho, msg);
                break;
            default:
                putMsg("error", creatOutMsg().setAction("setError").setParam("content", "no action"));
        }
        return returnMsg;
    }

    private void urls(Object fromWho, TLMsg msg) {
        outData odata = creatOutDataMsg("urls");
        putOutData(odata);
    }

    private TLMsg getRoleMenus(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("roleid");
        String[] roleidArray =TLDataUtils.splitStrToArray(roleid,";");
        ViewTable menusIndb =new ViewTable("rolemenus",moduleFactory);
        TLDBSqlConditionExpression  inCondition =new TLDBSqlConditionExpression("rolemenus.roleid",roleidArray,"in",null);
        LinkedHashMap<String,Object> sqlparams= (LinkedHashMap<String, Object>) inCondition.getValue();
        String inSql = inCondition.getSqlStr();
        menusIndb.replaceSql(inSql);
        ArrayList<Map<String, Object>> menuList =  menusIndb.get(sqlparams);
        return createMsg().setParam(RESULT,menuList);
    }

    private TLMsg getAllMenus(Object fromWho, TLMsg msg) {
        ArrayList<Map<String, Object>> menuList =getAllMenus();
        return createMsg().setParam(RESULT,menuList);
    }
    private ArrayList<Map<String, Object>> getAllMenus() {
        String userid =getUserid() ;
        ArrayList<Map<String, Object>> menuList ;
        if(superUser !=null && userid.equals(superUser)){
          //  menuList =  menusIndb.getAll();
            ViewTable menusIndb =new ViewTable("menusview",moduleFactory);
            menuList =  menusIndb.get();
        }
         //
        else
        {
           ArrayList<String> roleidList =getUserRole() ;
            ViewTable menusIndb =new ViewTable("adminmenus",moduleFactory);
            String[] roleidArray = new String[roleidList.size()];
            roleidList.toArray( roleidArray);
            TLDBSqlConditionExpression  inCondition =new TLDBSqlConditionExpression("rolemenus.roleid",roleidArray,"in",null);
            LinkedHashMap<String,Object> sqlparams= (LinkedHashMap<String, Object>) inCondition.getValue();
            sqlparams.put("userid",userid);
            String inSql = inCondition.getSqlStr();
            menusIndb.replaceSql(inSql);
            menuList =  menusIndb.get(sqlparams);
        }
        return menuList ;
    }
    private TLMsg loadAppUrls(Object fromWho, TLMsg msg) {
        String appConfigPath =getAppParams("appConfigPath");
        String urlMapconfigfile =getAppParams("urlMapconfigfile");
        if(appConfigPath !=null && urlMapconfigfile !=null){
            String[] configArray =TLDataUtils.splitStrToArray(urlMapconfigfile,";") ;
            for(int i =0 ;i <configArray.length ; i++)
            {
                String configfile =appConfigPath+configArray[i];
                loadUrls(appConfigPath,configfile) ;
            }
        }else {
            TLMsg urlMsg =createMsg().setAction("getUrlMapTable");
            TLMsg returnMsg =putToAllServer("urlMap",urlMsg);
            if(returnMsg ==null)
                return   putResultToClient(false,"loadUrls","加载失败，app服务器没有启动");
            HashMap<String, ArrayList<TLMsg>> urlMap =returnMsg.getArgs();
            TLMsg sreturnMsg =putToAllServer("urlMap",createMsg().setAction("getPrefixUrl"));
            String prefixUrl = (String) sreturnMsg.getParam("prefixUrl");
            urlMapTableToDb(prefixUrl,  urlMap);
        }
        return   putResultToClient(true,"loadAppUrls","加载成功");
    }
    private TLMsg loadUrls(Object fromWho, TLMsg msg) {

        if(urlMapconfigfile ==null)
            return   putResultToClient(false,"loadUrls","加载失败，没有配置urlMapconfigfile");
      //  String configDir ="../../../../../"+appConfigPath;
        String configDir =moduleFactory.getConfigDir();
        String[] configArray =TLDataUtils.splitStrToArray(urlMapconfigfile,";") ;
        for(int i =0 ;i <configArray.length ; i++)
        {
            String configfile =configDir+configArray[i];
            loadUrls(configDir,configfile) ;
        }
        return   putResultToClient(true,"loadAppUrls","加载成功");
    }
    private TLMsg loadUrls(String configPath,String  urlMapconfigfile ) {
        TLWUrlMap.myConfig config = new TLWUrlMap.myConfig(urlMapconfigfile, configPath);
        config.init(urlMapconfigfile);
        HashMap<String, ArrayList<TLMsg>> urlMap =config.getUrlMapTable() ;
        HashMap<String,String> urlMapParams =config.getParams();
        String prefixUrl =urlMapParams.get("prefixUrl");
        if(prefixUrl ==null)
            prefixUrl="";
        urlMapTableToDb(prefixUrl,  urlMap);
        return   putResultToClient(true,"loadUrls","加载成功");
    }
    private  void urlMapTableToDb(String prefixUrl, HashMap<String, ArrayList<TLMsg>> urlMap){
        ArrayList<LinkedHashMap> dblist = new ArrayList<>();
        for(String key :urlMap.keySet())
        {
            ArrayList<TLMsg> listMsg =urlMap.get(key);
            TLMsg umsg =listMsg.get(0) ;
            String modulename = umsg.getDestination() ;
            if(key.indexOf("*") !=-1){
                String prefix =key.substring(0,key.length()-1);
                Map<String,Object> params = umsg.getArgs();
                for(String url :params.keySet()){
                    LinkedHashMap<String ,String > dbmap =new LinkedHashMap<>();
                    String action = (String) params.get(url);
                    if(action ==null)
                        continue;
                    String actionid=modulename+":"+action ;
                    String href =prefix+url;
                    String name =prefix+url;
                    dbmap.put("module",modulename);
                    dbmap.put("actionid",actionid);
                    dbmap.put("name",name);
                    dbmap.put("href", prefixUrl+href);
                    dblist.add(dbmap);
                }
            }
            else {

                String action = umsg.getAction() ;
                String actionid=modulename+":"+action ;
                String href =key;
                String name =key;
                LinkedHashMap<String ,String > dbmap =new LinkedHashMap<>();
                dbmap.put("module",modulename);
                dbmap.put("actionid",actionid);
                dbmap.put("name",name);
                dbmap.put("href",prefixUrl+href);
                dblist.add(dbmap);
            }
        }
        BeanTable beanTable =new BeanTable(urlsTableName,"href",getFactory());
        beanTable.addAll(dblist);
    }
    private TLMsg menuEditSubmit(Object fromWho, TLMsg msg) {
        String idstr = (String) msg.getParam("id");
        Long id = Long.valueOf(idstr);
        Map<String,Object> menudata =menusIndb.get(id);
        int ifsystem = (int) menudata.get("ifsystem");
        if(ifsystem ==1)
            return putResultToClient(0,"menuEditSubmit");
        String name = (String)msg.getParam("name");
        String title = (String)msg.getParam("title");
        String typestr = (String)msg.getParam("type");
        int type = Integer.parseInt(typestr);
        String sortstr = (String)msg.getParam("sort");
        int sort = Integer.parseInt(sortstr);
        String statusstr = (String)msg.getParam("status");
        int status = Integer.parseInt(statusstr);
        String href = (String)msg.getParam("href");
        String relatedurls = (String)msg.getParam("relatedurls");
        if(href.isEmpty())
            href=null;
        if(relatedurls.isEmpty())
            relatedurls=null;
        String remark = (String)msg.getParam("remark");
        updateRole(href,relatedurls,id) ;
        LinkedHashMap<String,Object> menu = new LinkedHashMap<>();
        menu.put("name",name);
        menu.put("title",title);
        menu.put("type",type);
        menu.put("sort",sort);
        menu.put("status",status);
        menu.put("href",href);
        menu.put("relatedurls",relatedurls);
        menu.put("remark",remark);
        int result =menusIndb.update(id,menu);
        return putResultToClient(result,"menuEditSubmit");
    }
    private void updateRole(String href,String relatedurls,Long menuid){
        TLMsg roleMsg =createMsg().setAction("updateRoleByHref").setParam("menuid",menuid);
        Map<String,Object> oldMenu =menusIndb.get(menuid);
        String oldhref = (String) oldMenu.get("href");
        if(oldhref !=null && href !=null && !oldhref.equals(href)){
            roleMsg.setParam("href",oldhref).setParam("action","delete");
            putMsg("rolesManagerControl",roleMsg) ;
            roleMsg.setParam("href",href).setParam("action","add");
            putMsg("rolesManagerControl",roleMsg) ;
        }
        else if(oldhref ==null && href !=null ){
            roleMsg.setParam("href",href).setParam("action","add");
            putMsg("rolesManagerControl",roleMsg) ;
        }
        else if(oldhref !=null && href ==null ){
            roleMsg.setParam("href",oldhref).setParam("action","delete");
            putMsg("rolesManagerControl",roleMsg) ;
        }
        String oldRelatedurls = (String) oldMenu.get("relatedurls");
        ArrayList<String> relatedurlsList =new ArrayList() ;
        if(relatedurls !=null && !relatedurls.isEmpty())
            relatedurlsList= (ArrayList) TLDataUtils.splitStrToList(relatedurls,";");
        ArrayList<String> oldRelatedurlsList =new ArrayList() ;
        if( oldRelatedurls !=null && !oldRelatedurls.isEmpty())
            oldRelatedurlsList= (ArrayList) TLDataUtils.splitStrToList( oldRelatedurls,";");
        List<String> addHrefList = (List) CollectionUtils.subtract(relatedurlsList,oldRelatedurlsList);
        if(!addHrefList.isEmpty()){
            for(String addhref :addHrefList){
                roleMsg.setParam("href",addhref).setParam("action","add");
                putMsg("rolesManagerControl",roleMsg) ;
            }
        }
        List<String> deleteHrefList = (List) CollectionUtils.subtract(oldRelatedurlsList,relatedurlsList);
        if(!deleteHrefList.isEmpty()){
            for(String deletehref :deleteHrefList){
                roleMsg.setParam("href",deletehref).setParam("action","delete");
                putMsg("rolesManagerControl",roleMsg) ;
            }
        }
    }
    private void getMenuForEdit(Object fromWho, TLMsg msg) {
        String idstr = (String) msg.getParam("id");
        Long id = Long.valueOf(idstr);
        Map<String,Object> menu =menusIndb.get(id);
        BeanTable urlsTable = new BeanTable(urlsTableName,"href",moduleFactory);
        ArrayList<Map<String, Object>> urls =urlsTable.getAll() ;
        outData odata =  creatOutDataMsg("getMenuForEdit");
        odata.addData("menu",menu);
        odata.addData("urls",urls);
        putOutData(odata);
    }

    private void menuEdit(Object fromWho, TLMsg msg) {
        String id = (String) msg.getParam("id");
        outData odata = creatOutDataMsg("menuedit");
        odata.addData("id",id);
        putOutData(odata);
    }

    private TLMsg menuDelete(Object fromWho, TLMsg msg) {
        String idstr = (String) msg.getParam("id");
        Long id = Long.valueOf(idstr);
        Map<String,Object> menu =menusIndb.get(id);
        int ifsystem = (int) menu.get("ifsystem");
        if(ifsystem ==1)
            return putResultToClient(0,"menuDelete");
        int result = menuDeleteById(id);
        return putResultToClient(result,"menuDelete");
    }

    private int menuDeleteById(Long id) {
        ArrayList<Map<String,Object>> menusons = getMenuSons(id) ;
        if( menusons !=null || menusons.size() >0)
        {
            for(Map<String,Object> menu : menusons)
            {
                Long mid = (Long) menu.get("id");
                int result = menuDeleteById(mid) ;
                if(result ==0)
                    return 0;
            }
        }
        TLMsg msg =createMsg().setAction("deleteRoleMenu").setParam("menuid",id);
        putMsg("rolesManagerControl",msg) ;
        return menusIndb.remove(id) ;
    }

    private ArrayList<Map<String,Object>> getMenuSons(Long pid) {
        return  menusIndb.getAll("pid",pid) ;
    }

    private void getUrls(Object fromWho, TLMsg msg) {
        String type = (String) msg.getParam("type");
        BeanTable urlsTable = new BeanTable(urlsTableName,"href",moduleFactory);
        ArrayList<Map<String, Object>> urls;
        if(type !=null && type.equals("all"))
            urls =urlsTable.getAll() ;
        else
             urls =urlsTable.getAll("ismenu",1) ;
        outData odata = creatOutDataMsg("getUrls");
        odata.addData("code","0") ;
        odata.addData("msg","") ;
        odata.addData("count",urls.size());
        odata.addData("data",urls);
        putOutData(odata);
    }

    private TLMsg menuAddSubmit(Object fromWho, TLMsg msg) {
        String pid = (String) msg.getParam("pid");
        String name = (String)msg.getParam("name");
        String title = (String)msg.getParam("title");
        String typestr = (String)msg.getParam("type");
        int type = Integer.parseInt(typestr);
        String sortstr = (String)msg.getParam("sort");
        int sort = Integer.parseInt(sortstr);
        String href = (String)msg.getParam("href");
        String relatedurls = (String)msg.getParam("relatedurls");
        String remark = (String)msg.getParam("remark");
        LinkedHashMap<String,Object> menu = new LinkedHashMap<>();
        menu.put("pid",pid);
        menu.put("name",name);
        menu.put("title",title);
        menu.put("type",type);
        menu.put("sort",sort);
        if(!href.isEmpty())
            menu.put("href",href);
        if(!relatedurls.isEmpty())
            menu.put("relatedurls",relatedurls);
        menu.put("remark",remark);
        int result =menusIndb.add(menu);
        return putResultToClient(result,"menuAddSubmit");
    }

    private void menuAdd(Object fromWho, TLMsg msg) {
        String id = (String) msg.getParam("id");
        if(id ==null)
            id="0" ;
        outData odata = creatOutDataMsg("menuadd");
        odata.addData("pid",id);
        putOutData(odata);
    }

    protected void index(Object fromWho, TLMsg msg) {
        outData odata = creatOutDataMsg("index");
        odata.addData("msg", "this is menu index ");
        putOutData(odata);
    }
    private void menuList(Object fromWho, TLMsg msg) {
        ArrayList<Map<String, Object>>  menuInfo =getAllMenus();
        outData odata =  creatOutDataMsg("menuJson");
        odata.addData("code","0") ;
        odata.addData("msg","") ;
        odata.addData("count",menuInfo.size());
        odata.addData("data",menuInfo);
        putOutData(odata);
    }

}

