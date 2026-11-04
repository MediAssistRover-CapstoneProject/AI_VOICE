package com.mediassistrover.backend.knowledge;

import com.mediassistrover.backend.assistant.OpenAiGateway;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component @RequiredArgsConstructor
public class DocumentExtractor {
    public record Extracted(String text,int pages,String method) { }
    private final OpenAiGateway openAi;
    public Extracted extract(Path file,String mediaType)throws IOException{
        String type=mediaType==null?"":mediaType.toLowerCase(Locale.ROOT);
        if(type.equals("text/plain"))return checked(Files.readString(file,java.nio.charset.StandardCharsets.UTF_8),1,"TEXT");
        if(type.equals("application/pdf"))return pdf(file);
        if(type.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))return docx(file);
        if(type.equals("image/jpeg")||type.equals("image/png")){String text=openAi.ocr(Files.readAllBytes(file),type);return checked(text,1,"OPENAI_VISION");}
        throw new BusinessRuleException("Only PDF, DOCX, JPG and PNG are supported");
    }
    private Extracted pdf(Path file)throws IOException{
        try(PDDocument doc=Loader.loadPDF(file.toFile())){
            if(doc.getNumberOfPages()>200)throw new BusinessRuleException("PDF exceeds the 200 page limit");
            PDFTextStripper stripper=new PDFTextStripper();PDFRenderer renderer=new PDFRenderer(doc);StringBuilder all=new StringBuilder();boolean usedOcr=false;
            for(int page=1;page<=doc.getNumberOfPages();page++){
                stripper.setStartPage(page);stripper.setEndPage(page);String text=stripper.getText(doc).trim();
                if(text.length()<40){ByteArrayOutputStream image=new ByteArrayOutputStream();ImageIO.write(renderer.renderImageWithDPI(page-1,144),"png",image);text=openAi.ocr(image.toByteArray(),"image/png").trim();usedOcr=true;}
                all.append("\n\n[PAGE ").append(page).append("]\n").append(text);
                if(all.length()>2_000_000)throw new BusinessRuleException("Document exceeds the 2 million character limit");
            }
            return checked(all.toString(),doc.getNumberOfPages(),usedOcr?"PDF_TEXT_AND_OCR":"PDF_TEXT");
        }
    }
    private Extracted docx(Path file)throws IOException{
        try(InputStream in=Files.newInputStream(file);XWPFDocument doc=new XWPFDocument(in)){
            StringBuilder text=new StringBuilder();String currentHeading=null;
            for(IBodyElement element:doc.getBodyElements()){
                if(element instanceof XWPFParagraph p){String value=clean(p.getText());if(value.isEmpty())continue;String style=p.getStyle();boolean heading=style!=null&&style.toLowerCase(Locale.ROOT).startsWith("heading");if(heading){currentHeading=value;text.append("\n\n[HEADING] ").append(value);}else text.append('\n').append(value);}
                else if(element instanceof XWPFTable table){
                    List<XWPFTableRow> rows=table.getRows();if(rows.isEmpty())continue;
                    text.append("\n[TABLE]\n[TABLE_HEADING] ").append(currentHeading==null?"Bảng thông tin":currentHeading);
                    for(int rowIndex=0;rowIndex<rows.size();rowIndex++){List<String> cells=rows.get(rowIndex).getTableCells().stream().map(c->clean(c.getText())).toList();text.append(rowIndex==0?"\n[TABLE_HEADER] ":"\n[TABLE_ROW] ").append(String.join(" | ",cells));}
                    text.append("\n[/TABLE]");
                }
                if(text.length()>2_000_000)throw new BusinessRuleException("Document exceeds the 2 million character limit");
            }
            return checked(text.toString(),Math.max(1,doc.getProperties().getExtendedProperties().getUnderlyingProperties().getPages()),"DOCX_TEXT");
        }
    }
    private String clean(String value){return value==null?"":value.replace('\u0000',' ').replaceAll("[\\r\\n\\t]+"," ").replaceAll("\\s{2,}"," ").trim();}
    private Extracted checked(String text,int pages,String method){String clean=text==null?"":text.replace("\u0000","").trim();if(clean.isBlank())throw new BusinessRuleException("No readable text was extracted");if(clean.length()>2_000_000)throw new BusinessRuleException("Document exceeds the 2 million character limit");return new Extracted(clean,pages,method);}
}
