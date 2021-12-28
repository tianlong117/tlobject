package cn.tianlong.tlobject.servletutils.view;

import java.lang.reflect.Field;
import java.util.HashMap;

/**
 * 创建日期：2020/9/1720:47
 * 描述:
 * 作者:tianlong
 */
public class TLBaseHtmlFormUnit {
    private Class clazz ;
    public String id ;
    public String name ;
    public String value ;
    public String style ;
    public String tabindex ;
    public String hidden ;

    public TLBaseHtmlFormUnit() {

    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public String getTabindex() {
        return tabindex;
    }

    public void setTabindex(String tabindex) {
        this.tabindex = tabindex;
    }

    public String getHidden() {
        return hidden;
    }

    public void setHidden(String hidden) {
        this.hidden = hidden;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
    public TLBaseHtmlFormUnit setParam (String key,String value){
        if(clazz ==null)
            clazz = getClass() ;
        try {
            Field f=clazz.getField(key) ;
            if(f !=null)
                f.set(this, value);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this ;
    }
    public TLBaseHtmlFormUnit setParam (HashMap<String,String> params){
        if(clazz ==null)
            clazz = getClass() ;
        try {
            Field[] fields=clazz.getFields() ;
            for (Field field : fields){
                String fieldName = field.getName();
                if(params.containsKey(fieldName))
                     field.set(this, params.get(fieldName));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this ;
    }
    public TLBaseHtmlFormUnit setParam_O(String key ,String value){
        switch (key){
            case "id" :
                this.id = value ;
                break;
            case "name" :
                this.name = value ;
                break;
            case "value" :
                this.value= value ;
                break;
            case "style" :
                this.style = value ;
                break;
            case "tabindex" :
                this.tabindex = value ;
                break;
            case "hidden" :
                this.hidden = value ;
                break;
        }
        return this ;
    }
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if(id !=null)
            sb.append(" id=\""+id+"\"");
        if(name !=null)
            sb.append(" name=\""+name+"\"");
        if(value !=null)
            sb.append(" value=\""+value+"\"");
        if(style !=null)
            sb.append(" style=\""+style+"\"");
        if(tabindex !=null)
            sb.append(" tabindex=\""+tabindex+"\"");
        if(hidden !=null)
            sb.append(" "+hidden+" ");
        return sb.toString();
    }
}
