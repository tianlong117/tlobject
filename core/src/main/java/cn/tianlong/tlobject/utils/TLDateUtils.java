package cn.tianlong.tlobject.utils;


import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class TLDateUtils {


    public static String dateToStr(Date date,String formatStr){
        if(formatStr ==null)
            formatStr ="yyyy-MM-dd HH:mm:ss" ;
        SimpleDateFormat format = new SimpleDateFormat(formatStr);
        return format.format(date);
    }
    public static Date strToDay(String dateStr,String formatStr){
        if(formatStr ==null)
            formatStr ="yyyy-MM-dd HH:mm:ss" ;
        SimpleDateFormat format = new SimpleDateFormat(formatStr);
        try {
            return format.parse(dateStr);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return null ;
    }
    public static String getNowDateStr(String formatStr){
        if(formatStr ==null)
            formatStr ="yyyy-MM-dd HH:mm:ss" ;
        Date date = new Date();
        SimpleDateFormat format = new SimpleDateFormat(formatStr);
        return format.format(date);
    }
    public static String getIntervalDayStr(String day,int interval,String formatStr){
        if(formatStr ==null)
            formatStr ="yyyy-MM-dd HH:mm:ss" ;
        Date date = null;
        try {
            date = new SimpleDateFormat(formatStr).parse(day);
        } catch (ParseException e) {
            e.printStackTrace();
        }
       return getIntervalDayStr(date,interval,formatStr);
     }
    public static String getIntervalDayStr(Date date,int interval,String formatStr){
        if(formatStr ==null)
            formatStr ="yyyy-MM-dd HH:mm:ss" ;
        SimpleDateFormat sf = new SimpleDateFormat(formatStr);
        Date day =getIntervalDay( date,interval);
        return sf.format(day);
    }
    public static Date getIntervalDay(Date date,int interval){
        if(date ==null)
            date = new Date();
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        int day1 = c.get(Calendar.DATE);
        c.set(Calendar.DATE, day1 + interval);
        return c.getTime();
    }
    public static  Boolean compareDate(Date date1 ,Date date2 ,int second){
        long beginMillisecond = date1.getTime();
        long endMillisecond = date2.getTime();
        long intval =beginMillisecond -endMillisecond;
        if(intval <= second*1000)
            return false ;
        else
            return true;
    }

      /**
     * 根据日期获取 星期 （2019-05-06 ——> 星期一） 0:星期天 6 星期六
     * @param datetime
     * @return
     */
    public static int dateToWeek(String datetime ,String dateFormat) {
        if(dateFormat ==null)
            dateFormat="yyyy-MM-dd";
        SimpleDateFormat f = new SimpleDateFormat(dateFormat);
        Calendar cal = Calendar.getInstance();
        Date date;
        try {
            date = f.parse(datetime);
            cal.setTime(date);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        //一周的第几天
        int w = cal.get(Calendar.DAY_OF_WEEK) - 1;
        if (w < 0)
            w = 0;
       return w ;
    }
}
