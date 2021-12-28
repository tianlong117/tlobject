package cn.tianlong.tlobject.servletutils.view;

import java.util.ArrayList;
import java.util.List;

/**
 * 创建日期：2020/9/1720:47
 * 描述:
 * 作者:tianlong
 */
public class TLHtmlForm extends TLBaseHtmlFormUnit{
    public String action ;
    public String method ;
    public String name ;
    public String target ;
    public String onreset ;
    public String onsubmit;
    public String enctype ;
    protected List<TLBaseHtmlFormUnit> formUnits =new ArrayList<>();

    public TLHtmlForm() {
    }
    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getOnreset() {
        return onreset;
    }

    public void setOnreset(String onreset) {
        this.onreset = onreset;
    }

    public String getOnsubmit() {
        return onsubmit;
    }

    public void setOnsubmit(String onsubmit) {
        this.onsubmit = onsubmit;
    }

    public String getEnctype() {
        return enctype;
    }

    public void setEnctype(String enctype) {
        this.enctype = enctype;
    }

    public List<TLBaseHtmlFormUnit> getFormUnits() {
        return formUnits;
    }

    public void setFormUnits(List<TLBaseHtmlFormUnit> formUnits) {
        this.formUnits = formUnits;
    }
    public void addFormUnits(TLBaseHtmlFormUnit formUnit) {
        this.formUnits.add(formUnit) ;
    }
    public String formHeader(){
        StringBuilder sb = new StringBuilder();
            sb.append("<form ");
            if(action !=null)
                sb.append(" action=\""+action+"\"");
            if(method !=null)
                sb.append(" method=\""+method+"\"");
            if(name !=null)
                sb.append(" name=\""+name+"\"");
            if(target !=null)
                sb.append(" target=\""+target+"\"");
            if(enctype !=null)
                sb.append(" enctype=\""+enctype+"\"");
            if(onreset !=null)
                sb.append(" onreset=\""+onreset+"\"");
            if(onsubmit !=null)
                sb.append(" onsubmit=\""+onsubmit+"\"");
            sb.append(" >\r\n");
            return sb.toString();
    }
    public String formEnd() {
       return " </form> \r\n";
    }

    @Override
    public String toString() {
        if(formUnits.isEmpty())
           return formHeader();
        StringBuilder sb = new StringBuilder();
        sb.append(formHeader());
        sb.append("<br>\r\n");
        for(TLBaseHtmlFormUnit unit : formUnits){
            sb.append(unit.toString());
            sb.append("<br>\r\n");
        }
        sb.append(formEnd());
        return  sb.toString() ;
    }
}
