package cn.tianlong.tlobject.db;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLDBFieldExpression {
    private String value ;

    public TLDBFieldExpression(String value) {
        this.value = value;
    }
    public String getValue(){
        return value ;
    }
}
