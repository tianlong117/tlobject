package cn.tianlong.tlobject.servletutils.view;

import java.util.HashMap;

/**
 * 创建日期：2020/9/1721:32
 * 描述:
 * 作者:tianlong
 */
public class TLHtmlFormUtils {

    public static TLBaseHtmlFormUnit  getFormUnit(String type ){
        TLBaseHtmlFormUnit obj = null;
        switch (type){
            case "form" :
                obj =new TLHtmlForm();
                break;
            case "input" :
                obj = new TLHtmlFormInput();
                break;
        }
        return  obj ;
    }
   public static TLBaseHtmlFormUnit  getFormUnit(String type ,HashMap<String ,String> params){
       TLBaseHtmlFormUnit obj = getFormUnit(type);
       if(obj !=null)
          obj.setParam(params);
       return  obj ;
   }
}
