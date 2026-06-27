package cn.tianlong.tlobject.servletutils.clientinterface;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLModuleConfig;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;

import cn.tianlong.tlobject.servletutils.TLWServModule;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.templateresolver.ServletContextTemplateResolver;
import org.xmlpull.v1.XmlPullParser;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import static cn.tianlong.tlobject.servletutils.TLParamString.*;

public class TLThymeleafOutInterface extends TLBaseClientDataOutInterface {
    protected TemplateEngine templateEngine;
    protected String templatePath;
    protected HashMap<String, HashMap<String, String>> templates;

    public TLThymeleafOutInterface() {
        super();
    }

    public TLThymeleafOutInterface(String name) {
        super(name);
    }

    public TLThymeleafOutInterface(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }

    @Override
    protected void initProperty() {
        super.initProperty();
        if (this.params != null && this.params.get("templatePath") != null) {
            this.templatePath = (String) this.params.get("templatePath");
            params.remove("templatePath");
        }
    }

    @Override
    protected TLBaseModule init() {
        ServletContext ervletContext = getContext();
        ServletContextTemplateResolver templateResolver = new ServletContextTemplateResolver(ervletContext);
        templateResolver.setTemplateMode("HTML");
        String dir ;
        if(templatePath !=null && !templatePath.isEmpty())
            dir =templatePath ;
        else
            dir="/WEB-INF/templates/";
        templateResolver.setPrefix(dir);
        templateResolver.setSuffix(".html");
        if (params.get("cacheTTL") != null && !params.get("cacheTTL").isEmpty()) {
            Long cacheTTl = Long.parseLong(params.get("cacheTTL"));
            templateResolver.setCacheTTLMs(cacheTTl);
        }
        templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        return this;
    }

    @Override
    protected Object setConfig() {
        myConfig config = new myConfig(configFile, moduleFactory.getConfigDir());
        mconfig = config;
        super.setConfig();
        templates = config.getTemplates();
        return config;
    }

    @Override
    protected TLMsg putContentToUser(Object fromWho, TLMsg msg) {
        String outContent = (String) msg.getParam(CLIENT_P_CONTENT);
        return responseWrite(outContent, (String) msg.getParam(CHARSET));
    }

    @Override
    protected TLMsg putDataToUser(Object fromWho, TLMsg msg) {
        outData outData = (TLWServModule.outData) msg.getParam(CLIENT_P_OUDDATA);
        String dataid = outData.getDataId();
        LinkedHashMap<String, Object> datas = (LinkedHashMap<String, Object>) outData.getParam(CLIENT_P_OUDDATA);
        boolean hastemp = true;
        String template = "";
        if (templates != null && dataid != null) {
            HashMap<String, String> dataHash = templates.get(dataid);
            if (dataHash != null) {
                template = dataHash.get("template");
                if (template == null)
                    hastemp = false;
            } else
                hastemp = false;
        } else
            hastemp = false;
        String outContent;
        if (hastemp == false) {
            StringBuilder buffer = new StringBuilder();
            for (String key : datas.keySet()) {
                String data = "";
                if (datas.get(key) != null)
                    data = datas.get(key).toString();
                buffer.append(data);
            }
            outContent = buffer.toString();
            return responseWrite(outContent, (String) msg.getParam(CHARSET));
        }
        HttpServletRequest request = getRequest();
        HttpServletResponse response = getResponse();
        String charset = (String) msg.getParam(CHARSET);
        if (charset == null)
            charset = "UTF-8";
        String contentType = "text/html;charset=" + charset;
        response.setContentType(contentType);
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);
        WebContext ctx = new WebContext(request, response, request.getServletContext(), request.getLocale());
        for (String key : datas.keySet()) {
            ctx.setVariable(key, datas.get(key));
        }
        try {
            PrintWriter printWriter = response.getWriter();
            templateEngine.process(template, ctx, printWriter);
        } catch (IOException e) {
            e.printStackTrace();
            putLog("get writer error", LogLevel.ERROR, "putDataToUser");
        }
        return null;
    }

    protected class myConfig extends TLModuleConfig {
        protected HashMap<String, HashMap<String, String>> templates;

        public myConfig(String configFile, String configDir) {
            super(configFile, configDir);
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
                    templates = getHashMap(xpp, "templates", "dataid");
                }

            } catch (Throwable t) {
                putLog("TLThymeleaf config parse error:" + t.toString(), LogLevel.WARN);
            }
        }

    }
}
