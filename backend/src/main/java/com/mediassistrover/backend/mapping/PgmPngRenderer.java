package com.mediassistrover.backend.mapping;

import com.mediassistrover.backend.exception.BusinessRuleException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Lossless display conversion: PGM and PNG both store rows from top to bottom. */
final class PgmPngRenderer {
    private PgmPngRenderer() { }

    static byte[] render(byte[] pgm) {
        if(pgm.length>4_100_000)throw invalid();
        Parser parser=new Parser(pgm);
        String magic=parser.token();
        int width=parser.integer(),height=parser.integer(),max=parser.integer();
        long cells=(long)width*height;
        if(!("P5".equals(magic)||"P2".equals(magic)) || width<1 || height<1 || cells>4_000_000 || max!=255)throw invalid();
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_BYTE_GRAY);
        byte[] pixels=((DataBufferByte)image.getRaster().getDataBuffer()).getData();
        if("P5".equals(magic)) {
            // Consume the header separator only: binary pixels may themselves be whitespace or '#'.
            parser.binarySeparator();
            if(pgm.length-parser.offset!=cells)throw invalid();
            System.arraycopy(pgm,parser.offset,pixels,0,(int)cells);
        } else {
            for(int i=0;i<cells;i++) {
                int sample=parser.integer();if(sample>255)throw invalid();pixels[i]=(byte)sample;
            }
            parser.skip();if(parser.offset!=pgm.length)throw invalid();
        }
        try(var output=new ByteArrayOutputStream()) {
            if(!ImageIO.write(image,"png",output))throw invalid();
            return output.toByteArray();
        } catch(IOException e){throw new BusinessRuleException("Cannot render saved map PNG");}
    }

    private static BusinessRuleException invalid(){return new BusinessRuleException("Invalid saved map PGM");}
    private static final class Parser {
        final byte[] bytes;int offset;
        Parser(byte[] bytes){this.bytes=bytes;}
        boolean space(byte b){return b==' '||b=='\n'||b=='\r'||b=='\t'||b=='\f';}
        void skip(){while(offset<bytes.length){if(space(bytes[offset]))offset++;else if(bytes[offset]=='#'){while(offset<bytes.length&&bytes[offset]!='\n')offset++;}else break;}}
        String token(){skip();int start=offset;while(offset<bytes.length&&!space(bytes[offset])&&bytes[offset]!='#')offset++;
            if(offset==start||offset-start>16)throw invalid();return new String(bytes,start,offset-start,StandardCharsets.US_ASCII);}
        int integer(){String token=token();if(!token.matches("[0-9]{1,9}"))throw invalid();return Integer.parseInt(token);}
        void binarySeparator(){if(offset>=bytes.length||!space(bytes[offset]))throw invalid();
            byte first=bytes[offset++];if(first=='\r'&&offset<bytes.length&&bytes[offset]=='\n')offset++;}
    }
}
