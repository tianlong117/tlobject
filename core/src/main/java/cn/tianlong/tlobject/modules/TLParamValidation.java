package cn.tianlong.tlobject.modules;


import cn.tianlong.tlobject.base.*;
import cn.tianlong.tlobject.utils.TLDataUtils;
import org.xmlpull.v1.XmlPullParser;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TLParamValidation extends TLBaseModule {
    /** 整数  */
    private static final String  V_INTEGER="^-?[1-9]\\d*$";

    /**  正整数 */
    private static final String V_Z_INDEX="^[1-9]\\d*$";

    /**负整数 */
    private static final String V_NEGATIVE_INTEGER="^-[1-9]\\d*$";

    /** 数字 */
    private static final String V_NUMBER="^([+-]?)\\d*\\.?\\d+$";

    /**正数 */
    private static final String V_POSITIVE_NUMBER="^[1-9]\\d*|0$";

    /** 负数 */
    private static final String V_NEGATINE_NUMBER="^-[1-9]\\d*|0$";

    /** 浮点数 */
    private static final String V_FLOAT="^([+-]?)\\d*\\.\\d+$";

    /** 正浮点数 */
    private static final String V_POSTTIVE_FLOAT="^[1-9]\\d*.\\d*|0.\\d*[1-9]\\d*$";

    /** 负浮点数 */
    private static final String V_NEGATIVE_FLOAT="^-([1-9]\\d*.\\d*|0.\\d*[1-9]\\d*)$";

    /** 非负浮点数（正浮点数 + 0） */
    private static final String V_UNPOSITIVE_FLOAT="^[1-9]\\d*.\\d*|0.\\d*[1-9]\\d*|0?.0+|0$";

    /** 非正浮点数（负浮点数 + 0） */
    private static final String V_UN_NEGATIVE_FLOAT="^(-([1-9]\\d*.\\d*|0.\\d*[1-9]\\d*))|0?.0+|0$";

    /** 邮件 */
    private static final String V_EMAIL="^\\w+((-\\w+)|(\\.\\w+))*\\@[A-Za-z0-9]+((\\.|-)[A-Za-z0-9]+)*\\.[A-Za-z0-9]+$";

    /** 颜色 */
    private static final String V_COLOR="^[a-fA-F0-9]{6}$";

    /** url */
    private static final String V_URL="^http[s]?:\\/\\/([\\w-]+\\.)+[\\w-]+([\\w-./?%&=]*)?$";

    /** 仅中文 */
    private static final String V_CHINESE="^[\\u4E00-\\u9FA5\\uF900-\\uFA2D]+$";

    /** 仅ACSII字符 */
    private static final String V_ASCII="^[\\x00-\\xFF]+$";

    /** 邮编 */
    private static final String V_ZIPCODE="^\\d{6}$";

    /** 手机 */
    private static final String V_MOBILE="^(1)[0-9]{10}$";

    /** ip地址 */
    private static final String V_IP4="^(25[0-5]|2[0-4]\\d|[0-1]\\d{2}|[1-9]?\\d)\\.(25[0-5]|2[0-4]\\d|[0-1]\\d{2}|[1-9]?\\d)\\.(25[0-5]|2[0-4]\\d|[0-1]\\d{2}|[1-9]?\\d)\\.(25[0-5]|2[0-4]\\d|[0-1]\\d{2}|[1-9]?\\d)$";

    /** 非空 */
    private static final String V_NOTEMPTY="^\\S+$";

    /** 图片  */
    private static final String V_PICTURE="(.*)\\.(jpg|bmp|gif|ico|pcx|jpeg|tif|png|raw|tga)$";

    /**  压缩文件  */
    private static final String V_RAR="(.*)\\.(rar|zip|7zip|tgz)$";

    /** 日期 */
    private static final String V_DATE="^((((1[6-9]|[2-9]\\d)\\d{2})-(0?[13578]|1[02])-(0?[1-9]|[12]\\d|3[01]))|(((1[6-9]|[2-9]\\d)\\d{2})-(0?[13456789]|1[012])-(0?[1-9]|[12]\\d|30))|(((1[6-9]|[2-9]\\d)\\d{2})-0?2-(0?[1-9]|1\\d|2[0-8]))|(((1[6-9]|[2-9]\\d)(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00))-0?2-29-)) (20|21|22|23|[0-1]?\\d):[0-5]?\\d:[0-5]?\\d$";

    /** QQ号码  */
    private static final String V_QQ_NUMBER="^[1-9]*[1-9][0-9]*$";

    /** 电话号码的函数(包括验证国内区号,国际区号,分机号) */
    private static final String V_TEL="^(([0\\+]\\d{2,3}-)?(0\\d{2,3})-)?(\\d{7,8})(-(\\d{3,}))?$";

    /** 用来用户注册。匹配由数字、26个英文字母或者下划线组成的字符串 */
    private static final String V_USERNAME="^\\w+$";

    /** 字母 */
    private static final String V_LETTER="^[A-Za-z]+$";

    /** 大写字母  */
    private static final String V_LETTER_U="^[A-Z]+$";

    /** 小写字母 */
    private static final String V_LETTER_I="^[a-z]+$";

    /** 身份证  */
    private static final String V_IDCARD ="^(\\d{15}$|^\\d{18}$|^\\d{17}(\\d|X|x))$";

    /**验证密码(数字和英文同时存在)*/
    private static final String V_PASSWORD_REG="[A-Za-z]+[0-9]";

    /**验证密码长度(6-18位)*/
    private static final String V_PASSWORD_LENGTH="^\\d{6,18}$";

    /**验证两位数*/
    private static final String V_TWO＿POINT="^[0-9]+(.[0-9]{2})?$";

    /**验证一个月的31天*/
    private static final String V_31DAYS="^((0?[1-9])|((1|2)[0-9])|30|31)$";
    protected String failureMsgid;
    protected  LinkedHashMap<String, ArrayList<TLMsg>> paramsTable;
    protected  HashMap<String,Method> validateMethod =new HashMap<>();
    protected  HashMap<String,Method> validateMethodWithContion =new HashMap<>();
    public TLParamValidation(){
        super();
    }
    public TLParamValidation(String name ){
        super(name);
    }
    public TLParamValidation(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }


    @Override
    protected void setModuleParams() {
        if(params !=null && params.get("failureMsgid")!=null)
            failureMsgid = params.get("failureMsgid");
    }
    @Override
    protected TLBaseModule init() {
        setValidationOnMsgTable();
        getValidateMethodInfo();
        return  this ;
    }
    @Override
    protected Object setConfig(){
        myConfig config=new myConfig(configFile,moduleFactory.getConfigDir());
        mconfig=config;
        super.setConfig();
        paramsTable =config.getParamsTable();
        return config ;
    }
    protected  void getValidateMethodInfo() {
        Class clazz = this.getClass();
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            String methodName = method.getName();
            if(methodName.startsWith("validate_"))
            {
                String subName =methodName.substring(9);
                validateMethod.put(subName,method);
            }
            else if( methodName.startsWith("validate2_")) {
                String subName =methodName.substring(10);
                validateMethodWithContion.put(subName,method);
            }
       }
    }
    protected void setValidationOnMsgTable(){
        for(String key :paramsTable.keySet())
        {
            ArrayList<TLMsg> msgList =paramsTable.get(key);
            if(msgList ==null )
                continue;
            for (int i = 0; i <  msgList.size(); i++){
                TLMsg msg =msgList.get(i) ;
                if(!msg.isNull("validations") )
                {
                    ArrayList<String> validationsList =TLDataUtils.splitStrToList((String)msg.getParam("validations"),";");
                    if(validationsList !=null && validationsList.size() >0)
                        msg.setParam("validations",validationsList) ;
                }
            }
        }
    }
    @Override
    protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
        String paramValue = (String) msg.getParam("paramValue");
        Map<String,Object> conditon =msg.getArgs();
        Map<String,String> result = null;
        switch (msg.getAction()) {
            case "validate":
               return validate(fromWho,msg);
            case "length":
                result = validate2_Length(paramValue,conditon);
                break;
            default:
                 return  null;
        }
        if(result !=null)
          return msg.addMap(result) ;
        else
            return null ;
    }

    protected TLMsg defaultAction(Object fromWho, TLMsg msg) {
        return validate( fromWho,  msg);
    }
    protected TLMsg validate(Object fromWho, TLMsg msg) {
        Map<String ,Object>  msgParams =msg.getArgs();
        Set<String> paramSet = msgParams.keySet();
       for(String paramName : paramSet)
       {
           if( paramName.equals("noPassAction"))
               continue;
           String moduleParamName =((IObject)fromWho).getName()+":"+paramName;
           ArrayList<TLMsg> validattionMsgList =paramsTable.get(moduleParamName);
           if(validattionMsgList ==null || validattionMsgList.size() ==0)
               validattionMsgList =paramsTable.get(paramName);
           if(validattionMsgList ==null || validattionMsgList.size() ==0)
               continue;
           String value = (String) msgParams.get(paramName);
           HashMap<String,String> result = validateParam(paramName,value,validattionMsgList);
           if(result !=null )
           {
               String noPassAction = (String) msg.getParam("noPassAction");
               if(noPassAction !=null && !noPassAction.isEmpty())
               {
                   TLMsg emsg =createMsg().setAction(noPassAction).setArgs(result);
                   putMsg((IObject) fromWho,emsg);
                   return createMsg().setSystemParam(MODULE_DONEXTMSG,false) ;
               }
               else
                 return   noPassValidate(result);
           }
       }
       return null ;
    }

    protected TLMsg noPassValidate(HashMap<String,String> result) {
        if(failureMsgid !=null)
            return getMsg(this,createMsg().setMsgId(failureMsgid).setArgs(result));
        else
            return  createMsg().setArgs(result).setSystemParam(MODULE_DONEXTMSG,false);
    }

    protected HashMap<String,String> validateParam(String paramName, String value, ArrayList<TLMsg> validationMsgList) {

        for(int i=0; i<validationMsgList.size();i++)
        {
            TLMsg rmsg =validationMsgList.get(i);
            ArrayList<String> validations= (ArrayList<String>) rmsg.getParam("validations");
            TLMsg returnMsg ;
            if(validations !=null && validations.size()>0)
            {
                String failureMethod  =validateParamByMethods(value,validations,rmsg.getArgs());
                if(failureMethod !=null)
                {
                    String errorMessage = ( rmsg.getParam("errorMessage")!=null)?(String) rmsg.getParam("errorMessage"):null;
                    HashMap<String,String> result =new HashMap<>();
                    result.put("method",failureMethod);
                    result.put("paramName",paramName);
                    result.put("paramValue",value);
                    result.put("message",errorMessage);
                    return result ;
                }
            }
            if(rmsg.getAction() !=null || rmsg.getMsgId() !=null )
            {
                TLMsg cmsg = new TLMsg().copyFrom(rmsg).setParam(paramName,value);
                returnMsg =getMsg(this,cmsg);
                if(!ifDoNextMsg( returnMsg) )
                {
                    String failureMethod =(rmsg.getAction() !=null)?rmsg.getAction() :rmsg.getMsgId();
                    String errorMessage = ( returnMsg.getParam("errorMessage")!=null)?(String) returnMsg.getParam("errorMessage"):(String) cmsg.getParam("errorMessage");
                    HashMap<String,String> result =new HashMap<>();
                    result.put("method",failureMethod);
                    result.put("paramName",paramName);
                    result.put("paramValue",value);
                    result.put("message",errorMessage);
                    return result ;
                }
            }
        }
        return null ;
    }

    private String validateParamByMethods(String value, ArrayList<String> validations, HashMap condition) {
        Boolean result = false;
        String failureMethod = null;
        for(String methodName :validations)
        {
            result =false ;
           if(methodName.startsWith("C_"))
           {
               methodName =methodName.substring(2);
               Method method =validateMethodWithContion.get(methodName);
               if(method !=null)
               {
                   try {
                       Map<String,String> resultMap = (Map<String, String>) method.invoke(this,value,condition);
                       if(resultMap ==null)
                           result =true ;
                   } catch (IllegalAccessException e) {
                       e.printStackTrace();
                   } catch (InvocationTargetException e) {
                       e.printStackTrace();
                   }
               }
               else
                   break;
           }
            else {
               Method method =validateMethod.get(methodName);
               if(method !=null)
               {
                   try {
                       result = (Boolean) method.invoke(this,value);
                   } catch (IllegalAccessException e) {
                       e.printStackTrace();
                   } catch (InvocationTargetException e) {
                       e.printStackTrace();
                   }
               }
               else
                   break;
           }
            if(result ==false)
            {
                failureMethod =methodName ;
                break;
            }
        }
        return failureMethod;
    }

    /**
     * 验证是不是空值或输入空
     * @param value 要验证的字符串 要验证的字符串
     * @return  如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_NotNull(String value){
        if(value ==null || value.trim().isEmpty())
            return false;
        else
            return  true ;
    }

    /**
     * 验证字符串长度
     * @param value 要验证的字符串 要验证的字符串
     * @return  如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static Map<String,String> validate2_Length(String value, Map<String,Object> conditon){
        HashMap <String,String> result =new HashMap<>();
        int maxLength = (conditon.get("maxLength")!=null)?Integer.parseInt((String) conditon.get("maxLength")):0;
        int minLength =  (conditon.get("minLength")!=null)?Integer.parseInt((String) conditon.get("minLength")):0;
        int length =value.length();
        if ( maxLength >0 && length > maxLength)
        {
            result.put("maxLenght","超过最大长度:"+maxLength);
            return result ;
        }
        if ( minLength >0 && length < minLength)
        {
            result.put("minLength","小于最小长度:"+minLength);
            return result ;
        }
        return null ;
    }
    /**
     * 验证是不是整数
     * @param value 要验证的字符串 要验证的字符串
     * @return  如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Integer(String value){
        return match(V_INTEGER,value);
    }

    /**
     * 验证是不是正整数
     * @param value 要验证的字符串
     * @return  如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Z_index(String value){
        return match(V_Z_INDEX,value);
    }

    /**
     * 验证是不是负整数
     * @param value 要验证的字符串
     * @return  如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Negative_integer(String value){
        return match(V_NEGATIVE_INTEGER,value);
    }

    /**
     * 验证是不是数字
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Number(String value){
        return match(V_NUMBER,value);
    }

    /**
     * 验证是不是正数
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_PositiveNumber(String value){
        return match(V_POSITIVE_NUMBER,value);
    }

    /**
     * 验证是不是负数
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_NegativeNumber(String value){
        return match(V_NEGATINE_NUMBER,value);
    }

    /**
     * 验证一个月的31天
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Is31Days(String value){
        return match(V_31DAYS,value);
    }

    /**
     * 验证是不是ASCII
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_ASCII(String value){
        return match(V_ASCII,value);
    }


    /**
     * 验证是不是中文
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Chinese(String value){
        return match(V_CHINESE,value);
    }



    /**
     * 验证是不是颜色
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Color(String value){
        return match(V_COLOR,value);
    }



    /**
     * 验证是不是日期
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Date(String value){
        return match(V_DATE,value);
    }

    /**
     * 验证是不是邮箱地址
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Email(String value){
        return match(V_EMAIL,value);
    }

    /**
     * 验证是不是浮点数
     * @param value 要验证的字符串
     * @return  如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Float(String value){
        return match(V_FLOAT,value);
    }

    /**
     * 验证是不是正确的身份证号码
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_IDcard(String value){
        return match(V_IDCARD,value);
    }

    /**
     * 验证是不是正确的IP地址
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_IP4(String value){
        return match(V_IP4,value);
    }

    /**
     * 验证是不是字母
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Letter(String value){
        return match(V_LETTER,value);
    }

    /**
     * 验证是不是小写字母
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Letter_i(String value){
        return match(V_LETTER_I,value);
    }


    /**
     * 验证是不是大写字母
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Letter_u(String value){
        return match(V_LETTER_U,value);
    }


    /**
     * 验证是不是手机号码
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Mobile(String value){
        return match(V_MOBILE,value);
    }

    /**
     * 验证是不是负浮点数
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Negative_float(String value){
        return match(V_NEGATIVE_FLOAT,value);
    }

    /**
     * 验证非空
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Notempty(String value){
        return match(V_NOTEMPTY,value);
    }

    /**
     * 验证密码的长度(6~18位)
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Number_length(String value){
        return match(V_PASSWORD_LENGTH,value);
    }

    /**
     * 验证密码(数字和英文同时存在)
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Password_reg(String value){
        return match(V_PASSWORD_REG,value);
    }

    /**
     * 验证图片
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Picture(String value){
        return match(V_PICTURE,value);
    }

    /**
     * 验证正浮点数
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Posttive_float(String value){
        return match(V_POSTTIVE_FLOAT,value);
    }

    /**
     * 验证QQ号码
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_QQnumber(String value){
        return match(V_QQ_NUMBER,value);
    }

    /**
     * 验证压缩文件
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Rar(String value){
        return match(V_RAR,value);
    }

    /**
     * 验证电话
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Tel(String value){
        return match(V_TEL,value);
    }

    /**
     * 验证两位小数
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Two_point(String value){
        return match(V_TWO＿POINT,value);
    }

    /**
     * 验证非正浮点数
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Un_negative_float(String value){
        return match(V_UN_NEGATIVE_FLOAT,value);
    }

    /**
     * 验证非负浮点数
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Unpositive_float(String value){
        return match(V_UNPOSITIVE_FLOAT,value);
    }

    /**
     * 验证URL
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Url(String value){
        return match(V_URL,value);
    }

    /**
     * 验证用户注册。匹配由数字、26个英文字母或者下划线组成的字符串
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_UserName(String value){
        return match(V_USERNAME,value);
    }

    /**
     * 验证邮编
     * @param value 要验证的字符串
     * @return 如果是符合格式的字符串,返回 <b>true </b>,否则为 <b>false </b>
     */
    public static boolean validate_Zipcode(String value){
        return match(V_ZIPCODE,value);
    }

    /**
     * @param regex 正则表达式字符串
     * @param str 要匹配的字符串
     * @return 如果str 符合 regex的正则表达式格式,返回true, 否则返回 false;
     */
    private static boolean match(String regex, String str)
    {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(str);
        boolean result = matcher.matches();
        return result ;
    }

    protected class myConfig extends TLModuleConfig {
        protected  LinkedHashMap<String, ArrayList<TLMsg>> paramsTable ;
        public myConfig(String configFile ,String configDir) {
            super(configFile,configDir);
        }
        public myConfig() {

        }

        public  LinkedHashMap<String, ArrayList<TLMsg>>  getParamsTable() {
            return paramsTable;
        }

        protected void myConfig(XmlPullParser xpp) {
            super.myConfig(xpp);
            try {
                if (xpp.getName().equals("paramsTable")) {
                    paramsTable = getLinkHashMsgList(xpp, "paramsTable","param");
                }

            } catch (Throwable t) {

            }
        }

    }
}
