package cn.tianlong.java.application.webmanager;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.db.dbdata.ViewTable;
import cn.tianlong.tlobject.utils.TLDataUtils;
import cn.tianlong.tlobject.utils.TLDateUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

public class managersManagerControl extends adminCommon {
    protected String superUser;
    protected String managerRole ;
    protected String defaultMenusTableName ="menus";
    protected String defaultActionUserTableName ="actionusers";
    protected String defaultUserMenusTableName ="usermenus";
    protected String defaultUrlsTableName ="urls" ;
    protected String defaultUserRolesTableName ="userroles" ;
    protected String defaultRolesTableName ="managerroles";
    protected String userMenusTableName;
    protected String userRolesTableName;
    protected String rolesTableName;
    protected String actionUserTableName;
    protected String menusTableName;
    protected String urlsTableName ;
    protected BeanTable userMenusIndb;
    protected BeanTable actionUserTable ;
    protected BeanTable menusTable ;
    protected  BeanTable urlsTable ;
    protected  BeanTable userRolesTable ;
    protected  BeanTable rolesIndb ;

     public managersManagerControl() {
        super();
    }

    public managersManagerControl(String name) {
        super(name);
    }

    public managersManagerControl(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }
    @Override
    protected TLBaseModule init() {
        if(params !=null && params.get("managerRole")!=null)
            managerRole = params.get("managerRole");
        if(params !=null && params.get("rolesTable")!=null)
            userMenusTableName = params.get("rolesTable");
        else
            userMenusTableName = defaultUserMenusTableName;
        if(params !=null && params.get("actionUserTable")!=null)
            actionUserTableName = params.get("actionUserTable");
        else
            actionUserTableName = defaultActionUserTableName;
        if(params !=null && params.get("menusTable")!=null)
            menusTableName = params.get("menusTable");
        else
            menusTableName = defaultMenusTableName;
        if(params !=null && params.get("urlsTable")!=null)
            urlsTableName = params.get("urlsTable");
        else
            urlsTableName = defaultUrlsTableName;
        if(params !=null && params.get("userrolesTable")!=null)
            userRolesTableName = params.get("userrolesTable");
        else
            userRolesTableName = defaultUserRolesTableName;
        if(params !=null && params.get("rolesTable")!=null)
            rolesTableName = params.get("rolesTable");
        else
            rolesTableName = defaultRolesTableName;
        superUser =  getModuleParam("adminauth",AUTH_P_DEFAULTSUPERUSER);
        userMenusIndb =new BeanTable(userMenusTableName,moduleFactory);
        actionUserTable =new BeanTable(actionUserTableName,moduleFactory);
        menusTable = new BeanTable(menusTableName,"id",moduleFactory);
        urlsTable = new BeanTable(urlsTableName,"href",moduleFactory);
        userRolesTable =new BeanTable(userRolesTableName,moduleFactory);
        rolesIndb =new BeanTable(rolesTableName,"id",true,moduleFactory);
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "index":
                index(fromWho, msg);
                break;
            case "managersList":
                managersList(fromWho, msg);
                break;
            case "managerAdd":
                managerAdd(fromWho, msg);
                break;
            case "managerAddSubmit":
                managerAddSubmit(fromWho, msg);
                break;
            case "managerDelete":
                managerDelete(fromWho, msg);
                break;
            case "managerEdit":
                managerEdit(fromWho, msg);
                break;
            case "getManagerForEdit":
                getManagerForEdit(fromWho, msg);
                break;
            case "managerEditSubmit":
                managerEditSubmit(fromWho, msg);
                break;
            case "managerPasswdEdit":
                managerPasswdEdit(fromWho, msg);
                break;
            case "managerPasswdSubmit":
                managerPasswdSubmit(fromWho, msg);
                break;
            case "otherMenus":
                otherMenus(fromWho, msg);
                break;
            case "getOtherMenus":
                getOtherMenus(fromWho, msg);
                break;
            case "changeMenus":
                changeMenus(fromWho, msg);
                break;
            case "getCreatorMenus":
                getCreatorMenus(fromWho, msg);
                break;
            case "changeMenuSubmit":
                changeMenuSubmit(fromWho, msg);
                break;
            case "onlineManagers":
                onlineManagers(fromWho, msg);
                break;
            case "onlinesList":
                onlinesList(fromWho, msg);
                break;
            case "setOffline":
                setOffline(fromWho, msg);
                break;
            case "updateStatus":
                updateStatus(fromWho, msg);
                break;
            default:
                putMsg("error", creatOutMsg().setAction("setError").setParam("content", "no action"));
        }
        return returnMsg;
    }

    protected void updateStatus(Object fromWho, TLMsg msg) {
         if(msg.isNull("userid") || msg.isNull("status"))
             return;
        String userid = (String) msg.getParam("userid");
        Map<String,Object> userinfo = getUserInfo(userid);
        if(userinfo ==null || userinfo.isEmpty()){
            putResultToClient(false,"setOffline","没有权限");
            return;
        }
        int status =Integer.parseInt((String) msg.getParam("status"));
        TLMsg updateMsg =createMsg().setAction("updatState").setParam(USER_P_USERID,userid).setParam("status",status);
        TLMsg returnMsg =putMsg("managerModle",updateMsg);
        if(status ==0){
            String userobj =getUserObjName();
            putMsg(userobj,createMsg().setAction(USER_SETOFFLINE).setParam(USER_P_USERID,userid));
        }
        int result = (int) returnMsg.getParam(DB_R_RESULT);
        putResultToClient(result,"updateStatus");
    }

    protected void setOffline(Object fromWho, TLMsg msg) {
         if(msg.isNull("userid"))
             return;
         String userid = (String) msg.getParam("userid");
        Map<String,Object> userinfo = getUserInfo(userid);
        if(userinfo ==null || userinfo.isEmpty()){
            putResultToClient(false,"setOffline","没有权限");
            return;
        }
        TLMsg updateMsg =createMsg().setAction("updatState").setParam(USER_P_USERID,userid).setParam("status",0);
        putMsg("managerModle",updateMsg);
        String userobj =getUserObjName();
        putMsg(userobj,createMsg().setAction(USER_SETOFFLINE).setParam(USER_P_USERID,userid));
        putResultToClient(true,"setOffline");
    }

    protected void onlinesList(Object fromWho, TLMsg msg) {
        String userobj =getUserObjName();
        TLMsg returnMsg = putMsg(userobj,createMsg().setAction(USER_GETONLINE));
        Map<String,Object> onlines =returnMsg.getArgs();
        ArrayList<HashMap<String,Object>> userList =new ArrayList<>();
        if(onlines !=null)
        {
            for(String userid : onlines.keySet()){
                HashMap<String,Object> info = (HashMap<String, Object>) onlines.get(userid);
                Long  logintime = (Long) info.get("logintime");
                String logintimeStr =TLDateUtils.dateToStr(new Date(logintime),null);
                HashMap<String,Object> data  = new HashMap<>();
                data.put("userid",userid);
                data.put("logintime",logintimeStr);
                userList.add(data);
            }
        }
        outData odata =  creatOutDataMsg("onlinesList");
        odata.addData("code","0") ;
        odata.addData("msg","") ;
        odata.addData("count",userList.size());
        odata.addData("data",userList);
        putOutData(odata);
    }

    protected void onlineManagers(Object fromWho, TLMsg msg) {
        outData odata = creatOutDataMsg("onlineManagers");
        putOutData(odata);
    }

    protected void changeMenuSubmit(Object fromWho, TLMsg msg) {
        String menus = (String) msg.getParam("menus");
        String edituserid = (String) msg.getParam("userid");
        Map<String,Object> userinfo = getUserInfo(edituserid);
        if(userinfo ==null || userinfo.isEmpty()){
            putResultToClient(false,"getCreatorMenus");
            return;
        }
        List subMenus= TLDataUtils.splitStrToIntgerList(menus,";");
        if(subMenus ==null)
            subMenus =new ArrayList() ;
        String roleid = (String) userinfo.get("roleid");
        TLMsg returnMsg =putMsg("menuManagerControl",createMsg().setAction("getRoleMenus").setParam("roleid",roleid));
        ArrayList<Map<String, Object>> rolemenuList    = (ArrayList<Map<String, Object>>) returnMsg.getParam(RESULT);
        ArrayList<Integer> roleMenus =new ArrayList() ;
        if(rolemenuList !=null && !rolemenuList .isEmpty())
        {
            ArrayList idList =TLDataUtils.mapListToList(rolemenuList,"id");
            roleMenus=TLDataUtils.longTypeToIntList(idList);
        }
        List newMenus =  (List) CollectionUtils.subtract(subMenus,roleMenus);
        ViewTable menusIndb =new ViewTable("adminothermenus",moduleFactory);
        LinkedHashMap<String,Object> sqlparams = new LinkedHashMap<>() ;
        sqlparams.put("userid",edituserid);
        ArrayList<Map<String, Object>> usermenuList =  menusIndb.get(sqlparams);
        ArrayList<Integer> oldMenus =new ArrayList() ;
        if(usermenuList !=null && !usermenuList .isEmpty())
        {
            ArrayList idList =TLDataUtils.mapListToList(usermenuList,"id");
            oldMenus=TLDataUtils.longTypeToIntList(idList);
        }
        List<Integer> addMenus = (List) CollectionUtils.subtract(newMenus,oldMenus);
        List<Integer> deleteMenus = (List) CollectionUtils.subtract(oldMenus,newMenus);
        if(addMenus !=null && !addMenus.isEmpty())
        {
            ArrayList<Map<String, Object>> userMenusList =getUserMenus();
            for(Integer menuid : addMenus)
            {
                if(ifUsersMenu(menuid,userMenusList))
                    continue;
                else {
                    putResultToClient(false,"getCreatorMenus");
                    return;
                }
            }
            addUserMenu(edituserid , addMenus);
        }
        if(deleteMenus !=null && !deleteMenus.isEmpty())
        {
            for(int menuid : deleteMenus)
                deleteUserMenu(edituserid,menuid) ;
        }
       putResultToClient( true,"roleEditSubmit");
    }

    protected   ArrayList<Map<String, Object>> getUserMenus(){
        TLMsg returnMsg =putMsg("menuManagerControl",createMsg().setAction("getAllMenus"));
        return   (ArrayList<Map<String, Object>>) returnMsg.getParam(RESULT);
    }

    protected Boolean ifUsersMenu(int menuId,ArrayList<Map<String, Object>>menuList){
        boolean result=false;
        for(Map<String, Object> menu: menuList){
            Long mid = (Long) menu.get("id");
            if(mid ==menuId)
            {
                result =true;
                break;
            }
        }
        return result ;
    }
    protected void deleteUserMenu(String userid, int menuid) {
        LinkedHashMap<String,Object> rolemenu = new LinkedHashMap<>();
        rolemenu.put("userid",userid) ;
        rolemenu.put("menuid",menuid) ;
        int result = userMenusIndb.remove(rolemenu) ;
        if (result ==1)
            deleteActionUser(userid, menuid);
    }

    protected void deleteActionUser(String userid, int menuid) {
        Map<String,Object> menu =menusTable.get(menuid);
        if(menu ==null)
            return;
        String href = (String) menu.get("href");
        deleteUserByHref(href,userid);
        String relatedurls = (String) menu.get("relatedurls");
        if(relatedurls ==null || relatedurls.isEmpty())
            return;
        String[] urlsArray =TLDataUtils.splitStrToArray(relatedurls,";");
        for(int i=0 ; i< urlsArray.length ; i++){
            String  urlid =urlsArray[i];
            deleteUserByHref(urlid, userid) ;
        }
    }
    protected void deleteUserByHref(String href, String userid) {
        if(href==null || href.isEmpty())
            return;
        Map<String,Object> url =urlsTable.get(href);
        if(url==null || url.isEmpty())
            return;
        int isMenu = (int) url.get("ismenu");
        if(isMenu==0)
            return;
        String actionid = (String) url.get("actionid");
        if(actionid ==null || actionid.isEmpty())
            return;
        deleteUserToActiond(actionid,userid) ;
        putMsg("rolesManagerControl",createMsg().setAction("setPolicyForAction").setParam("actionid",actionid)) ;
    }
    protected void deleteUserToActiond(String actionid, String userid) {
        LinkedHashMap<String ,Object> params =new LinkedHashMap<>() ;
        params.put("actionid",actionid) ;
        params.put("userid",userid) ;
        actionUserTable.remove(params);
    }
    protected void  addUserMenu(String userid ,List<Integer> menusList) {
        for (int i = 0; i < menusList.size(); i++)
        {
            LinkedHashMap<String, Object> rolemenu = new LinkedHashMap<>();
            rolemenu.put("userid", userid);
            rolemenu.put("menuid", menusList.get(i));
            int ifadd =  userMenusIndb.add(rolemenu);
            if (ifadd == 0)
                return;
            addActionUser(userid,  menusList.get(i));
        }
    }

    protected void addActionUser(String userid,int menuid ) {
        Map<String,Object> menu =menusTable.get(menuid);
        if(menu ==null || menu.isEmpty())
            return;
        String href = (String) menu.get("href");
        addUserByHref(href,userid);
        String relatedurls = (String) menu.get("relatedurls");
        if(relatedurls ==null || relatedurls.isEmpty())
            return;
        String[] urlsArray =TLDataUtils.splitStrToArray(relatedurls,";");
        for(int i=0 ; i< urlsArray.length ; i++){
            String  urlid =urlsArray[i];
            addUserByHref(urlid, userid) ;
        }
    }
    protected void addUserByHref(String href, String userid) {
        if(href==null || href.isEmpty())
            return;
        Map<String,Object> url =urlsTable.get(href);
        if(url==null || url.isEmpty())
            return;
        int isMenu = (int) url.get("ismenu");
        if(isMenu==0)
            return;
        String actionid = (String) url.get("actionid");
        if(actionid ==null || actionid.isEmpty())
            return;
        addUserToActiond(actionid,userid) ;
        putMsg("rolesManagerControl",createMsg().setAction("setPolicyForAction").setParam("actionid",actionid)) ;
    }
    protected void addUserToActiond(String actionid, String userid) {
        LinkedHashMap<String ,Object> params =new LinkedHashMap<>() ;
        params.put("actionid",actionid) ;
        params.put("userid",userid) ;
        actionUserTable.add(params);
    }
    protected void  getCreatorMenus(Object fromWho, TLMsg msg) {
        String edituserid = (String) msg.getParam("userid");
        Map<String,Object> userinfo = getUserInfo(edituserid);
        if(userinfo ==null || userinfo.isEmpty()){
            putResultToClient(false,"getCreatorMenus");
            return;
        }
        String roleid = (String) userinfo.get("roleid");
        TLMsg returnMsg =putMsg("menuManagerControl",createMsg().setAction("getRoleMenus").setParam("roleid",roleid));
        ArrayList<Map<String, Object>> rolemenuList    = (ArrayList<Map<String, Object>>) returnMsg.getParam(RESULT);
        String roleMenusStr ="";
        for(Map menu:  rolemenuList){
            if(roleMenusStr.isEmpty())
                roleMenusStr = String.valueOf(menu.get("id"));
            else
                roleMenusStr =roleMenusStr +";"+menu.get("id");
        }
        ViewTable menusIndb =new ViewTable("adminothermenus",moduleFactory);
        LinkedHashMap<String,Object> sqlparams = new LinkedHashMap<>() ;
        sqlparams.put("userid",edituserid);
        ArrayList<Map<String, Object>> usermenuList =  menusIndb.get(sqlparams);
        returnMsg =putMsg("menuManagerControl",createMsg().setAction("getAllMenus"));
        ArrayList<Map<String, Object>> allmenuList    = (ArrayList<Map<String, Object>>) returnMsg.getParam(RESULT);
        if(!rolemenuList.isEmpty())
        {
            for (Map menu : allmenuList){
                Long mid = (Long) menu.get("id");
                for (Map umenu : rolemenuList)
                {
                    Long umid = (Long) umenu.get("id");
                    if(mid.longValue() ==umid.longValue())
                    {
                        menu.put("isrolemenu","true");
                        menu.put("LAY_CHECKED","true");
                        break;
                    }
                }
            }
        }
        if(!usermenuList.isEmpty())
        {
            for (Map menu : allmenuList){
                Long mid = (Long) menu.get("id");
                for (Map umenu : usermenuList)
                {
                    Long umid = (Long) umenu.get("id");
                    if(mid.longValue() ==umid.longValue())
                    {
                        menu.put("LAY_CHECKED","true");
                        break;
                    }
                }
            }
        }
        outData odata =  creatOutDataMsg("getMenuRole");
        odata.addData("rolemenusstr",roleMenusStr) ;
        odata.addData("menus",allmenuList) ;
        putOutData(odata);
    }

    protected void changeMenus(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        String username = (String) msg.getParam("username");
        outData odata = creatOutDataMsg("changeMenus");
        odata.addData("userid",userid);
        odata.addData("username",username);
        putOutData(odata);
    }

    protected void getOtherMenus(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        ViewTable menusIndb =new ViewTable("adminothermenus",moduleFactory);
        LinkedHashMap<String,Object> sqlparams = new LinkedHashMap<>() ;
        sqlparams.put("userid",userid);
        ArrayList<Map<String, Object>> menuList =  menusIndb.get(sqlparams);
        outData odata =  creatOutDataMsg("getOtherMenus");
        odata.addData("menus",menuList) ;
        putOutData(odata);
    }

    protected void otherMenus(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        String username = (String) msg.getParam("username");
        outData odata = creatOutDataMsg("otherMenus");
        odata.addData("userid",userid);
        odata.addData("username",username);
        putOutData(odata);
    }

    protected void managerPasswdSubmit(Object fromWho, TLMsg msg) {
        String edituserid = (String) msg.getParam("userid");
        Map<String,Object> userinfo = getUserInfo(edituserid);
        if(userinfo ==null || userinfo.isEmpty()){
            putResultToClient(false,"getManagerForEdit");
            return;
        }
        TLMsg addMsg =createMsg().setAction("changePassword").addMap(msg.getArgs());
        TLMsg returnMsg =putMsg("managerModle",addMsg);
        int result = (int) returnMsg.getParam(DB_R_RESULT);
        putResultToClient(result,"managerPasswdSubmit");
    }

    protected void managerPasswdEdit(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        String username= (String) msg.getParam("username");
        outData odata = creatOutDataMsg("managerPasswdEdit");
        odata.addData("userid",userid);
        odata.addData("username",username);
        putOutData(odata);
    }

    protected void managerEditSubmit(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("roleid");
        if(roleid ==null || roleid.isEmpty())
        {
            putResultToClient(false,"managerEditSubmit","用户至少有一个角色");
            return;
        }
        String  userid = (String) msg.getParam("userid");
        if(userid ==null || userid.isEmpty())
            return;
        String[] roleidArray =TLDataUtils.splitStrToArray(roleid,";");
        for (int i =0 ;i< roleidArray.length ;i++){
            if(!isRoleCreator(roleidArray[i]))
            {
                putResultToClient(false,"managerEditSubmit");
                return;
            }
        }
        TLMsg addMsg =createMsg().setAction("updateUser").addMap(msg.getArgs());
        TLMsg returnMsg =putMsg("managerModle",addMsg);
        int addnumb = (int) returnMsg.getParam(DB_R_RESULT);
        boolean result =false;
        if(addnumb ==1)
        {
           deleteUserRole(userid) ;
            result = addUserRole(userid, roleidArray);
            putResultToClient(result,"managerEditSubmit");
            return;
        }
         putResultToClient(result,"managerEditSubmit","没有更新");
    }

    protected int deleteUserRole(String userid) {
         LinkedHashMap<String ,Object> param = new LinkedHashMap<>();
         param.put("userid",userid);
         return   userRolesTable.remove(param);
    }

    protected void getManagerForEdit(Object fromWho, TLMsg msg) {
        String edituserid = (String) msg.getParam("userid");
        Map<String,Object> userinfo = getUserInfo(edituserid);
        if(userinfo ==null || userinfo.isEmpty()){
            putResultToClient(false,"getManagerForEdit");
            return;
        }
        String roleid = (String) userinfo.get("roleid");
        TLMsg roleMsg = putMsg("rolesManagerControl",createMsg().setAction("getRolesList"));
        ArrayList<Map<String, Object>>  rolesList = (ArrayList<Map<String, Object>>) roleMsg.getParam(RESULT);
        String[] roldidArray =TLDataUtils.splitStrToArray(roleid,";");
        for(Map<String,Object> role : rolesList)
        {
            for(int i =0 ;i <roldidArray.length ; i++)
            {
                if(role.get("id").equals(roldidArray[i]))
                {
                    role.put("LAY_CHECKED","true");
                    break;
                }
            }
        }
        outData odata =  creatOutDataMsg("getManagerForEdit");
        odata.addData("user",userinfo);
        odata.addData("roles",rolesList);
        putOutData(odata);
    }
    protected Boolean  ifHaveAuth(){
        String userid =getUserid() ;
        if(userid.equals(superUser))
            return true;
        else {
           return  isRole(managerRole) ;
        }
    }
    protected  Map<String,Object> getUserInfo(String selectedUser){
       String userid =getUserid() ;
        TLMsg userMsg ;
        if(ifHaveAuth())
            userMsg =createMsg().setAction("getUser");
        else
            userMsg =createMsg().setAction("getByCreator").setParam("creator",userid);
        userMsg.setParam("userid",selectedUser);
        TLMsg returnMsg =putMsg("managerModle",userMsg);
        Map<String,Object> userInfo = (Map<String, Object>) returnMsg.getParam(DB_R_RESULT);
        return  userInfo ;
    }
    protected void managerEdit(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        outData odata = creatOutDataMsg("managerEdit");
        odata.addData("userid",userid);
        putOutData(odata);
    }

    protected void managerDelete(Object fromWho, TLMsg msg) {
        String deletedUserid= (String) msg.getParam("userid");
        Map<String,Object> userinfo = getUserInfo(deletedUserid);
        if(userinfo ==null || userinfo.isEmpty()){
            putResultToClient(false,"getManagerForEdit");
            return;
        }
        TLMsg userMsg =createMsg().setAction("deleteUser").setParam("userid",deletedUserid);
        TLMsg returnMsg =putMsg("managerModle",userMsg);
        int deletenumb = (int) returnMsg.getParam(DB_R_RESULT);
        boolean result =false;
        if(deletenumb ==1)
        {
            int deleteNum=deleteUserRole(deletedUserid) ;
            if(deleteNum >0)
                result=true;
        }
        putResultToClient(result,"managerDelete");
    }

    protected void managerAddSubmit(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("roleid");
        if(roleid ==null || roleid.isEmpty())
            return;
        String  userid = (String) msg.getParam("userid");
        if(userid ==null || userid.isEmpty())
            return;
        String[] roleidArray =TLDataUtils.splitStrToArray(roleid,";");
        for (int i =0 ;i< roleidArray.length ;i++){
            if(!isRoleCreator(roleidArray[i]))
            {
                putResultToClient(false,"managerAddSubmit");
                return;
            }
        }
        String creator = getUserid();
        TLMsg addMsg =createMsg().setAction("addUser").addMap(msg.getArgs())
                .setParam("creator",creator);
        TLMsg returnMsg =putMsg("managerModle",addMsg);
        int addnumb = (int) returnMsg.getParam(DB_R_RESULT);
        boolean result =false;
        if(addnumb ==1)
        {

            result = addUserRole(userid, roleidArray);
            if(result ==false){
                TLMsg userMsg =createMsg().setAction("deleteUser").setParam("userid",userid);
                putMsg("managerModle",userMsg);
            }
        }
       putResultToClient(result,"managerAddSubmit");
    }
    protected boolean addUserRole(String userid ,String[] roleidArray) {
         ArrayList<LinkedHashMap> list = new ArrayList<>();
        for (int i =0 ;i< roleidArray.length ;i++){
            LinkedHashMap<String,Object> map =new LinkedHashMap<>();
            map.put("userid",userid) ;
            map.put("roleid",roleidArray[i]);
            list.add(map);
        }
        return userRolesTable.addAll(list);
    }
    protected void managerAdd(Object fromWho, TLMsg msg) {
        outData odata = creatOutDataMsg("managerAdd");
        putOutData(odata);
    }

    protected void index(Object fromWho, TLMsg msg) {
         String  dataid="index";
         if(!msg.isNull("mid")){
            String mid  = (String) msg.getParam("mid");
             dataid=dataid+"_"+mid ;
         }
        outData odata = creatOutDataMsg(dataid);
        odata.addMapData(msg.getArgs());
        putOutData(odata);
    }
    protected void managersList(Object fromWho, TLMsg msg) {
        if(!msg.isNull("roleid") && msg.getParam("roleid").equals("$roleid"))
            msg.removeParam("roleid");
        if(!msg.isNull("userid") && msg.getParam("userid").equals("$userid"))
            msg.removeParam("userid");
        if(!msg.isNull("username") && msg.getParam("username").equals("$username"))
            msg.removeParam("username");
        if(!msg.isNull("depid") && msg.getParam("depid").equals("$depid"))
            msg.removeParam("depid");
        String userid =getUserid();
        TLMsg userMsg=createMsg().setAction("getAllUser").setArgs(msg.getArgs());
        if(! ifHaveAuth())
            userMsg.setParam("creator",userid);
        TLMsg returnMsg =putMsg("managerModle",userMsg);
        ArrayList<Map<String, Object>> managerList = (ArrayList<Map<String, Object>>) returnMsg.getParam(DB_R_RESULT);
        Long count ;
        if(!returnMsg.isNull("number"))
        {
            count = (Long) returnMsg.getParam("number") ;
            setUserSessionData("usersNumbers",count);
        }
        else
            count = (Long) getUserSessionData("usersNumbers");
        ArrayList<Map<String, Object>> roleList =rolesIndb.getAll();
        HashMap<String,String> roles = new HashMap<>() ;
        for(Map<String,Object> role : roleList){
            roles.put((String) role.get("id"),(String)role.get("name"));
        }
        for(Map<String,Object> manager : managerList){
            String roleidstr = (String) manager.get("roleid");
            String[] roleidArray =TLDataUtils.splitStrToArray(roleidstr,";");
            String[] roleNameArray = new String[roleidArray.length];
            for(int i =0 ; i < roleidArray.length ; i ++){
                String roleid = roleidArray[i] ;
                roleNameArray[i]=roles.get(roleid);
            }
            String roleidName =StringUtils.join(roleNameArray, ";");
            manager.put("roleid",roleidName);
        }
        outData odata =  creatOutDataMsg("managerList");
        odata.addData("code","0") ;
        odata.addData("msg","") ;
        odata.addData("count",count);
        odata.addData("data",managerList);
        putOutData(odata);
    }
    protected boolean isRoleCreator(String roleid){
         String userid =getUserid();
         if( ifHaveAuth())
             return true ;
         TLMsg msg =createMsg().setAction("getRoleById").setParam("roleid",roleid);
         TLMsg returnMsg =putMsg("rolesManagerControl",msg);
         String  dbroleid = (String) returnMsg.getParam("id");
         if(dbroleid ==null || dbroleid.isEmpty())
             return false ;
         String creator = (String) returnMsg.getParam("creator");
         if(creator ==null || !userid.equals(creator))
             return false ;
         return  true ;
    }
}

