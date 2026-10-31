package com.mediassistrover.backend.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.repository.MappingSessionRepository;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.*;
import java.util.zip.InflaterInputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Latest bounded map/camera frame per robot. Frames are not persisted as JobEvents. */
@Service @RequiredArgsConstructor
public class MappingPreviewService {
    private final MappingSessionRepository sessions;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Map<UUID,Frame> latest=new ConcurrentHashMap<>();
    private final Map<UUID,Picture> cameras=new ConcurrentHashMap<>();
    private record Frame(UUID sessionId,Instant captured,long revision,JsonNode data,byte[] png) { }
    public record Picture(UUID sessionId,Instant captured,long revision,byte[] bytes) { }
    public record Changed(UUID jobId,JsonNode preview) { }
    public void accept(UUID robotId,JsonNode payload) {
        UUID sessionId=UUID.fromString(payload.path("mapping_session_id").asText());var session=sessions.findById(sessionId).orElse(null);
        if(session==null || !session.getRobotId().equals(robotId) || !session.isPotentiallyOpen())return;
        Instant captured=Instant.parse(payload.path("captured_at").asText());if(!fresh(captured))return;
        long cells=(long)payload.path("width").asInt()*payload.path("height").asInt();
        if(!payload.path("width").canConvertToInt() || !payload.path("height").canConvertToInt() || payload.path("width").asInt()<1
                || payload.path("height").asInt()<1 || cells<1 || cells>4_000_000)throw new BusinessRuleException("Preview grid exceeds 4 million cells");
        Frame prior=latest.get(robotId);long revision=payload.path("revision").asLong();
        if(prior!=null && prior.sessionId().equals(sessionId) && (!captured.isAfter(prior.captured()) || revision<=prior.revision()))return;
        byte[] png;
        try(var inflated=new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payload.path("data").asText())))) {
            byte[] raster=inflated.readNBytes((int)cells+1);if(raster.length!=cells)throw new BusinessRuleException("Preview raster length differs from grid");
            int width=payload.path("width").asInt(),height=payload.path("height").asInt();
            BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_BYTE_GRAY);
            byte[] pixels=((java.awt.image.DataBufferByte)image.getRaster().getDataBuffer()).getData();
            for(int row=0;row<height;row++)for(int col=0;col<width;col++) {
                int value=raster[row*width+col];if(value< -1 || value>100)throw new BusinessRuleException("Invalid occupancy preview value");
                pixels[(height-1-row)*width+col]=(byte)(value==-1?205:254-Math.round(value*2.54f));
            }
            var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);png=output.toByteArray();
        } catch(IOException | IllegalArgumentException ex){throw new BusinessRuleException("Invalid zlib/base64 preview");}
        latest.entrySet().removeIf(e->!fresh(e.getValue().captured()));
        Frame frame=new Frame(sessionId,captured,revision,payload.deepCopy(),png);
        Frame selected=latest.compute(robotId,(id,old)->old!=null && old.sessionId().equals(sessionId)
                && (!captured.isAfter(old.captured()) || revision<=old.revision())?old:frame);
        if(selected==frame)events.publishEvent(new Changed(session.getJobId(),frame.data().deepCopy()));
    }
    public void acceptCamera(UUID robotId,JsonNode payload) {
        UUID sessionId=UUID.fromString(payload.path("mapping_session_id").asText());var session=sessions.findById(sessionId).orElse(null);
        if(session==null || !session.getRobotId().equals(robotId) || !session.isPotentiallyOpen() || !"MAPPING".equals(session.getState()))return;
        Instant captured=Instant.parse(payload.path("captured_at").asText());if(!fresh(captured))return;
        byte[] bytes;
        try {
            bytes=Base64.getDecoder().decode(payload.path("data").asText());
            if(bytes.length>65536)throw new BusinessRuleException("Camera JPEG exceeds 64 KiB");
            try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new BusinessRuleException("Camera payload is not an image");
                var reader=readers.next();
                try {
                    reader.setInput(stream);int w=reader.getWidth(0),h=reader.getHeight(0);
                    if(!"JPEG".equalsIgnoreCase(reader.getFormatName()) || w<1 || h<1 || w>640 || h>480
                            || w!=payload.path("width").asInt() || h!=payload.path("height").asInt())throw new BusinessRuleException("Invalid camera JPEG dimensions");
                    reader.read(0); // Decode only after dimensions are bounded.
                } finally {reader.dispose();}
            }
        } catch(IOException | IllegalArgumentException ex){throw new BusinessRuleException("Invalid camera JPEG");}
        cameras.entrySet().removeIf(e->!fresh(e.getValue().captured()));
        Picture frame=new Picture(sessionId,captured,payload.path("revision").asLong(),bytes);
        cameras.compute(robotId,(id,old)->old!=null && old.sessionId().equals(sessionId)
                && (!captured.isAfter(old.captured()) || frame.revision()<=old.revision())?old:frame);
    }
    private boolean fresh(Instant at) {Instant now=Instant.now(clock);return !at.isBefore(now.minusSeconds(10)) && !at.isAfter(now.plusSeconds(5));}
    private Frame frame(UUID sessionId) {
        var session=sessions.findById(sessionId).orElse(null);if(session==null || !session.isPotentiallyOpen())return null;
        Frame frame=latest.get(session.getRobotId());
        return frame!=null && frame.sessionId().equals(sessionId) && fresh(frame.captured())?frame:null;
    }
    public JsonNode latest(UUID sessionId) {Frame frame=frame(sessionId);return frame==null?null:frame.data().deepCopy();}
    /** Geometry and pose from the same revision as the PNG, without transferring the occupancy grid. */
    public JsonNode metadata(UUID sessionId) {
        Frame frame=frame(sessionId);if(frame==null)return null;
        ObjectNode data=(ObjectNode)frame.data().deepCopy();data.remove("data");
        double resolution=data.path("resolution").asDouble();
        data.put("width_m",data.path("width").asInt()*resolution).put("height_m",data.path("height").asInt()*resolution);
        return data;
    }
    public Picture mapImage(UUID sessionId) {Frame frame=frame(sessionId);return frame==null?null:new Picture(sessionId,frame.captured(),frame.revision(),frame.png());}
    public Picture cameraImage(UUID sessionId) {
        var session=sessions.findById(sessionId).orElse(null);if(session==null || !session.isPotentiallyOpen())return null;
        Picture frame=cameras.get(session.getRobotId());
        return frame!=null && frame.sessionId().equals(sessionId) && fresh(frame.captured())?frame:null;
    }
}
