package com.mediassistrover.backend.knowledge;

import com.mediassistrover.backend.assistant.OpenAiGateway;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DocumentExtractorTest {
    @TempDir Path directory;
    private final DocumentExtractor extractor = new DocumentExtractor(mock(OpenAiGateway.class));

    @Test void extractsDocxHeadingsAndBodyWithoutCallingOcr() throws Exception {
        Path path=directory.resolve("hospital.docx");
        try(XWPFDocument document=new XWPFDocument()){
            var heading=document.createParagraph();heading.setStyle("Heading1");heading.createRun().setText("Khoa khám bệnh");
            document.createParagraph().createRun().setText("Quầy tiếp nhận mở cửa lúc 07:00.");
            try(var output=Files.newOutputStream(path)){document.write(output);}
        }
        var result=extractor.extract(path,"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(result.method()).isEqualTo("DOCX_TEXT");
        assertThat(result.text()).contains("[HEADING] Khoa khám bệnh","07:00");
    }

    @Test void extractsTextPdfAndKeepsPageLocator() throws Exception {
        Path path=directory.resolve("hospital.pdf");
        try(PDDocument document=new PDDocument()){
            PDPage page=new PDPage();document.addPage(page);
            try(PDPageContentStream out=new PDPageContentStream(document,page)){
                out.beginText();out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);out.newLineAtOffset(72,700);
                out.showText("Outpatient reception is available from Monday to Friday at the main lobby desk.");out.endText();
            }
            document.save(path.toFile());
        }
        var result=extractor.extract(path,"application/pdf");
        assertThat(result.method()).isEqualTo("PDF_TEXT");
        assertThat(result.pages()).isEqualTo(1);
        assertThat(result.text()).contains("[PAGE 1]","Outpatient reception");
    }

    @Test void keepsDocxTableBetweenSurroundingParagraphsAndMarksCompleteRows() throws Exception {
        Path path=directory.resolve("hours.docx");
        try(XWPFDocument document=new XWPFDocument()){
            var heading=document.createParagraph();heading.setStyle("Heading1");heading.createRun().setText("Nhà thuốc ngoại trú");
            document.createParagraph().createRun().setText("Giờ phục vụ như sau:");
            var table=document.createTable(2,3);table.getRow(0).getCell(0).setText("Ngày");table.getRow(0).getCell(1).setText("Mở cửa");table.getRow(0).getCell(2).setText("Đóng cửa");
            table.getRow(1).getCell(0).setText("Thứ Bảy");table.getRow(1).getCell(1).setText("07:00");table.getRow(1).getCell(2).setText("13:00");
            document.createParagraph().createRun().setText("Vui lòng mang theo đơn thuốc.");
            try(var output=Files.newOutputStream(path)){document.write(output);}
        }
        String text=extractor.extract(path,"application/vnd.openxmlformats-officedocument.wordprocessingml.document").text();
        assertThat(text).contains("[TABLE_HEADER] Ngày | Mở cửa | Đóng cửa","[TABLE_ROW] Thứ Bảy | 07:00 | 13:00");
        assertThat(text.indexOf("Giờ phục vụ")).isLessThan(text.indexOf("[TABLE]"));
        assertThat(text.indexOf("[/TABLE]")).isLessThan(text.indexOf("Vui lòng mang"));
    }
}
