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
     * Description: 判断字节数组的原始编码（启发式检测）
     * </p>
     *
     * @param bytes 原始字节数组
     * @return 编码名称 ("UTF-8", "GBK", "ISO-8859-1")，无法判断返回 null
     */
    public static String getEncoding(byte[] bytes) {
        // 先尝试 UTF-8: 检查是否符合 UTF-8 编码规则
        if (isValidUtf8(bytes)) {
            return "UTF-8";
        }
        // 尝试 GBK: 检查是否能完整解码（GBK 解码不会失败，但可以检查是否包含无法映射的字符）
        try {
            String decoded = new String(bytes, "GBK");
            // 如果能完整地编码回相同��节，说明 GBK 编码是自洽的
            byte[] reEncoded = decoded.getBytes("GBK");
            if (bytesEqual(bytes, reEncoded)) {
                return "GBK";
            }
        } catch (UnsupportedEncodingException e) {
            // ignore
        }
        // 尝试 ISO-8859-1: 这种编码总是能解码任何字节
        try {
            String decoded = new String(bytes, "ISO-8859-1");
            byte[] reEncoded = decoded.getBytes("ISO-8859-1");
            if (bytesEqual(bytes, reEncoded)) {
                return "ISO-8859-1";
            }
        } catch (UnsupportedEncodingException e) {
            // ignore
        }
        return null;
    }

    /**
     * 判断是否为有效的 UTF-8 字节序列
     */
    private static boolean isValidUtf8(byte[] bytes) {
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            int following;
            if (b < 0x80) {
                following = 0;
            } else if (b >= 0xC2 && b <= 0xDF) {
                following = 1;
            } else if (b >= 0xE0 && b <= 0xEF) {
                following = 2;
            } else if (b >= 0xF0 && b <= 0xF4) {
                following = 3;
            } else {
                return false;
            }
            i++;
            for (int j = 0; j < following; j++) {
                if (i >= bytes.length) {
                    return false;
                }
                if ((bytes[i] & 0xC0) != 0x80) {
                    return false;
                }
                i++;
            }
        }
        return true;
    }

    private static boolean bytesEqual(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    /**
     * @deprecated 此方法无法正确检测 String 的原始编码。
     * Java String 始终为 UTF-16 内部编码，请使用 {@link #getEncoding(byte[])} 传入原始字节数组。
     */
    @Deprecated
    public static String getEncoding(String sb) {
        return null;
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

