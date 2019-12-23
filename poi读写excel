#### 1.poi读写excel，需事先引入maven依赖
```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi</artifactId>
    <version>4.1.1</version>
</dependency>
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>4.1.1</version>
</dependency>
```
实现方式：
```java
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 这个类是用来读写excel文件的，采用的是poi这个jar包实现，当前类只支持对excel2007以后的xlsx格式文件进行解析
 * xlsx格式解析需要用XSSFWorkbook类，xls格式的文件需要HSSFWorkbook类
 */
public class ExcelUtil {
    /**
     * 读取excel文件流
     * @param inputStream
     * @return
     * @throws IOException
     */
    public static Workbook getWorkbook(InputStream inputStream) throws IOException {
        return new XSSFWorkbook(inputStream);
    }

    /**
     * 每一个excel都可能有多个页面，每个页面就是一个sheet
     * @param workbook
     * @param sheetIndex index从0开始
     * @return
     */
    public static Sheet getSheet(Workbook workbook, int sheetIndex) {
        return workbook.getSheetAt(sheetIndex);
    }

    /**
     * 获取sheet的某一行
     * @param sheet
     * @param rowIndex
     * @return
     */
    public static Row getRow(Sheet sheet, int rowIndex) {
        return sheet.getRow(rowIndex);
    }

    /**
     * 每一个excel都可能有多个页面，每个页面就是一个sheet
     * @param row
     * @param cellIndex index从0开始
     * @return
     */
    public static Cell getCell(Row row, int cellIndex) {
        return row.getCell(cellIndex);
    }


    /**
     * 将表格的内容以字符串格式返回，excel默认是会把一些大一点的数字转成科学计数法，这就很可能造成精度丢失，特别是一些有小数点的情况，
     * 比如我填写一个手机号进去，鼠标失去焦点之后excel自动把手机号转成了科学计数法，所以建议所有填写数字的地方都强制转成文本格式
     * @param cell
     * @return
     */
    public static String getCellAsString(Cell cell) {
        if(cell==null){
            return null;
        }
        String returnValue = "";
        switch (cell.getCellType()) {
            case NUMERIC:   //数字
                //Double doubleValue = cell.getNumericCellValue();
                // 格式化科学计数法，取一位整数
                //DecimalFormat df = new DecimalFormat("0");
                //returnValue = df.format(doubleValue);
                returnValue = cell.getStringCellValue();
                break;
            case STRING:    //字符串
                returnValue = cell.getStringCellValue();
                break;
            case BOOLEAN:   //布尔
                Boolean booleanValue = cell.getBooleanCellValue();
                returnValue = booleanValue.toString();
                break;
            case BLANK:     // 空值
                break;
            case FORMULA:   // 公式
                returnValue = cell.getCellFormula();
                break;
            case ERROR:     // 故障
                break;
            default:
                break;
        }
        return returnValue;
    }

    /**
     * 创建一个sheet
     * @param workbook
     * @return
     */
    public static Sheet createSheet(Workbook workbook) {
        //workbook.createSheet("sheet名称");
        return workbook.createSheet();
    }

    /**
     * 将excel内容导出到outputStream
     * @param workbook
     * @param outputStream
     * @throws IOException
     */
    public static void writeTo(Workbook workbook, OutputStream outputStream) throws IOException {
        workbook.write(outputStream);
    }

    /**
     * 创建一个cell style，用来设置格子的格式
     * @param workbook
     * @return
     */
    public static CellStyle createCellStyle(Workbook workbook) {
        return workbook.createCellStyle();
    }

    /**
     * 创建一个字体配置
     * @param workbook
     * @return
     */
    public static Font createCellFont(Workbook workbook) {
        return workbook.createFont();
    }

    /**
     * 创建一行
     * @param sheet
     * @param index 指定创建第几行，index从0开始
     * @return
     */
    public static Row createRow(Sheet sheet, int index) {
        return sheet.createRow(index);
    }

    /**
     * 创建一个格子
     * @param row
     * @param index 指定创建第几个格子，index从0开始
     * @return
     */
    public static Cell createCell(Row row, int index) {
        return row.createCell(index);
    }

    /**
     * 设置格子的文本内容，建议是保存文本格式，特别是一些带小数点的数字，如果不强制设置cellType，保存带小数点的数字的时候，
     * excel会自动将数字转成科学计数法，比如1231321321321，在excel会自动显示1.23132E+12
     * @param cell
     * @param value
     * @param type
     */
    public static void setCellValue(Cell cell, Object value, CellType type) {
        cell.setCellType(type);
        cell.setCellValue(value.toString());
    }
}
```
