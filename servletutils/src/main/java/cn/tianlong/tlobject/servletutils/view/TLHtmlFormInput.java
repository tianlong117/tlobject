package cn.tianlong.tlobject.servletutils.view;

/**
 * 创建日期：2020/9/1721:03
 * 描述:
 * 作者:tianlong
 */
public class TLHtmlFormInput extends TLBaseHtmlFormUnit {
    public String type ;
    public TLHtmlFormInput() {
        super();
    }
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
    @Override
    public String toString(){
        String  superStr =super.toString();
        StringBuilder sb = new StringBuilder();
        sb.append("<input ");
        if(type !=null)
            sb.append(" type=\""+type+"\"");
        if(superStr !=null)
           sb.append(superStr) ;
        sb.append(" >") ;
        return sb.toString() ;
    }
}
