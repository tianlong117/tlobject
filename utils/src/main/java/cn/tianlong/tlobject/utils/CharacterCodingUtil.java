package cn.tianlong.tlobject.utils;

import java.io.UnsupportedEncodingException;

/**
 * 判断字符编码
 *
 * @author guyinyihun
 */
public class CharacterCodingUtil {


    private final static String ENCODE = "GBK";

    /**
     * 判断是否为ISO-8859-1
     *
     * @return
     */
    public static boolean checkISO(String str) {
        boolean flag = java.nio.charset.Charset.forName("ISO-8859-1").newEncoder().canEncode(str);
        return flag;
    }

    /**
     * 判断是否为UTF-8
     *
     * @return
     */
    public static boolean checkUTF(String str) {

        boolean flag = java.nio.charset.Charset.forName("UTF-8").newEncoder().canEncode(str);
        return flag;
    }

    public static boolean checkUnicode(String str) {

        boolean flag = java.nio.charset.Charset.forName("unicode").newEncoder().canEncode(str);
        return flag;
    }

    /**
     * <p>
     * Title: getEncoding
     * </p>
     * <p>
     * Description: 判断字符编码
     * </p>
     *
     * @param sb
     * @return
     */
    public static String getEncoding(String sb) {
        String utf8 = null ;
        try {
            utf8 = new String(sb.getBytes("utf-8"));
        } catch (UnsupportedEncodingException e) {

        }
        if (utf8.equals(sb))
            return "utf8";
        String iso8859 = null;
        try {
            iso8859 = new String(sb.getBytes("iso8859-1"));
        } catch (UnsupportedEncodingException e) {
        }
        if (iso8859.equals(sb))
            return "iso8859";
        String gbk = null;
        try {
            gbk = new String(sb.getBytes("gbk"));
        } catch (UnsupportedEncodingException e) {
        }
        if (gbk.equals(sb))
            return "gbk";
        return null ;
    }
    /**
     * <p>
     * Title: isoToutf8
     * </p>
     * <p>
     * Description: ISO-8859-1 编码 转 UTF-8
     * </p>
     *
     * @param str
     * @return
     */
    public static String isoToutf8(String str) {
        try {
            return new String(str.getBytes("ISO-8859-1"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return str;
        }
    }

    /**
     * <p>
     * Title: utf8Toiso
     * </p>
     * <p>
     * Description: UTF-8 编码 转 ISO-8859-1
     * </p>
     *
     * @param str
     * @return
     */
    public static String utf8Toiso(String str) {
        try {
            return new String(str.getBytes("utf-8"), "iso-8859-1");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return str;
        }
    }

    /**
     * <p>Title: unicodeToCn</p>
     * <p>Description: unicode 转 中文</p>
     *
     * @param unicode
     * @return
     */
    public static String unicodeToCn(String unicode) {
        /** 以 \ u 分割，因为java注释也能识别unicode，因此中间加了一个空格 */
        String[] strs = unicode.split("\\\\u");
        String returnStr = "";
        // 由于unicode字符串以 \ u 开头，因此分割出的第一个字符是""。
        for (int i = 1; i < strs.length; i++) {
            returnStr += (char) Integer.valueOf(strs[i], 16).intValue();
        }
        return returnStr;
    }


    /**
     * <p>Title: cnToUnicode</p>
     * <p>Description: 中文转 unicode</p>
     *
     * @param cn
     * @return
     */
    public static String cnToUnicode(String cn) {
        char[] chars = cn.toCharArray();
        String returnStr = "";
        for (int i = 0; i < chars.length; i++) {
            returnStr += "\\u" + Integer.toString(chars[i], 16);
        }
        return returnStr;
    }

    /**
     * URL 解码
     *
     * @return String
     * @author lifq
     * @date 2015-3-17 下午04:09:51
     */
    public static String getURLDecoderString(String str) {
        String result = "";
        if (null == str) {
            return "";
        }
        try {
            result = java.net.URLDecoder.decode(str, ENCODE);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * URL 转码
     *
     * @return String
     * @author lifq
     * @date 2015-3-17 下午04:10:28
     */
    public static String getURLEncoderString(String str) {
        String result = "";
        if (null == str) {
            return "";
        }
        try {
            result = java.net.URLEncoder.encode(str, ENCODE);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return result;
    }

}

