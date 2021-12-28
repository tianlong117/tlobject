package cn.tianlong.tlobject.execl;

import cn.tianlong.tlobject.utils.TLDateUtils;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * 创建日期：2020/9/59:39
 * 描述:
 * 作者:tianlong
 */
public class TLExeclFileUtils {

    public static List<HashMap<String,Object>> parseExeclFileToList(String path,Boolean hasTitle,List<String> importTitles){
        Sheet sheet =parseSheet(path);
        if(sheet ==null)
            return null ;
        List<HashMap<String,Object>> listMap =parseCell(sheet,hasTitle,importTitles);
        return listMap ;
    }
    // 解析Excel,读取内容,path Excel路径
    public static Sheet parseSheet(String path) {
        File file = null;
        InputStream input = null;
        if (path != null && path.length() > 3) {
            // 判断文件是否是Excel(2003、2007)
            String suffix = path.substring(path.lastIndexOf("."), path.length());
            file = new File(path);
            try {
                input = new FileInputStream(file);
            } catch (FileNotFoundException e) {
                System.out.println("未找到指定的文件！");
                return null ;
            }
            // Excel2003
            if (".xls".equals(suffix)) {
                POIFSFileSystem fileSystem = null;
                // 工作簿
                HSSFWorkbook workBook = null;
                try {
                    fileSystem = new POIFSFileSystem(input);
                    workBook = new HSSFWorkbook(fileSystem);
                } catch (IOException e) {
                    e.printStackTrace();
                    return null ;
                }
                // 获取第一个工作簿
               return  workBook.getSheetAt(0);
                // Excel2007
            } else if (".xlsx".equals(suffix)) {
                XSSFWorkbook workBook = null;
                try {
                    workBook = new XSSFWorkbook(input);
                } catch (IOException e) {
                    e.printStackTrace();
                    return null ;
                }
                // 获取第一个工作簿
                return workBook.getSheetAt(0);
            }
        } else {
            System.out.println("非法的文件路径!");
            return null ;
        }
        return null;
    }
    // 获取Excel内容
    public static List<HashMap<String,Object>> parseCell(Sheet sheet ,boolean hasTitle,List<String> importTitles) {
        List<HashMap<String,Object>> list = new ArrayList<>();
        // Excel数据总行数
        int rowCount = sheet.getPhysicalNumberOfRows();
        if(rowCount ==0)
            return null ;
        //获取标题
        int firstRowNum =sheet.getFirstRowNum() ;
        int dataRow  ;
        List<String> titles = null;
        if( hasTitle ==true)
        {
            Row titleRow = sheet.getRow(firstRowNum);
            titles = parseTitle(titleRow);
            dataRow =firstRowNum +1 ;
        }
        else
            dataRow =firstRowNum ;
        int lastRow =sheet.getLastRowNum() ;
        // 遍历数据行，略过标题行，从第二行开始
        for (int i = dataRow; i <= lastRow; i++) {
            HashMap<String,Object> rowMap = new HashMap<>();
            Row row = sheet.getRow(i);
            if(row ==null)
                continue;
            int cellCount = row.getPhysicalNumberOfCells();
            // 遍历行单元格
            for (int j = 0; j < cellCount; j++) {
                Cell cell = row.getCell(j);
                String value =getCellValue(cell) ;
                if(titles !=null )
                {
                   String title =titles.get(j) ;
                   if(title !=null && !title.isEmpty())
                   {
                       if(importTitles ==null || importTitles.isEmpty())
                          rowMap.put(title,value) ;
                       else {
                           if(importTitles.contains(title))
                               rowMap.put(title,value) ;
                       }
                   }
                }
                else
                    rowMap.put(String.valueOf(j),value) ;
            }
            list.add(rowMap);
        }
        return list;
    }
    public static List<String> parseTitle(Sheet sheet) {
        int firstRowNum =sheet.getFirstRowNum() ;
        Row titleRow = sheet.getRow(firstRowNum);
        return parseTitle(titleRow) ;
    }
    public static List<String> parseTitle(Row row) {
            List<String> list =new ArrayList<>() ;
            int cellCount = row.getPhysicalNumberOfCells();
            // 遍历行单元格
            for (int j = 0; j < cellCount; j++) {
                Cell cell = row.getCell(j);
                String value =getCellValue(cell) ;
                list.add(value) ;
            }
        return list;
    }
    //获取Cell内容
    public static String getCellValue(Cell cell) {
        String value = "";
        if(cell != null) {
            //以下是判断数据的类型
            switch (cell.getCellType()) {
                case NUMERIC://数字
                    if(HSSFDateUtil.isCellDateFormatted(cell)) {
                        Date date = cell.getDateCellValue();
                        if(date != null) {
                            value = TLDateUtils.dateToStr(date,null);
                        }else {
                            value = "";
                        }
                    }else {
                    //    value = new DecimalFormat("0").format(cell.getNumericCellValue());
                        value = String.valueOf(cell.getNumericCellValue()) ;
                    }
                    break;
                case STRING: //字符串
                    value = cell.getStringCellValue();
                    break;
                case BOOLEAN: //boolean
                    value = String.valueOf(cell.getBooleanCellValue()) ;
                    break;
                case FORMULA: //公式
                    value = cell.getCellFormula();
                    break;
                case BLANK: //空值
                    value = "";
                    break;
                case ERROR: //故障
                    value = "非法字符";
                    break;
                default:
                    value = "未知类型";
                    break;
            }
        }
        return value.trim();
    }

