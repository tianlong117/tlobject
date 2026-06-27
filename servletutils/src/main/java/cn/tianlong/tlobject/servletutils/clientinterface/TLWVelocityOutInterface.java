package cn.tianlong.tlobject.servletutils.clientinterface;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.xmlpull.v1.XmlPullParser;
import javax.servlet.ServletContext;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Properties;

import static cn.tianlong.tlobject.servletutils.TLParamString.CHARSET;
import static cn.tianlong.tlobject.servletutils.TLParamString.CLIENT_P_CONTENT;
import static cn.tianlong.tlobject.servletutils.TLParamString.CLIENT_P_OUDDATA;
import static java.lang.Thread.sleep;

public class TLWVelocityOutInterface extends TLBaseClientDataOutInterface {
    protected VelocityEngine ve;
    protected  String prefixPath ;
    protected String templatePath ;
    protected HashMap<String, HashMap<String, String>> templates ;
    public TLWVelocityOutInterface(){
        super();
    }
    public TLWVelocityOutInterface(String name ){
        super(name);
    }
    public TLWVelocityOutInterface(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }
    @Override
    protected void initProperty() {
        super.initProperty();
        if (params != null ) {
            if (params.get("templatePath") != null) {
                templatePath = params.get("templatePath");
                params.remove("templatePath");
            }
            if (params.get("prefixPath") != null) {
                prefixPath = params.get("prefixPath");
                params.remove("prefixPath");
            }
        }
    }
    @Override
    protected TLBaseModule init(){
        String[] propertyItem={"resource.loader","input.encoding","output.encoding","file.resource.loader.class","file.resource.loader.cache"};
        String dir ;
        if(templatePath !=null && !templatePath.isEmpty())
            dir =templatePath ;
        else
        {
            ServletContext context=getContext();
            if(context !=null)
               dir=context.getRealPath("/WEB-INF/templates");
            else
            {
                putLog("velocity do not configure templatePath :" ,LogLevel.ERROR,"init");
                return this ;
            }
        }
        putLog("velocity path:"+dir ,LogLevel.DEBUG,"init");
        Properties properties = new Properties();
        properties.setProperty(VelocityEngine.FILE_RESOURCE_LOADER_PATH,dir);
        for (int i=0;i<propertyItem.length ;i++){
            if(params.get(propertyItem[i])!=null)
               properties.setProperty(propertyItem[i],params.get(propertyItem[i]));
        }
        ve = new VelocityEngine(properties);
        ve.init();
        return this ;
    }
    @Override
    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());
        mconfig=config;
        super.setConfig();
        templates=config.getTemplates();
        return config;
    }
    @Override
    protected TLMsg putContentToUser(Object fromWho, TLMsg msg) {
        String outContent = (String) msg.getParam(CLIENT_P_CONTENT);
        return  responseWrite(outContent, (String) msg.getParam(CHARSET));
    }

    @Override
    protected TLMsg putDataToUser(Object fromWho, TLMsg msg) {
        outData outData = (TLWServModule.outData) msg.getParam(CLIENT_P_OUDDATA);
        String dataid= outData.getDataId();
        LinkedHashMap<String, Object> datas =(LinkedHashMap<String, Object>)outData.getParam(CLIENT_P_OUDDATA);
        boolean hastemp=true;
        String template="";
        if(templates!=null && dataid!=null ){
            HashMap<String, String> dataHash=templates.get(dataid);
            if(dataHash!=null)
            {
                template =dataHash.get("template");
                if(template==null)
                    hastemp=false;
            }
            else
                hastemp=false;
        }
        else
            hastemp=false;

        String outContent ;
        if(hastemp==false)
        {
            StringBuilder buffer = new StringBuilder();
            for (String key : datas.keySet()) {
                String data ="";
                if(datas.get(key)!=null)
                   data =datas.get(key).toString();
                buffer.append(data);
            }
            outContent=buffer.toString();
        }
        else{
            if(prefixPath !=null)
            {
                if(!template.startsWith("/"))
                    template =prefixPath + template ;
            }
            Template tpl;
            try {
                tpl = ve.getTemplate(template);
            } catch (Exception e) {
               putLog("没有模板文件:"+template,LogLevel.ERROR);
               return null ;
            }
            putLog("dataid: "+dataid+" 模板文件:"+template,LogLevel.DEBUG);
            VelocityContext ctx = new VelocityContext();
            for (String key : datas.keySet()) {
                ctx.put(key,datas.get(key));
            }
            StringWriter sw = new StringWriter();
            tpl.merge(ctx,sw);
            outContent=sw.toString();
        }
        return  responseWrite(outContent,(String) msg.getParam(CHARSET));
    }
    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> templates ;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }
        public myConfig() {

        }
        public HashMap getTemplates() {
            return templates;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("templates")) {
                    templates= getHashMap(xpp,"templates","dataid");
                }

            } catch (Throwable t) {
                putLog("TLVelocity config parse error:" + t.toString(), LogLevel.WARN);
            }
        }

    }
}
