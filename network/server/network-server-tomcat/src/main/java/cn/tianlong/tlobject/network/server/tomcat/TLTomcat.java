package cn.tianlong.tlobject.network.server.tomcat;

import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

import cn.tianlong.tlobject.modules.LogLevel;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

/**
 * 创建日期：2018/4/23 on 21:00
 * 描述:
 * 作者:tianlong
 */

public class TLTomcat extends TLBaseModule {
    protected  String host ="0.0.0.0";
    protected  int port =8080;
    protected  int httpsPort =8443;
    protected  Tomcat tomcat;
    protected  String serverName;
    protected  String resourceBase;
    protected  String contextPath ="/";
    protected  String webappDir ="/";
    protected  String baseDir="temp";
    protected  String sslCerFile ="/" ;
    protected  String sslCerFilePwd ="/" ;
    protected  String connector="http";
    protected  int minThreads =10;
    protected  int maxThreads =200 ;
    protected  int idleTimeout =30000 ;
    public TLTomcat(){
        super();
    }
    public TLTomcat(String name ){
        super(name);
    }
    public TLTomcat(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void setModuleParams()
    {
        if(params!=null  ){
            if( params.get("serverName")!=null)
                serverName = params.get("serverName");
            if( params.get("baseDir")!=null)
                baseDir = params.get("baseDir ");
            if( params.get("webappDir")!=null)
                webappDir = params.get("webappDir");
            if( params.get("connector")!=null)
                connector =params.get("connector");
            if( params.get(host)!=null)
                host =params.get(host);
            minThreads =(params.get("minThreads")!=null && !params.get("minThreads").isEmpty())
                    ?Integer.parseInt(params.get("minThreads")):minThreads;
            maxThreads =(params.get("maxThreads")!=null&& !params.get("maxThreads").isEmpty())
                    ?Integer.parseInt(params.get("maxThreads")):maxThreads;
            idleTimeout =(params.get("idleTimeout")!=null&& !params.get("idleTimeout").isEmpty())
                    ?Integer.parseInt(params.get("idleTimeout")):idleTimeout;
            port =(params.get("port")!=null && !params.get("port").isEmpty())
                    ?Integer.parseInt(params.get("port")):port;
            httpsPort =(params.get("httpsPort")!=null && !params.get("httpsPort").isEmpty())
                    ?Integer.parseInt(params.get("httpsPort")):httpsPort;
            if( params.get("resourceBase")!=null)
                resourceBase = params.get("resourceBase");
            if( params.get("contextPath")!=null)
                contextPath = params.get("contextPath");

            if( params.get(SSL_SCERFILE)!=null)
                sslCerFile =params.get(SSL_SCERFILE);
            if( params.get(SSL_SCERFILE_PWD)!=null)
                sslCerFilePwd =params.get(SSL_SCERFILE_PWD);

        }
    }
    @Override
    protected TLBaseModule init() {
        initServer();
        return this ;
    }

    protected void initServer() {
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir);
        tomcat.setPort(port);
        tomcat.addWebapp(contextPath, webappDir);
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        TLMsg returnMsg=null;
        switch (msg.getAction()) {
            case "run":
                returnMsg=run( fromWho,  msg);
                break;
            case "stop" :
                returnMsg=stop( fromWho,  msg);
            default:              ;
        }
        return returnMsg;
    }

    private TLMsg stop(Object fromWho, TLMsg msg) {
        if(tomcat !=null)
        {
            try {
                tomcat.stop();
                return createMsg().setParam(RESULT,true) ;
            } catch (Exception e) {
                e.printStackTrace();
                return createMsg().setParam(RESULT,false) ;
            }
        }
        return createMsg().setParam(RESULT,false) ;
    }

    private TLMsg run(Object fromWho, TLMsg msg)
    {
        try {
            tomcat.start();
        } catch (LifecycleException e) {
            putLog("tomcat server start up failure :"+host+ ":"+port,LogLevel.ERROR);
            return createMsg().setParam(RESULT,true);
        }
        putLog("tomcat server start up  :"+host+ ":"+port,LogLevel.INFO);
        tomcat.getServer().await();
        return createMsg().setParam(RESULT,true);
    }

    protected TLMsg destroy(Object fromWho, TLMsg msg) {
        stop(null, null) ;
        return  super.destroy(fromWho,msg);
    }

}
