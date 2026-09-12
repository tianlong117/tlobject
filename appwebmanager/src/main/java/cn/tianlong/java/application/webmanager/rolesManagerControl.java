package cn.tianlong.java.application.webmanager;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.db.dbdata.BeanTable;
import cn.tianlong.tlobject.db.dbdata.MapInDB;
import cn.tianlong.tlobject.db.dbdata.TLDataInDBUtils;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.apache.commons.collections.CollectionUtils;

import java.util.*;

import static cn.tianlong.tlobject.servletutils.TLParamString.*;

public class rolesManagerControl extends adminCommon {
    protected String superUser;
    protected String defaultRolesTableName ="roles";
    protected String defaultMenusTableName ="menus";
    protected String defaultRoleMenusTableName ="rolemenus";
    protected String defaultUrlsTableName ="urls" ;
    protected String defaultActionPoliciesTable ="actionpolicies" ;
    protected String defaultActionUserTableName ="actionusers";
    protected String rolesTableName;
    protected String urlsTableName ;
    protected String menusTableName;
    protected String rolemenusTableName ;
    protected String actionPoliciesTableName ;
    protected String actionUserTableName;
    protected String userRolesTableName ="userroles";
    protected  String authModule ;
    protected BeanTable rolesIndb;
    protected BeanTable menusTable ;
    protected  BeanTable urlsTable ;
    protected  BeanTable actionPoliciesTable ;
    protected  BeanTable rolemenusTable ;
    protected BeanTable actionUserTable ;
    protected BeanTable userRolesTable;
     public rolesManagerControl() {
        super();
    }

    public rolesManagerControl(String name) {
        super(name);
    }

