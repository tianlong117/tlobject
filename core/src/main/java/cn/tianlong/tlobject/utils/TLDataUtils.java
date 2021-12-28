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
                    String result = str.substring(str.indexOf(".") + 1);
                    if(Integer.parseInt(result) ==0)
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
    public static List<Map> toTree_old(List<Map> treeList, Long pid) {
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
}
