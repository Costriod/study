#### 1.目前有很多生成pdf文件的工具包，常用的有jasper report、itext，不过jasper report需要手工配置模板文件，稍微比较繁琐
#### 2.下面是采用itext实现一个简单的功能生成pdf，事先引入maven依赖
```xml
<dependency>
    <groupId>com.itextpdf</groupId>
    <artifactId>itextpdf</artifactId>
    <version>5.5.13</version>
</dependency>
<dependency>
    <groupId>com.itextpdf</groupId>
    <artifactId>itext-asian</artifactId>
    <version>5.2.0</version>
</dependency>
```
实现方式：
```java
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import com.itextpdf.text.pdf.draw.LineSeparator;
import com.itextpdf.text.pdf.draw.VerticalPositionMark;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 有许多开源的PDF文件生成工具，这里使用的是itextpdf工具包生成pdf，样例如下：
 * 中文字体要选UniGB-UCS2-H这个编码，否则无法显示中文
 * BaseFont bfChinese = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
 * Font font = new Font(bfChinese);//正常字体
 * Font fontBold = new Font(bfChinese, 12, Font.BOLD);//正常加粗字体
 * Font fontBig = new Font(bfChinese, 20);//大字体
 * Font fontBigBold = new Font(bfChinese, 20, Font.BOLD);//加粗大字体
 * ByteArrayOutputStream outputStream = new ByteArrayOutputStream();//先创建outputStream用来存储pdf二进制流
 * Document document = new Document();//创建pdf文件的document对象
 * document.setPageSize(PageSize.A4);//设置文件宽度
 * PdfWriter writer = PdfWriter.getInstance(document, outputStream);//创建一个writer负责往outputStream写入数据
 *
 * Paragraph para = new Paragraph("文本内容", font);//创建一个段落
 * para.setAlignment(Element.ALIGN_CENTER);//设置居中对齐
 *
 * LineSeparator line = new LineSeparator(2f, 90, BaseColor.BLACK, Element.ALIGN_BOTTOM, -10);//创建一条线段
 *
 * PdfPTable table = new PdfPTable(3);//创建一个表格，3个属性列
 * table.setWidthPercentage(90); // 宽度90%填充
 * table.setSpacingBefore(20f); // 前间距
 * table.setSpacingAfter(20f); // 后间距
 *
 * List<PdfPRow> listRow = table.getRows();//获取table的所有row，一开始是里面什么都没有
 * //设置列宽相对占比，下面是1:2:3的宽度占比
 * float[] columnRelativeWidths = {1f, 2f, 3f};
 * table.setWidths(columnRelativeWidths);
 *
 * PdfPCell cells[] = new PdfPCell[3];//创建包含三个格子的数组
 * PdfPRow row = new PdfPRow(cells);//创建一个row
 *
 * cells[0] = new PdfPCell(new Paragraph("文本内容", fontBold));//单元格内容
 * cells[0].setHorizontalAlignment(Element.ALIGN_CENTER);//水平居中
 * cells[0].setVerticalAlignment(Element.ALIGN_MIDDLE);//垂直居中
 * cells[0].setFixedHeight(25f);
 * cells[0].setBackgroundColor(BaseColor.LIGHT_GRAY);
 * //cells[0].setColspan(2);//合并单元格
 *
 * //cells[1]、cells[2]直到cells[n]都是按照上面的写法
 * listRow.add(row);
 *
 * document.add(para);//添加段落到document
 * document.add(line);//添加线段
 * document.add(table);//添加表格
 * document.close();
 * writer.close();
 *
 * 到这里就已经生成了pdf文件，只不过pdf文件的内容保存在了outputStream里面，
 * 而outputStream你可以保存到文件流，或者保存到其他输出流里面
 */
public class PdfUtil {
    /**
     * make it inaccessible
     */
    private PdfUtil() {};

    /**
     * 中文字体格式，最重要一点就是UniGB-UCS2-H编码，没有这个编码会导致中文无法显示
     */
    private static BaseFont CHINESE_BASE_FONT;
    private static Font NORMAL_FONT = new Font(CHINESE_BASE_FONT);//正常字体
    private static Font BOLD_FONT = new Font(CHINESE_BASE_FONT, 12, Font.BOLD);//正常加粗字体
    private static Font BIG_BOLD_FONT = new Font(CHINESE_BASE_FONT, 20);//大字体
    static {
        try {
            CHINESE_BASE_FONT = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            NORMAL_FONT = new Font(CHINESE_BASE_FONT);//正常字体
            BOLD_FONT = new Font(CHINESE_BASE_FONT, 12, Font.BOLD);//正常加粗字体
            BIG_BOLD_FONT = new Font(CHINESE_BASE_FONT, 20);//大字体
        } catch (DocumentException e) {
            
        } catch (IOException e) {
            
        }
    }

    /**
     * 创建document对象，默认A4纸大小
     * @return
     */
    private static Document createDocument() {
        return new Document();//默认就是A4
    }

    /**
     * 创建writer对象
     * @param document
     * @param outputStream
     * @return
     * @throws DocumentException
     */
    private static PdfWriter createWriter(Document document, OutputStream outputStream) throws DocumentException {
        return PdfWriter.getInstance(document, outputStream);
    }

    /**
     * 创建一个文本段落
     * @param content
     * @param font
     * @param align
     * @return
     */
    private static Paragraph createParagraph(String content, Font font, int align) {
        Paragraph paragraph = new Paragraph(content, font);
        paragraph.setAlignment(align);
        return paragraph;
    }

    /**
     * 创建一个表格，设置初始的表格列数
     * @param columns
     * @return
     */
    private static PdfPTable createTable(int columns) {
        return createTable(columns, 90, 20f, 20f);
    }

    /**
     * 创建一个表格
     * @param columns 表格有多少列
     * @param widthPercentage 宽度占用百分比
     * @param spaceBefore 和前一行的间隔
     * @param spaceAfter 和后一行的间隔
     * @return
     */
    private static PdfPTable createTable(int columns, int widthPercentage, float spaceBefore, float spaceAfter) {
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(widthPercentage); // 宽度填充百分比
        table.setSpacingBefore(spaceBefore); // 和前一行的间隔
        table.setSpacingAfter(spaceAfter); // 和后一行的间隔
        return table;
    }

    /**
     * 创建一条横线（实线）
     * @return
     */
    private static LineSeparator createLine() {
        return createLine(2f, 90, BaseColor.BLACK, Element.ALIGN_BOTTOM, -10f);
    }

    /**
     * 创建一条横线（实线）
     * @param lineWidth 横线的线宽（粗线与细线控制）
     * @param widthPercentage 页面宽度占比
     * @param color 颜色
     * @param align 对齐
     * @param offset 当前位置上下偏移量（默认横线就是在当前文本的底部，和文字的最下方像素点在同一个位置）
     * @return
     */
    private static LineSeparator createLine(float lineWidth, int widthPercentage, BaseColor color, int align, float offset) {
        return new LineSeparator(lineWidth, widthPercentage, color, align, offset);//创建一条线段
    }


    /**
     * 创建一条横线（虚线）
     * @return
     */
    private static DashLineSeparator createDashLine() {
        return createDashLine(2f, 90, BaseColor.BLACK, Element.ALIGN_BOTTOM, -10f);
    }

    /**
     * 创建一条横线（虚线）
     * @param lineWidth 横线的线宽（粗线与细线控制）
     * @param widthPercentage 页面宽度占比
     * @param color 颜色
     * @param align 对齐
     * @param offset 当前位置上下偏移量（默认横线就是在当前文本的底部，和文字的最下方像素点在同一个位置）
     * @return
     */
    private static DashLineSeparator createDashLine(float lineWidth, int widthPercentage, BaseColor color, int align, float offset) {
        return new DashLineSeparator(lineWidth, widthPercentage, color, align, offset);//创建一条线段
    }

    /**
     * 创建一个格子
     * @param paragraph
     * @return
     */
    private static PdfPCell createCell(Paragraph paragraph) {
        return new PdfPCell(paragraph);
    }

    /**
     * 创建一行
     * @param cells
     * @return
     */
    private static PdfPRow createRow(PdfPCell[] cells) {
        return new PdfPRow(cells);
    }

    /**
     * itext是没有虚线这个概念的，下面这个是虚线的实现，不要问我啥意思，我也看不懂，我也是抄过来的https://blog.csdn.net/qq_37255225/article/details/80089835
     */
    public static class DashLineSeparator extends VerticalPositionMark {

        /**
         * 线的粗细
         */
        protected float lineWidth = 1;

        /**
         * 线占页面宽度的百分比
         */
        protected float percentage = 90;

        /**
         * 线色
         */
        protected BaseColor lineColor;

        /**
         * 线的对齐方式
         */
        protected int alignment = Element.ALIGN_BOTTOM;

        public DashLineSeparator(float lineWidth, float percentage, BaseColor lineColor, int align, float offset) {
            this.lineWidth = lineWidth;
            this.percentage = percentage;
            this.lineColor = lineColor;
            this.alignment = align;
            this.offset = offset;
        }

        public DashLineSeparator(Font font) {
            this.lineWidth = PdfChunk.UNDERLINE_THICKNESS * font.getSize();
            this.offset = PdfChunk.UNDERLINE_OFFSET * font.getSize();
            this.percentage = 90;
            this.lineColor = font.getColor();
        }
        public DashLineSeparator() {}

        public void draw(PdfContentByte canvas, float llx, float lly, float urx, float ury, float y) {
            canvas.saveState();
            drawLine(canvas, llx, urx, y);
            canvas.restoreState();
        }

        public void drawLine(PdfContentByte canvas, float leftX, float rightX, float y) {
            float w;
            if (getPercentage() < 0)
                w = -getPercentage();
            else
                w = (rightX - leftX) * getPercentage() / 100.0f;
            float s;
            switch (getAlignment()) {
                case Element.ALIGN_LEFT:
                    s = 0;
                    break;
                case Element.ALIGN_RIGHT:
                    s = rightX - leftX - w;
                    break;
                default:
                    s = (rightX - leftX - w) / 2;
                    break;
            }
            canvas.setLineWidth(getLineWidth());
            if (getLineColor() != null) {
                canvas.setColorStroke(getLineColor());
            }
            canvas.setLineDash(6, 6, 0);//设置虚线长度
            canvas.moveTo(s + leftX, y + offset);
            canvas.lineTo(s + w + leftX, y + offset);
            canvas.stroke();
        }

        public float getLineWidth() {
            return lineWidth;
        }

        public void setLineWidth(float lineWidth) {
            this.lineWidth = lineWidth;
        }

        public float getPercentage() {
            return percentage;
        }

        public void setPercentage(float percentage) {
            this.percentage = percentage;
        }

        public BaseColor getLineColor() {
            return lineColor;
        }

        public void setLineColor(BaseColor color) {
            this.lineColor = color;
        }

        public int getAlignment() {
            return alignment;
        }

        public void setAlignment(int align) {
            this.alignment = align;
        }
    }
}
```
