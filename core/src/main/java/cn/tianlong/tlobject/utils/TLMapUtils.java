package cn.tianlong.tlobject.utils;

import java.util.*;

public class TLMapUtils {
    public static boolean isNull(Map map,String param )
    {
        if(map ==null)
            return  true;
        if( !map.containsKey(param))
            return  true;
        Object value = map.get(param);
        if(value==null)
            return  true ;
        return false ;
    }
    public static Object getValue(Map map,String param ,Object defaultValue)
    {
        if(map == null || !map.containsKey(param))
            return  defaultValue;
        Object value = map.get(param);
        if(value == null)
            return  defaultValue ;
        return value ;
    }
    public static Long getLongValue(Map map,String param ,Long defaultValue)
    {
         Object value  =getValue( map, param ,defaultValue);
         if(value instanceof  Long)
             return (Long) value ;
         return defaultValue ;
    }
    public static Double getDoubleValue(Map map,String param ,Double defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value instanceof Double )
            return (Double)value;
        return defaultValue ;
    }
    public static String getStringParam(Map map,String param ,String defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value instanceof String )
            return (String)value;
        return defaultValue ;
    }
    public static int getIntParam(Map map,String param ,int defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value instanceof Integer )
            return (int)value;
        return defaultValue ;
    }
    public static boolean getBooleanParam(Map map,String param ,boolean defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value instanceof Boolean )
            return (Boolean) value;
        return defaultValue ;
    }
    public static byte getByeParam(Map map,String param ,byte defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value instanceof Byte )
            return (byte) value;
        return defaultValue ;
    }
    public static Map getMapParam(Map map,String param ,Map defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value instanceof Map )
            return (Map) value;
        return defaultValue ;
    }
    public static List getListParam(Map map,String param , List defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value instanceof List )
            return (List) value;
        return defaultValue ;
    }
    public static Set getSetParam(Map map,String param , Set defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value instanceof Set )
            return (Set) value;
        return defaultValue ;
    }
    public static Object getArrayParam(Map map,String param , Object defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value !=null && value.getClass().isArray() )
            return  value;
        return defaultValue ;
    }
    public static boolean parseBoolean(Map map,String param ,boolean defaultValue)
    {
        Object value  =getValue( map, param ,defaultValue);
        if (value instanceof Boolean )
            return (boolean)value;
        else if(value instanceof String )
            return Boolean.parseBoolean((String)value) ;
        else if(value instanceof Integer )
        {
            if((int)value ==0)
                return false ;
            else
                return true ;
        }
        else
            return defaultValue ;
    }
}
