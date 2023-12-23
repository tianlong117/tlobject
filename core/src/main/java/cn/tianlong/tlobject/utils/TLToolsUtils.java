package cn.tianlong.tlobject.utils;

public class TLToolsUtils {

    public static  String  exceptionToString (Exception exception){
        StringBuilder sb = new StringBuilder();
        sb.append("exception:\n");
        sb.append(exception.toString());
        sb.append("\n");
        StackTraceElement[] stackArray = exception.getStackTrace();
        for (int i = 0; i < stackArray.length; i++) {
            StackTraceElement element = stackArray[i];
            sb.append("  ");
            sb.append(i);
            sb.append(". ");
            sb.append(element.toString());
            sb.append("\n");
        }
        return  sb.toString();
    }
}
