package cn.tianlong.tlobject.servletutils.clientinterface;

import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import cn.tianlong.tlobject.servletutils.TLWServModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;

import static cn.tianlong.tlobject.servletutils.TLParamString.CHARSET;

public class TLWJsonDataOutInterface extends TLWDirectOutInterface {
    // Gson 实例线程安全，可跨请求共享；避免每次序列化重建 Gson/TypeAdapter 缓存
    private final Gson gson = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .serializeNulls()
            .create();
    public TLWJsonDataOutInterface(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected String responseContentType() {
        return "application/json";
    }

    /** 序列化兜底：循环引用等会让 Gson 抛 StackOverflowError，不能让它逃出输出接口 */
    protected String toJson(Object value) {
        try {
            return gson.toJson(value);
        } catch (Throwable t) {
            try {
                putLog("JSON 序列化失败: " + t, LogLevel.ERROR, "toJson");
            } catch (Throwable ignored) {
            }
            return "{\"error\":\"response serialization failed\"}";
        }
    }

    @Override
    protected TLMsg putContentToUser(Object fromWho, TLMsg msg) {
        String jsonString = toJson(msg.getParam("content"));
        msg.setParam("content",jsonString);
     return    super.putContentToUser(fromWho,  msg);
    }
    @Override
    protected TLMsg putDataToUser(Object fromWho, TLMsg msg) {
        Object odObj = msg.getParam("outData");
        if (!(odObj instanceof TLWServModule.outData)) {   // 调用方漏传 outData 时不要 NPE
            putLog("putDataToUser: outData 参数缺失或类型错误", LogLevel.ERROR, "putDataToUser");
            return null;
        }
        outData outData = (TLWServModule.outData) odObj;
        // 可选 HTTP 状态码（如未登录 401）：msg 带 httpStatus 参数时设置，无则默认 200
        Object httpStatus = msg.getParam("httpStatus");
        if (httpStatus != null) {
            int code = -1;
            if (httpStatus instanceof Number) code = ((Number) httpStatus).intValue();
            else {
                try { code = Integer.parseInt(String.valueOf(httpStatus)); } catch (NumberFormatException ignored) {}
            }
            HttpServletResponse response = getResponse();
            if (code > 0 && response != null) response.setStatus(code);
        }
        // 业务方可能把 outData 里的 "outData" 换成别的 Map 实现：按接口类型取，别强转 LinkedHashMap
        Object datasObj = outData.getParam("outData");
        Object datas = datasObj instanceof Map ? datasObj : null;
        String  outContent = toJson(datas);
        return  responseWrite(outContent,(String) msg.getParam(CHARSET));
    }
}
