package cn.tianlong.java.application.webmanager;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.List;

public class adminMemberControl extends TLWServModule {
    public adminMemberControl(){
        super();
    }
    public adminMemberControl(String name ){
        super(name);
    }
    public adminMemberControl(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected TLBaseModule init() {
        return this ;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "memberList":
                memberList(fromWho,msg);
                break;
            case "changePassword":
                changePassword(fromWho,msg);
                break;
            case "changepwdSubmit":
                changepwdSubmit(fromWho,msg);
                break;
            case "memberdelete":
                memberdelete(fromWho,msg);
                break;
            default:
                putMsg("error",creatOutMsg().setAction("setError").setParam("content","no action"));
        }
        return returnMsg ;
    }

    private void memberdelete(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        putMsg("membersModel",createMsg().setAction("delete")
                .setParam("userid",userid));
        outData outData =creatOutDataMsg("memberdelete");
        outData.addData("code","0");
        outData.addData("message","用户删除："+userid);
        putOutData(outData);
    }

    private void changepwdSubmit(Object fromWho, TLMsg msg) {
        String userid = (String) msg.getParam("userid");
        String repass = (String) msg.getParam("repass");
        String newpass = (String) msg.getParam("newpass");
        outData outData =creatOutDataMsg("changepwdSubmit");
        if(newpass.equals(repass))
        {
            String newPassword= DigestUtils.md5Hex(repass+userid);
            putMsg("membersModel",createMsg().setAction("changePassword")
                    .setParam("password",newPassword).setParam("userid",userid));
            outData.addData("code","0");
            outData.addData("message","更改成功");
        }
        else
        {
            outData.addData("code","1");
            outData.addData("message","更改失败");
        }
        putOutData(outData);
    }

    private void changePassword(Object fromWho, TLMsg msg) {
        outData outData =creatOutDataMsg("changePassword");
        outData.addData("userid",msg.getParam("userid"));
        outData.addData("username",msg.getParam("username"));
        putOutData(outData);

    }

    private void memberList(Object fromWho, TLMsg msg) {
        String username = (String) msg.getParam("username");
        int start=0;
        if(msg.getParam("start")!=null)
           start = (int) msg.getParam("start");
        String userid = (String) msg.getParam("userid");
        String loginpasswd =(String) msg.getParam("password");
        TLMsg getMsg =createMsg().setAction("userList")
                .setParam("start",start)
                .setParam("username",username);
        TLMsg resultMsg =putMsg("membersModel",getMsg);
        List datas = (List) resultMsg.getParam("result");
        outData odata =  creatOutDataMsg("memberlist");
        odata.addData("members",datas);
        putOutData(odata);
    }


}
