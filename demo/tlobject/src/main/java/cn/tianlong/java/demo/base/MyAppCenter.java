package cn.tianlong.java.demo.base;

import cn.tianlong.tlobject.base.*;

import java.util.HashMap;

public class MyAppCenter extends TLBaseModule {
    public MyAppCenter(String name ){
        super(name);
    }
    public MyAppCenter(String name, TLObjectFactory moduleFactory)  {
        super(name,moduleFactory);
    }

    @Override
    protected TLBaseModule init() {
        return this;
    }

    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg = null;
        switch (msg.getAction()){
            case "testmsg" :
                testmsg( fromWho ,msg) ;
            default :
                System.out.println("no action");
        }
        return returnMsg;
    }

    private void testmsg(Object fromWho, TLMsg msg) {
         TLMsg mymsg =createMsg();
         mymsg.setParam("p1","dongqiang") ;
         mymsg.setParam("p2" ,111);
         Light light = (Light) getModule("light");
         mymsg.setParam("light",light);
         String p1 = (String) mymsg.getParam("p1" ,String.class);
        String p2 = (String) mymsg.getParam("p2" ,String.class);
         Light mylight1 = (Light) mymsg.getParam("light",Light.class);
         if(mylight1 !=null)
             println(mylight1.getClass().toString());
         else
             println("mylight1 is null");
         mylight1 = (Light) mymsg.getParam("light",IObject.class);
        if(mylight1 !=null)
            println(mylight1.getClass().toString());
        else
            println("mylight1 is null");
         Person person = (Person) mymsg.getParam("light",Person.class);
        if(person !=null)
            println(person.getClass().toString());
        else
            println("person is null");
         Light mylight2 = (Light) mymsg.getParam("light",House.class);
        if(mylight2 !=null)
            println(mylight2.getClass().toString());
        else
            println("mylight2 is null");
        HashMap<String,Object> p4 = new HashMap<>();
        msg.setParam("map",p4);
        HashMap<String,Object> map = (HashMap<String, Object>) msg.getParam("map",HashMap.class);
        msg.setParam("map","123");
        map = (HashMap<String, Object>) msg.getParam("map",HashMap.class);
        if( map !=null)
            println( map.getClass().toString());
        else
            println("mylight2 is null");

    }


}
