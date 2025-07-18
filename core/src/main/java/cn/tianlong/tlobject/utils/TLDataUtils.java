package cn.tianlong.tlobject.utils;

import com.google.gson.internal.LinkedTreeMap;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.tianlong.tlobject.base.TLParamString.FENHAO;

public class TLDataUtils {

    public static Object parseByType(Object value  ,Class classType,Object defaultValue)
    {
        if (value == null)
            return defaultValue ;
        if(classType.isInstance(value))
            return value ;
        else
            return defaultValue ;
    }

    public static boolean parseBoolean(Object value ,boolean defaultValue)
    {
        if(value ==null)
            return defaultValue ;
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
    public static int parseInt(Object value ,int defaultValue)
    {
        if (value !=null  )
        {
            if( value instanceof Integer)
                return (int) value;
            else if (value instanceof String && !((String) value).isEmpty())
                return Integer.parseInt((String) value);
            else if (value instanceof Double )
                return  ((Double)value).intValue();
            else if ( value instanceof Long)
                return  ((Long)value).intValue();
            else
                return defaultValue ;
        }
        else
            return defaultValue ;
    }
    public static Long parseLong(Object value ,Long defaultValue)
    {
        if (value !=null  )
        {
            if( value instanceof Long)
                return (Long) value;
            else if (value instanceof String && !((String) value).isEmpty())
                return Long.parseLong((String) value);
            else if (value instanceof Double )
                return  ((Double)value).longValue();
            else if (value instanceof Integer )
                return  ((Integer)value).longValue();
            else
                return defaultValue ;
        }
        else
            return defaultValue ;
    }
    public static Double parseDouble(Object value ,Double defaultValue)
    {
        if (value !=null  )
        {
            if( value instanceof Double)
                return (Double) value;
            else if (value instanceof String && !((String) value).isEmpty())
                return Double.parseDouble((String) value);
            else if (value instanceof Integer )
                return  ((Integer)value).doubleValue();
            else if (value instanceof Long )
                return  ((Long)value).doubleValue();
            else
                return defaultValue ;
        }
        else
            return defaultValue ;
    }
    public static String parseString(Object value ,String defaultValue)
    {
        if (value !=null  )
        {
            if( value instanceof String)
                return (String) value;
            else
                return String.valueOf(value);
        }
        else
            return defaultValue ;
    }
    public static Long getLongParam( Object value ,Long defaultValue)
    {
       if (value !=null && value  instanceof Long )
            return (Long)value;
        return defaultValue ;
    }
    public static Double getDoubleParam( Object value ,Double defaultValue)
    {
       if (value !=null && value  instanceof Double )
            return (Double)value;
        return defaultValue ;
    }
    public static String getStringParam( Object value ,String defaultValue)
    {
       if (value !=null && value instanceof String )
            return (String)value;
        return defaultValue ;
    }
    public static int getIntParam( Object value ,int defaultValue)
    {
        if (value !=null && value instanceof Integer )
            return (int)value;
        return defaultValue ;
    }
    public static boolean getBooleanParam( Object value ,boolean defaultValue)
    {
        if (value !=null && value  instanceof Boolean )
            return (Boolean) value;
        return defaultValue ;
    }
    public static byte getByteParam( Object value ,byte defaultValue)
    {
        if (value !=null && value  instanceof Byte )
            return (byte) value;
        return defaultValue ;
    }
    public static Map getMapParam( Object value ,Map defaultValue)
    {
        if (value !=null && value  instanceof Map )
            return (Map) value;
        return defaultValue ;
    }
    public static List getListParam( Object value , List defaultValue)
    {
        if (value !=null && value  instanceof List )
            return (List) value;
        return defaultValue ;
    }
    public static Set getSetParam( Object value , Set defaultValue)
    {
       if (value !=null && value  instanceof Set )
            return (Set) value;
        return defaultValue ;
    }
    public static Object getArrayParam( Object value , Object defaultValue)
    {
       if (value !=null  && value.getClass().isArray() )
            return  value;
        return defaultValue ;
    }

    public  static ArrayList mapListToList(List<Map<String, Object>> mapList, String key){
        ArrayList list = new ArrayList();
        for(Map map :mapList) {
            list.add(map.get(key));
        }
        return  list ;
    }
    public  static ArrayList<Integer> longTypeToIntList(List<Long> LongTypeList){
        ArrayList<Integer> list =new ArrayList() ;
        for(Long value: LongTypeList){
            list.add(Math.toIntExact(value));
        }
        return list ;
    }
    public static  String utf8Togb2312(String str){
        StringBuffer sb = new StringBuffer();
        for(int i=0; i<str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '+':
                    sb.append(' ');
                    break;
                case '%':
                    try {
                        sb.append((char)Integer.parseInt(
                                str.substring(i+1,i+3),16));
                    }
                    catch (NumberFormatException e) {
                        throw new IllegalArgumentException();
                    }
                    i += 2;
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        // Undo conversion to external encoding
        String result = sb.toString();
        String res=null;
        try{
            byte[] inputBytes = result.getBytes("8859_1");
            res= new String(inputBytes,"UTF-8");
        }
        catch(Exception e){}
        return res;
    }
    public static   String utf82gbk(String str){
        String utf8 = null;
        try {
            utf8 = new String(str.getBytes( "UTF-8"));
            String unicode = new String(utf8.getBytes(),"UTF-8");
            return new String(unicode.getBytes("GBK"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return null ;
    }
    public static   String nullToString(String value,String defaultValue){
        if(value ==null || value.isEmpty())
            return defaultValue;
        else
            return value ;
    }
    public  static  ArrayList<LinkedHashMap>  treeMapToLinkMap(ArrayList<LinkedTreeMap> list){
        ArrayList<LinkedHashMap>  newList =new ArrayList<>();
        for(LinkedTreeMap map :list){
            LinkedHashMap newMap =treeMapToLinkMap(map);
            newList.add(newMap) ;
        }
        return newList ;
    }
    public  static  LinkedHashMap  treeMapToLinkMap(LinkedTreeMap map){
        LinkedHashMap newMap = new LinkedHashMap<>();
        newMap.putAll(map);
        return newMap ;
    }
    public  static Map LinkMapToMap(LinkedHashMap map ,String mapTyep){
        if(mapTyep.equals("HashMap"))
           return LinkMapToHashMap( map);
        else if(mapTyep.equals("ConcurrentHashMap"))
            return LinkMapToConcurrentHashMap(map);
        else
           return  map ;
    }
    public  static  HashMap  LinkMapToHashMap(LinkedHashMap map){
        HashMap newMap = new HashMap<>();
        newMap.putAll(map);
        return newMap ;
    }
    public  static ConcurrentHashMap LinkMapToConcurrentHashMap(LinkedHashMap map){
        ConcurrentHashMap newMap = new ConcurrentHashMap<>();
        newMap.putAll(map);
        return newMap ;
    }
    public  static  Map<String, String>  mapToStrMap(Map<String,Object> map){
        HashMap<String ,String> newMap = new HashMap<>();
        for(String key : map.keySet()){
            Object value = map.get(key);
            if(value ==null)
            {
                newMap.put(key, "");
                continue;
            }
            if(value instanceof Integer|| value instanceof  Long || value instanceof  Double)
                value=String.valueOf(value);
            else if (value instanceof Date){
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                value = df.format(value);
            }
            else
                value= value.toString();
            newMap.put(key, (String) value);
        }
        return newMap ;
    }
    public  static ArrayList<HashMap<String,String>>  listObjectMapTolistStrMap(ArrayList<Map<String, Object>> list){
        ArrayList<HashMap<String,String>> resultList = new ArrayList<>();
        for(Map<String, Object> map :list){
            HashMap<String,String> tmpMsp = (HashMap<String, String>) mapToStrMap(map);
            resultList.add(tmpMsp);
        }
        return resultList ;
    }
    public  static  Map<String, Object> jsonDoubleToInt(Map<String,Object> map, String... keys){
        for(String key :keys){
           if(map.containsKey(key))
           {
               Double value = (Double) map.get(key);
               map.put(key, value.intValue());
           }
        }
        return  map ;
    }
    public  static ArrayList<Integer> jsonListToInt(List<Double> list){
         ArrayList<Integer> newList =new ArrayList<>();
        for (Double value : list) {
            newList.add(((Double)value).intValue());
        }
        return  newList ;
    }
    public  static  Map<String, Object> jsonDoubleToInt(Map map){
        for (Object key : map.keySet()) {
            Object value = map.get(key);
            if(value instanceof Double  )
            {
                if((Double)value <Integer.MAX_VALUE)
                {
                    String str = String.valueOf(value);
                    if(str.substring(str.indexOf(".") + 1).equals("0"))
                        map.put(key, ((Double)value).intValue());
                }
                else if ((Double)value <Long.MAX_VALUE){
                    map.put(key, ((Double)value).longValue());
                }
            }
            else if( value instanceof Map)
                 jsonDoubleToInt((Map)value) ;
            else if( value instanceof List)
                jsonDoubleToInt((List) value) ;
        }
        return  map ;
    }
    public  static List<Map<String,Object>> jsonDoubleToInt(List mapList, String... keys){
        for(Object map :mapList) {
            if (!(map instanceof Map)) {
                continue;
            }
            for (String key : keys) {
                if (((Map)map).containsKey(key)) {
                    Double value = (Double)(((Map)map).get(key));
                    ((Map)map).put(key, value.intValue());
                }
            }
        }
        return  mapList ;
    }
    public  static List  jsonDoubleToInt(List mapList){
        if( mapList ==null || mapList.isEmpty())
            return mapList;
        for(Object value :mapList) {
            if(value instanceof Map)
               jsonDoubleToInt((Map)value);
        }
        return  mapList ;
    }
    public static String[] splitStrToArray (String str ,String separator){
        if(str ==null || str.isEmpty())
            return null ;
        if(separator ==null)
            separator=FENHAO  ;
        String strArray[] = str.trim().split(separator);
        if(strArray.length ==0)
            return null ;
        for (int i = 0; i < strArray.length; i++)
           strArray[i] =strArray[i].trim() ;
        List<String> tmp = new ArrayList<String>();//新建List
        for(String unit:strArray){
            if(unit!=null && !unit.isEmpty()){
                tmp.add(unit);
            }
        }
        String [] array= tmp.toArray(new String[tmp.size()]);
        return array ;
    }
    public static String listToString(List<String> list, String separator) {
        if(list ==null || list.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i));
            if (i < list.size() - 1) {
                sb.append(separator);
            }
        }
        return sb.toString();
    }
    public static ArrayList splitStrToList (String str ,String separator){
        String strArray[] = splitStrToArray(str ,separator);
        if(strArray ==null || strArray.length ==0)
            return null ;
        ArrayList<String> list = new ArrayList<>();
        for (int i=0 ;i<strArray.length ;i++)
        {
            String value =strArray[i] ;
            if(value !=null && !value.isEmpty())
                list.add(strArray[i]);
        }
        return list ;
    }
    public static ArrayList<Integer> splitStrToIntgerList (String str ,String separator){

        ArrayList<String> list =splitStrToList(str,separator);
        if(list ==null || list.isEmpty())
            return null ;
        ArrayList<Integer> retunList = new ArrayList() ;
        for(int i=0 ;i < list.size() ; i++){
            retunList.add(Integer.parseInt(String.valueOf(list.get(i))));
        }
        return retunList ;
    }
    public static HashMap<String ,String> splitStrToMap (String str ,String separator){
        String strArray[] = splitStrToArray(str ,separator);
        if(strArray ==null || strArray.length ==0)
            return null ;
        HashMap<String ,String> map =new HashMap<>() ;
       for(int i=0 ;i<strArray.length; i++){
           String strunit = strArray[i] ;
           String strunitarray[] = splitStrToArray(strunit ,"=");
           if(strunitarray.length ==2)
             map.put(strunitarray[0],strunitarray[1]) ;
           else
               map.put(strunitarray[0],null) ;
       }
        return map ;
    }
    public static LinkedHashMap mapSortByKey(Map map ,Boolean ifDescend){
        Set set=map.keySet();
        Object[] arr=set.toArray();
        if (ifDescend ==false)
           Arrays.sort(arr);
        else
            Arrays.sort(arr, Collections.reverseOrder());
        LinkedHashMap sortMap =new LinkedHashMap() ;
        for(Object key:arr){
            sortMap.put(key,map.get(key));
        }
        return sortMap ;
    }
    public static boolean isAlphaNumeric(String s){
        Pattern p = Pattern.compile("[0-9a-zA-Z]{1,}");
        Matcher m = p.matcher(s);
        return m.matches();
    }

    public static List<Map> toTree(List<Map> treeList, Long pid) {
        List<Map> retList = new ArrayList<Map>();
        for (Map parent : treeList) {
            if (pid.equals(parent.get("pid"))) {
                retList.add(findChildren(parent, treeList));
            }
        }
        return retList;
    }
    private static Map findChildren(Map parent, List<Map> treeList) {
        for (Map child : treeList) {
            if (parent.get("id").equals(child.get("pid"))) {
                if (parent.get("child") == null) {
                    parent.put("child",new ArrayList<>());
                }
                List<Map> childList = (List<Map>) parent.get("child");
                childList.add(findChildren(child, treeList));
            }
        }
        return parent;
    }
    public static Map<Object, Map<String, Object>> mapDataToOrder(Map<String, Map<String, Object>> totaldatas) {
        Map<Object, Map<String, Object>> orderData = new TreeMap<>();
        orderData.putAll(totaldatas);
        return orderData;
    }

    public static List listMapDataByOrder(ArrayList<Object> totaldatas, String param) {
        Map<Object, Map<String, Object>> orderData = new TreeMap<>();
        for (int i = 0; i < totaldatas.size(); i++) {
            Map<String, Object> unit = (Map<String, Object>) totaldatas.get(i);
            Object value =unit.get(param) ;
            if(value ==null)
                continue;
            String orderParam = value.toString();
            if (orderData.containsKey(orderParam))
                orderParam = orderParam + i;
            orderData.put(orderParam, unit);
        }
        List<Map> orderList = new ArrayList<>();
        for (Map value : orderData.values()) {
            orderList.add(value);
        }
        return orderList;
    }
    public static Object[][] ListMapToArrayData(List<Map>datas) {
        Map fistData = (Map) ((List) datas).get(0);
        int totalNumber = datas.size();
        int size =fistData.size() ;
        Object[][] arrayData = new Object[totalNumber][size];
        int j=0;
        for (Map<String,Object> data: datas )
        {
            int i=0;
            for(String key : data.keySet())
            {
                arrayData[j][i]=data.get(key);
                i++;
            }
            j++ ;
        }
        return arrayData ;
    }
}
