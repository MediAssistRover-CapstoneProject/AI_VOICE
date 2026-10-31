package com.mediassistrover.backend.mapping;

import com.mediassistrover.backend.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

class PgmPngRendererTest {
    @Test void binaryPixelsIncludingHeaderLikeBytesKeepTheirPositions() throws Exception {
        byte[] header="P5\r\n# Saved map\r\n2 3\r\n255\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] pgm=java.util.Arrays.copyOf(header,header.length+6);
        byte[] values={10,35,13,32,(byte)205,(byte)254};System.arraycopy(values,0,pgm,header.length,6);
        var image=ImageIO.read(new ByteArrayInputStream(PgmPngRenderer.render(pgm)));
        assertThat(image.getWidth()).isEqualTo(2);assertThat(image.getHeight()).isEqualTo(3);
        for(int i=0;i<6;i++)assertThat(image.getRaster().getSample(i%2,i/2,0)).isEqualTo(Byte.toUnsignedInt(values[i]));
    }
    @Test void asciiPgmRetainsUnknownAndOccupiedSamples() throws Exception {
        var image=ImageIO.read(new ByteArrayInputStream(PgmPngRenderer.render("P2\n# comment\n2 2\n255\n0 205\n254 255\n".getBytes())));
        assertThat(image.getRaster().getSamples(0,0,2,2,0,(int[])null)).containsExactly(0,205,254,255);
    }
    @Test void rejectsOversizedMalformedAndTruncatedRasters() {
        for(String source:java.util.List.of("P5\n2 2\n255\n0","P2\n2 2\n255\n0 1 2 256",
                "P2\n2 2\n255\n0 1 2 3 4","P2\n2001 2000\n255\n0","P2\n0 2\n255\n0",
                "P2\n2 2\n65535\n0 1 2 3","P6\n2 2\n255\n0 1 2 3")) {
            assertThatThrownBy(()->PgmPngRenderer.render(source.getBytes())).isInstanceOf(BusinessRuleException.class);
        }
    }
}
