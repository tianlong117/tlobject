package cn.tianlong.tlobject.db;


import java.util.HashMap;

public interface TLBaseConnectorInterface {
    Object connect();
    void close(Object conn) ;

    /**
     * 日志用：去掉 JDBC URL 里可能带凭据的 query 部分（如 {@code ?user=x&password=y}）。
     * 各连接器原来在 connect 失败时直接打印完整 dburl + dbuser，会把敏感信息写进日志。
     */
    static String logSafeDbUrl(HashMap<String, String> params) {
        String url = (params == null) ? null : params.get("dburl");
        if (url == null || url.isEmpty())
            return "unknown";
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }
}