    public rolesManagerControl(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }
    @Override
    protected TLBaseModule init() {
        if(params !=null && params.get("rolesTable")!=null)
            rolesTableName = params.get("rolesTable");
        else
            rolesTableName = defaultRolesTableName;
        if(params !=null && params.get("menusTable")!=null)
            menusTableName = params.get("menusTable");
        else
            menusTableName = defaultMenusTableName;
        if(params !=null && params.get("roleMenusTable")!=null)
            rolemenusTableName = params.get("roleMenusTable");
        else
            rolemenusTableName = defaultRoleMenusTableName;
        if(params !=null && params.get("urlsTable")!=null)
            urlsTableName = params.get("urlsTable");
        else
            urlsTableName = defaultUrlsTableName;
        if(params !=null && params.get("actionPoliciesTable")!=null)
            actionPoliciesTableName = params.get("actionPoliciesTable");
        else
           actionPoliciesTableName = defaultActionPoliciesTable;
        if(params !=null && params.get("actionUserTable")!=null)
            actionUserTableName = params.get("actionUserTable");
        else
            actionUserTableName = defaultActionUserTableName;
        if(params !=null && params.get("userRolesTableName")!=null)
            userRolesTableName = params.get("userRolesTableName");
        if(params !=null && params.get("authModule")!=null)
           authModule = params.get("authModule");
        superUser =  getModuleParam("adminauth",AUTH_P_DEFAULTSUPERUSER);
        rolesIndb =new BeanTable(rolesTableName,"id",true,moduleFactory);
        menusTable = new BeanTable(menusTableName,"id",moduleFactory);
        rolemenusTable = new BeanTable( rolemenusTableName,moduleFactory);
        urlsTable = new BeanTable(urlsTableName,"href",moduleFactory);
        actionPoliciesTable =new BeanTable(actionPoliciesTableName,null,getFactory());
        actionUserTable =new BeanTable(actionUserTableName,moduleFactory);
        userRolesTable =new BeanTable(userRolesTableName,moduleFactory);

        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()) {
            case "index":
                index(fromWho, msg);
                break;
            case "getRolesList":
                returnMsg =getRolesList(fromWho, msg);
                break;
            case "rolesList":
                rolesList(fromWho, msg);
                break;
            case "roleAdd":
                roleAdd(fromWho, msg);
                break;
            case "roleAddSubmit":
                returnMsg = roleAddSubmit(fromWho, msg);
                break;
            case "roleEdit":
                roleEdit(fromWho, msg);
                break;
            case "getRoleForEdit":
                if(checkUser(msg))
                    getRoleForEdit(fromWho, msg);
                break;
            case "roleEditSubmit":
                if(checkUser(msg))
                   roleEditSubmit(fromWho, msg);
                break;
            case "roleDelete":
                if(checkUser(msg))
                    roleDelete(fromWho, msg);
                break;
            case "getPolicyFromModuleToDB":
                getPolicyFromModuleToDB(fromWho, msg);
                break;
            case "menuRoles":
                menuRoles(fromWho, msg);
                break;
            case "getMenuRole":
                getMenuRole(fromWho, msg);
                break;
            case "menuRoleSubmit":
                if(checkUser(msg))
                    menuRoleSubmit(fromWho, msg);
                break;
            case "updateRoleByHref":
                updateRoleByHref(fromWho, msg);
                break;
            case "deleteRoleMenu":
                deleteRoleMenu(fromWho, msg);
                break;
            case "makePolicy":
                if(checkUser(msg))
                    makePolicy(fromWho, msg);
                break;
            case "getRoleById":
               returnMsg =getRoleById(fromWho, msg);
                break;
            case "roleMenus":
                roleMenus(fromWho, msg);
                break;
            case "getRoleMenus":
                if(checkUser(msg))
                    getRoleMenus(fromWho, msg);
                break;
            case "setPolicyForAction":
                    setPolicyForAction(fromWho, msg);
                break;
            case "roleUsers":
                roleUsers(fromWho, msg);
                break;
            case "getRoleUsers":
                getRoleUsers(fromWho, msg);
                break;
            default:
                putMsg("error", creatOutMsg().setAction("setError").setParam("content", "no action"));
        }
        return returnMsg;
    }

    private void getRoleUsers(Object fromWho, TLMsg msg) {
        String roleid=null;
        if(!msg.isNull("roleid"))
            roleid = (String) msg.getParam("roleid");
        if(roleid ==null)
            return;
        int pageSize=10;
        int  page =1;
        if(!msg.isNull("page"))
           page = Integer.parseInt((String) msg.getParam("page"));
        if(!msg.isNull("limit"))
        {  String limit = (String) msg.getParam("limit");
            pageSize =Integer.parseInt(limit);
        }
        int start =(page-1)*pageSize;
        String limitStr =" limit "+start+","+pageSize;
     //   String sql="select  m.*  from  mangers m   WHERE  m.userid IN (SELECT userid FROM userroles  WHERE roleid =?  order by userid desc "+limitStr+" )";
        String sql="select  m.*  from  managers m  ,[table] r WHERE  r.roleid =?  and m.userid=r.userid order by r.userid desc "+limitStr;
        LinkedHashMap<String, Object> sqlparams =new LinkedHashMap<>();
        sqlparams.put("roleid",roleid);
        ArrayList<Map<String, Object>> rolesList = userRolesTable.query(sql,sqlparams);
        outData odata =  creatOutDataMsg("getRoleUsers");
        odata.addData("code","0") ;
        odata.addData("msg","") ;
        odata.addData("count",rolesList.size());
        odata.addData("data",rolesList);
        putOutData(odata);
    }

    private void roleUsers(Object fromWho, TLMsg msg) {
        String id = (String) msg.getParam("id");
        String name = (String) msg.getParam("name");
        outData odata = creatOutDataMsg("roleUsers");
        odata.addData("id",id);
        odata.addData("name",name);
        putOutData(odata);
    }

    private void roleMenus(Object fromWho, TLMsg msg) {
        String id = (String) msg.getParam("id");
        String name = (String) msg.getParam("name");
        outData odata = creatOutDataMsg("roleMenus");
        odata.addData("id",id);
        odata.addData("name",name);
        putOutData(odata);
    }
    private boolean checkUser(TLMsg msg) {
        String roleid=null;
         if(!msg.isNull("roleid"))
            roleid = (String) msg.getParam("roleid");
         else if (!msg.isNull("id"))
             roleid = (String) msg.getParam("id");
         if(roleid ==null)
             return false ;
        String userid =getUserid();
        if(!userid.equals(superUser)){
            Map<String,Object> role =rolesIndb.get(roleid);
            String creator = (String)role.get("creator");
            if(!userid.equals(creator))
                return false ;
        }
        return  true ;
    }
    private void getRoleMenus(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("roleid");
        TLMsg returnMsg =putMsg("menuManagerControl",createMsg().setAction("getRoleMenus").setParam("roleid",roleid));
        List menuList    = (List) returnMsg.getParam(RESULT);
        outData odata =  creatOutDataMsg("getRoleMenus");
        odata.addData("menus",menuList) ;
        putOutData(odata);
    }

    private TLMsg getRoleById(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("roleid");
        Map<String,Object> role =rolesIndb.get(roleid);
         return createMsg().addMap(role);
    }

    private TLMsg getRolesList(Object fromWho, TLMsg msg) {
        ArrayList<Map<String, Object>>  rolesList =getUserRoles();
        return createMsg().setParam(RESULT,rolesList) ;
    }
    private TLMsg makePolicy(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("id");
        makePolicy( roleid) ;
        return putResultToClient(true,"makepolicy");
    }
    private void makePolicy(String roleid) {

        ArrayList<Map<String, Object>> roleMenus =rolemenusTable.getAll("roleid",roleid) ;
        if(roleMenus !=null)
        {
            for(Map menu: roleMenus){
                addActionRole( roleid, (Integer) menu.get("menuid")) ;
            }
        }
    }
    private TLMsg roleDelete(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("id");
        Map<String,Object> role =rolesIndb.get(roleid);
        int ifsystem = (int) role.get("ifsystem");
        if(ifsystem ==1)
            return putResultToClient(false,"roleDelete","系统角色，无法删除");
        TLMsg returnMsg =putMsg("managerModel",createMsg().setAction("countByRole").setParam("roleid",roleid));
        Long roleUserNumbers= (Long) returnMsg.getParam("number");
        if(roleUserNumbers >0L){
            return putResultToClient(false,"roleDelete","该角色下有用户，无法删除");
        }
        ArrayList<Map<String, Object>> roleMenus =rolemenusTable.getAll("roleid",roleid) ;
        if(roleMenus !=null)
        {
            for(Map menu: roleMenus){
                deleteMenu(roleid, (Integer) menu.get("menuid"));
            }
        }
        int result = rolesIndb.remove(roleid);
        return putResultToClient(result,"roleDelete");
    }

    private void deleteRoleMenu(Object fromWho, TLMsg msg) {
        if( msg.isNull("menuid"))
            return;
        Long menuid = (Long) msg.getParam("menuid");
        ArrayList<Map<String, Object>> roleMenus =rolemenusTable.getAll("menuid",menuid) ;
        if(roleMenus !=null){
            for(Map<String,Object> map : roleMenus){
                String roleid = (String) map.get("roleid");
                deleteMenu(roleid, Math.toIntExact(menuid));
            }
        }
    }

    private void updateRoleByHref(Object fromWho, TLMsg msg) {
        if(msg.isNull("href") || msg.isNull("menuid")||msg.isNull("action"))
            return;
        String href = (String) msg.getParam("href");
        Long menuid = (Long) msg.getParam("menuid");
        String action = (String) msg.getParam("action");
        ArrayList<Map<String, Object>> roleMenus =rolemenusTable.getAll("menuid",menuid) ;
        if(roleMenus ==null || roleMenus.isEmpty())
            return;
         if(action.equals("add"))
         {
            for(Map<String,Object> roleMap :roleMenus)
            {
                String roleid = (String) roleMap.get("roleid");
                addRoleByHref(href,roleid);
            }
         }
        else
         {
             for(Map<String,Object> roleMap :roleMenus)
             {
                 String roleid = (String) roleMap.get("roleid");
                 deleteRoleByHref(href,roleid);
             }
         }
    }
    private boolean isRoleMenu(int menuid){
        String userid =getUserid();
        if(userid.equals(superUser))
           return true ;
        Boolean result =false ;
        ArrayList<Map<String, Object>>menuList  = getUserMenus() ;
        for(Map<String, Object> menu: menuList)
        {
             Long mid = (Long) menu.get("id");
             if(mid ==menuid)
             {
                  result =true;
                  break;
             }
         }
        return result ;
    }
    private void menuRoleSubmit(Object fromWho, TLMsg msg) {
         if(msg.isNull("menuid") || msg.isNull("roleid")||msg.isNull("action"))
             return;
        String menuidStr = (String) msg.getParam("menuid");
        int menuid =Integer.parseInt(menuidStr) ;
        if(!isRoleMenu(menuid))
        {
            putResultToClient(false,"menuRoleSubmit");
            return;
        }
        String roleid = (String) msg.getParam("roleid");
        String action = (String) msg.getParam("action");
        if(action.equals("on"))
            addMenu(roleid,menuid);
        else
            deleteMenu(roleid,menuid);
    }

    private void getMenuRole(Object fromWho, TLMsg msg) {
        String menuidStr = (String) msg.getParam("menuid");
        int menuid =Integer.parseInt(menuidStr) ;
        if(!isRoleMenu(menuid))
        {
            putResultToClient(false,"menuRoleSubmit");
            return;
        }
        ArrayList<Map<String, Object>> roleMenus =rolemenusTable.getAll("menuid",menuid) ;
        String roleMenusStr ="";
        for(Map menu: roleMenus){
            if(roleMenusStr.isEmpty())
                roleMenusStr = String.valueOf(menu.get("roleid"));
            else
                roleMenusStr =roleMenusStr +";"+menu.get("roleid");
        }
        ArrayList<Map<String, Object>> rolesList =  rolesIndb.getAll();
        for(Map<String,Object> role :rolesList){
            String roleid = (String) role.get("id");
            role.put("state",0);
            for(Map<String ,Object> menu: roleMenus){
                String mroleid = (String) menu.get("roleid");
                if(roleid.equals(mroleid)){
                    role.put("state",1);
                    break;
                }
            }
        }
        outData odata =  creatOutDataMsg("getMenuRole");
        odata.addData("roles",rolesList) ;
        odata.addData("menuroles",roleMenusStr) ;
        putOutData(odata);
    }

    private void menuRoles(Object fromWho, TLMsg msg) {
        String id = (String) msg.getParam("id");
        String title = (String) msg.getParam("title");
        outData odata = creatOutDataMsg("menuroles");
        odata.addData("id",id);
        odata.addData("title",title);
        putOutData(odata);
    }

    private void getPolicyFromModuleToDB(Object fromWho, TLMsg msg) {
        getPolicyFromModuleToDB(authModule) ;
    }

    protected void index(Object fromWho, TLMsg msg) {
        outData odata = creatOutDataMsg("index");
        odata.addData("msg", "this is menu index ");
        putOutData(odata);
    }
    private void rolesList(Object fromWho, TLMsg msg) {
        ArrayList<Map<String, Object>>  rolesList =getUserRoles();
        outData odata =  creatOutDataMsg("rolesList");
        odata.addData("code","0") ;
        odata.addData("msg","") ;
        odata.addData("count",rolesList.size());
        odata.addData("data",rolesList);
        putOutData(odata);
    }
    private  ArrayList<Map<String, Object>> getUserRoles(){
        String userid =getUserid() ;
        ArrayList<Map<String, Object>>  rolesList ;
        if(superUser ==null || userid.equals(superUser))
            rolesList =  rolesIndb.getAll();
        else
            rolesList =  rolesIndb.getAll("creator",userid);
        return rolesList ;
    }
    private void roleAdd(Object fromWho, TLMsg msg) {
        outData odata = creatOutDataMsg("roleadd");
        putOutData(odata);
    }
    private TLMsg roleAddSubmit(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("id");
        String name = (String)msg.getParam("name");
        String menus = (String)msg.getParam("menus");
        String remark = (String)msg.getParam("remark");
        String creator =getUserid();
        LinkedHashMap<String,Object> role = new LinkedHashMap<>();
        role.put("id",roleid);
        role.put("name",name);
        role.put("creator",creator);
        role.put("remark",remark);
        int result = rolesIndb.add(role);
        if(result ==0 || menus.isEmpty())
           return putResultToClient(result,"roleAddSubmit");
        List<String> subMenus=  TLDataUtils.splitStrToList(menus,";");
        if(subMenus !=null)
        {
           ArrayList<Integer> roleMenus =new ArrayList<>();
           for(String roleidStr: subMenus){
               roleMenus.add(Integer.parseInt(roleidStr));
           }
            addRoleMenu( roleid , roleMenus);
        }
        return putResultToClient(true,"roleAddSubmit");
    }
    private void addRoleMenu(String roleid ,List<Integer> menusList){
        String userid =getUserid() ;
        if(userid.equals(superUser))
        {
            for(int i=0 ;i < menusList.size() ; i++)
            {
                int menuid =menusList.get(i);
                addMenu(roleid, menuid) ;
            }
        }
        else {
            ArrayList<Map<String, Object>>menuList  = getUserMenus() ;
            for(int i=0 ;i < menusList.size() ; i++){
                int menuid =menusList.get(i);
                if(ifUsersMenu(menuid,menuList))
                    addMenu(roleid, menuid) ;
            }
        }
    }
    private   ArrayList<Map<String, Object>> getUserMenus(){
        TLMsg returnMsg =putMsg("menuManagerControl",createMsg().setAction("getAllMenus"));
         return   (ArrayList<Map<String, Object>>) returnMsg.getParam(RESULT);
    }
    private Boolean ifUsersMenu(int menuId,ArrayList<Map<String, Object>>menuList){
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
    protected  void addMenu(String roleid,int menuid){
        LinkedHashMap<String,Object> rolemenu = new LinkedHashMap<>();
        rolemenu.put("roleid",roleid) ;
        rolemenu.put("menuid",menuid) ;
        int ifadd =rolemenusTable.add(rolemenu) ;
        if (ifadd ==0)
            return;
        addActionRole(roleid,menuid);
    }
    protected  void deleteMenu(String roleid,int menuid){
        LinkedHashMap<String,Object> rolemenu = new LinkedHashMap<>();
        rolemenu.put("roleid",roleid) ;
        rolemenu.put("menuid",menuid) ;
        int result = rolemenusTable.remove(rolemenu) ;
        if (result ==1)
          deleteActionRole(roleid, menuid);
    }
    private void roleEdit(Object fromWho, TLMsg msg) {
        String id = (String) msg.getParam("id");
        outData odata = creatOutDataMsg("roleedit");
        odata.addData("id",id);
        putOutData(odata);
    }
    private void getRoleForEdit(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("id");
        Map<String,Object> role =rolesIndb.get(roleid);
        TLMsg returnMsg =putMsg("menuManagerControl",createMsg().setAction("getAllMenus"));
        ArrayList<Map<String, Object>> menus = ( ArrayList<Map<String, Object>>)returnMsg.getParam(RESULT);
        ArrayList<Map<String, Object>> roleMenus =rolemenusTable.getAll("roleid",roleid) ;
        String roleMenusStr ="";
        for(Map menu: roleMenus){
            if(roleMenusStr.isEmpty())
                roleMenusStr = String.valueOf(menu.get("menuid"));
            else
                roleMenusStr =roleMenusStr +";"+menu.get("menuid");
        }
        outData odata =  creatOutDataMsg("getMenuForEdit");
        odata.addData("role",role);
        odata.addData("rolemenus",roleMenusStr);
        odata.addData("menus",menus);
        putOutData(odata);
    }
    private TLMsg roleEditSubmit(Object fromWho, TLMsg msg) {
        String roleid = (String) msg.getParam("roleid");
        String name = (String)msg.getParam("name");
        String menus = (String)msg.getParam("menus");
        String remark = (String)msg.getParam("remark");
        LinkedHashMap<String,Object> role = new LinkedHashMap<>();
        role.put("name",name);
        role.put("remark",remark);
        rolesIndb.update(roleid,role);
        List subMenus= TLDataUtils.splitStrToList(menus,";");
        List newMenus = new ArrayList() ;
        if(subMenus !=null)
        {
           for(int i=0 ;i < subMenus.size() ; i++){
               newMenus.add(Integer.parseInt(String.valueOf(subMenus.get(i))));
           }
        }
        ArrayList<Map<String, Object>> roleMenus =rolemenusTable.getAll("roleid",roleid) ;
        ArrayList oldMenus =new ArrayList() ;
        if(roleMenus !=null && !roleMenus .isEmpty()){
            for(Map menu: roleMenus){
               oldMenus.add(menu.get("menuid"));
            }
        }
        List<Integer> addMenus = (List) CollectionUtils.subtract(newMenus,oldMenus);
        List<Integer> deleteMenus = (List) CollectionUtils.subtract(oldMenus,newMenus);
        if(addMenus !=null && !addMenus.isEmpty())
            addRoleMenu( roleid , addMenus);
        if(deleteMenus !=null && !deleteMenus.isEmpty())
        {
            for(int menuid : deleteMenus)
                deleteMenu(roleid,menuid) ;
        }
        return putResultToClient( true,"roleEditSubmit");
    }
    public void addActionRole(String roleid, int menuid) {
        Map<String,Object> menu =menusTable.get(menuid);
        if(menu ==null)
            return;
        String href = (String) menu.get("href");
        addRoleByHref(href,roleid);
        String relatedurls = (String) menu.get("relatedurls");
        if(relatedurls ==null || relatedurls.isEmpty())
            return;
        String[] urlsArray =TLDataUtils.splitStrToArray(relatedurls,";");
        for(int i=0 ; i< urlsArray.length ; i++){
            String  urlid =urlsArray[i];
            addRoleByHref(urlid, roleid) ;
        }
    }
    public void deleteActionRole(String roleid, int menuid) {
        Map<String,Object> menu =menusTable.get(menuid);
        if(menu ==null)
            return;
        String href = (String) menu.get("href");
        deleteRoleByHref(href,roleid);
        String relatedurls = (String) menu.get("relatedurls");
        if(relatedurls ==null || relatedurls.isEmpty())
            return;
        String[] urlsArray =TLDataUtils.splitStrToArray(relatedurls,";");
        for(int i=0 ; i< urlsArray.length ; i++){
            String  urlid =urlsArray[i];
            deleteRoleByHref(urlid, roleid) ;
        }
    }
    private void addRoleByHref(String href, String roleid) {
        if(href==null || href.isEmpty())
            return;
        if( roleid==null ||  roleid.isEmpty())
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
        addRoleToActiond(actionid,roleid) ;
        setPolicyForAction(actionid,authModule) ;
    }
    private void deleteRoleByHref(String href, String roleid) {
        if(href==null || href.isEmpty())
            return;
        if( roleid==null ||  roleid.isEmpty())
            return;
        Map<String,Object> url =urlsTable.get(href);
        String actionid = (String) url.get("actionid");
        if(actionid ==null || actionid.isEmpty())
            return;
        deleteRoleToActiond(actionid,roleid) ;
        setPolicyForAction(actionid,authModule) ;
    }
    // 增加模块方法的访问角色
    private void addRoleToActiond(String actionid, String roleid) {
        LinkedHashMap<String ,Object> params =new LinkedHashMap<>() ;
        params.put("actionid",actionid) ;
        params.put("roleid",roleid) ;
        actionPoliciesTable.replace(params);
    }
    private void deleteRoleToActiond(String actionid, String roleid) {
        LinkedHashMap<String ,Object> params =new LinkedHashMap<>() ;
        params.put("roleid",roleid) ;
        params.put("actionid",actionid) ;
        actionPoliciesTable.remove(params);
    }
    private void setPolicyForAction(Object fromWho, TLMsg msg) {
         String actionid = (String) msg.getParam("actionid");
         setPolicyForAction(actionid,authModule) ;
    }

    //动态设置模块的action策略
    public void   setPolicyForAction(String actionid,String authModule){
        String roles = getPolicyRolesByActione(actionid);
        HashMap<String,String>  authPolicy =new HashMap<>() ;
        if( roles !=null && ! roles.isEmpty())
            authPolicy.put("role",roles) ;
        String users =getPolicyUserByActione(actionid);
        if(users !=null && !users.isEmpty())
            authPolicy.put("user","*;"+users) ;
        updateActionPolicyInDb(actionid,  authPolicy);
        TLBaseModule authModuleObj = (TLBaseModule) getModule(authModule);
        if(authModuleObj ==null)
            return;
        TLMsg msg ;
        if(authPolicy.isEmpty())
            msg =createMsg().setAction(AUTH_DELETEPOLICY).setParam(AUTH_P_POLICYNAME,actionid) ;
       else
           msg =createMsg().setAction(AUTH_SETPOLICY).setParam(AUTH_P_POLICYNAME,actionid)
                .setParam(AUTH_P_POLICYVALUE,authPolicy) ;
        putMsg(authModuleObj,msg) ;
    }
    protected void updateActionPolicyInDb(String actionid, HashMap<String,String>  authPolicy){
        TLBaseModule authModuleObj = (TLBaseModule) getModule(authModule);
        if(authModuleObj ==null)
            return;
        String  policiesName=authModuleObj.getFieldNameInApp("policies");
        MapInDB mapInDB =TLDataInDBUtils.getMapInDBModule(policiesName,moduleFactory,0L);
        if(authPolicy.isEmpty())
            mapInDB.remove(actionid) ;
        else
           mapInDB.put(actionid,authPolicy) ;
    }
     protected void getPolicyFromModuleToDB(String authModule){
         TLBaseModule authModuleObj = (TLBaseModule) getModule(authModule);
         if(authModuleObj ==null)
             return;
         TLMsg returnMsg =putMsg(authModule,createMsg().setAction(AUTH_GETPOLICY));
         Map policys =returnMsg.getArgs();
         String  policiesName=authModuleObj.getFieldNameInApp("policies");
         MapInDB mapInDB =TLDataInDBUtils.getMapInDBModule(policiesName,moduleFactory,0L);
         mapInDB.putAll(policys) ;
     }
    public String getPolicyRolesByActione(String actionid) {
        actionPoliciesTable =new BeanTable(actionPoliciesTableName,null,getFactory());
        LinkedHashMap<String ,Object> params =new LinkedHashMap<>() ;
        params.put("actionid",actionid) ;
        ArrayList<Map<String,Object>> roleList =  actionPoliciesTable.getAll("actionid",actionid);
        String roles="";
        for(Map<String,Object> map : roleList){
            String  roleid = (String) map.get("roleid");
            if(roleid ==null || roleid.isEmpty())
                continue;
            if(roles.isEmpty())
                roles= roleid;
            else
                roles= roles+";"+roleid;
        }
        return roles ;
    }
    public String getPolicyUserByActione(String actionid) {
        LinkedHashMap<String ,Object> params =new LinkedHashMap<>() ;
        params.put("actionid",actionid) ;
        ArrayList<Map<String,Object>> userList =  actionUserTable.getAll("actionid",actionid);
        if(userList ==null || userList.isEmpty())
            return null ;
        String users="";
        for(Map<String,Object> map : userList){
            String  userid = (String) map.get("userid");
            if(userid ==null || userid.isEmpty())
                continue;
            if(users.isEmpty())
                users= userid;
            else
                users= users+";"+userid;
        }
        return users ;
    }
}

