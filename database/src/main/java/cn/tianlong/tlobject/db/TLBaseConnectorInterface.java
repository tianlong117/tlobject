package cn.tianlong.tlobject.db;




public interface TLBaseConnectorInterface {
    Object connect();
    void close( Object conn) ;
}