    /**
     * @功能：手工构建一个简单格式的Excel
     */

    public static  String listToExeclFile(List<Map<String,Object>> list, String saveFile,LinkedHashMap<String,String> execlTitles) {
        // 第一步，创建一个webbook，对应一个Excel文件
        HSSFWorkbook wb = new HSSFWorkbook();
        // 第二步，在webbook中添加一个sheet,对应Excel文件中的sheet
        HSSFSheet sheet = wb.createSheet("sheet1");
        // 第三步，在sheet中添加表头第0行,注意老版本poi对Excel的行数列数有限制short
        HSSFRow row = sheet.createRow((int) 0);
        // 第四步，创建单元格，并设置值表头 设置表头居中
        HSSFCellStyle style = wb.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER); // 创建一个居中格式

        if(execlTitles ==null)
        {
            execlTitles =new LinkedHashMap<>() ;
            Map<String, Object> dataMap0= list.get(0);
            Set keySets =dataMap0.keySet() ;
            String[] keys = (String[]) keySets.toArray(new String[0]);
            for(int i=0 ; i < keys.length ; i++){
                execlTitles.put(keys[i],keys[i]);
            }
        }
        int i=0 ;
        for(String key : execlTitles.keySet()){
            String value = execlTitles.get(key);
            HSSFCell cell = row.createCell((short) i);
            cell.setCellValue(value);
            cell.setCellStyle(style);
            i++;
        }
        // 第五步，写入实体数据 实际应用中这些数据从数据库得到，
        for (int j = 0; j < list.size();j++)
        {
            row = sheet.createRow((int) j + 1);
            Map<String,Object> execlData = list.get(j);
            // 第四步，创建单元格，并设置值
            i=0 ;
            for(String key : execlTitles.keySet())
            {
                Object value = execlData.get(key);
                if(value ==null)
                {
                    row.createCell((short) i);
                    i++;
                    continue;
                }
                if( value instanceof  Boolean)
                   row.createCell((short) i).setCellValue((Boolean) value);
                else if( value instanceof  String)
                    row.createCell((short) i).setCellValue((String) value);
                else if( value instanceof  Integer)
                    row.createCell((short) i).setCellValue((int) value);
                else if( value instanceof  Long)
                    row.createCell((short) i).setCellValue((Long) value);
                else if( value instanceof  Double)
                    row.createCell((short) i).setCellValue((Double) value);
                else if( value instanceof  BigDecimal)
                    row.createCell((short) i).setCellValue(((BigDecimal) value).doubleValue());
                else if( value instanceof BigInteger)
                    row.createCell((short) i).setCellValue(((BigInteger) value).longValue());
                else
                    row.createCell((short) i).setCellValue(value.toString());
                i++;
            }
       }
        // 第六步，将文件存到指定位置
        try {
            FileOutputStream fout = new FileOutputStream(saveFile);
            wb.write(fout);
            fout.close();
        } catch (Exception e) {
            e.printStackTrace();
            return null ;
        }
        return  saveFile ;
    }
}
