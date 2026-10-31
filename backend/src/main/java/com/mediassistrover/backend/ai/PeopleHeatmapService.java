package com.mediassistrover.backend.ai;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.dto.monitoring.request.MonitoringRequests.HeatmapCreate;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** Counts distinct producer tracks at their latest observed position, never spreads a zone count over a map. */
@Service @RequiredArgsConstructor
public class PeopleHeatmapService {
    private final DetectionEventRepository detections;
    private final MonitoringIngestionReceiptRepository receipts;
    private final AiSensingService ai;
    private final ObjectMapper json;
    public record Generated(ObjectNode grid,BigDecimal maxValue,int sourceCount) { }
    public Generated generate(HeatmapCreate request,FacilityMap map,String type) {
        if(!Set.of("PERSON_POSITIONS","CROWD_DENSITY").contains(type))fail("Heatmap type requires a versioned source contract; supported type is PERSON_POSITIONS");
        JsonNode input=request.gridData();Set<String> fields=new HashSet<>();input.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("frame_id","source_detection_ids")) || !map.getFrameId().equals(input.path("frame_id").asText())
                || !input.path("source_detection_ids").isArray() || input.path("source_detection_ids").isEmpty() || input.path("source_detection_ids").size()>10_000)
            fail("People heatmap requires frame_id and verified source_detection_ids; count/range grids cannot prove person positions");
        double cell=request.cellSizeM().doubleValue();if(!Double.isFinite(cell) || cell<=0)fail("Heatmap cell size must be positive/finite");
        double colsDouble=Math.ceil(map.getWidthM().doubleValue()/cell),rowsDouble=Math.ceil(map.getHeightM().doubleValue()/cell);
        if(colsDouble<1 || rowsDouble<1 || colsDouble*rowsDouble>250_000)fail("Heatmap exceeds 250000 cells");int cols=(int)colsDouble,rows=(int)rowsDouble;
        Set<UUID> ids=new HashSet<>();Map<Track,DetectionEvent> tracks=new HashMap<>();
        for(JsonNode n:input.path("source_detection_ids")) {
            UUID id;try{id=UUID.fromString(n.asText());if(!id.toString().equalsIgnoreCase(n.asText()))throw new IllegalArgumentException();}catch(IllegalArgumentException ex){throw new BusinessRuleException("Heatmap source ID must be a canonical UUID");}
            if(!ids.add(id))fail("Heatmap source IDs must be unique");
            var event=detections.findById(id).orElseThrow(()->new BusinessRuleException("Heatmap detection source not found"));
            var receipt=receipts.findById(id).orElse(null);
            if(receipt==null || !receipt.getRobotId().equals(event.getRobot().getRobotId()) || !receipt.getMessageType().equals("DETECTION_EVENT")
                    || !event.getMap().getMapId().equals(map.getMapId()) || event.getDetectedAt().isBefore(request.periodStart()) || !event.getDetectedAt().isBefore(request.periodEnd())
                    || !ai.verifiedPersonPosition(event,receipt.getProvenanceJson()))fail("Heatmap source lacks commissioned person coordinates or is outside map/frame/window");
            cell(event,map,cell,cols,rows); // Validate every supplied source, including earlier positions of the same track.
            var payload=event.getEventPayloadJson();Track key=new Track(event.getRobot().getRobotId(),event.getModelVersion(),payload.path("producer_ref").asText(),payload.path("track_id").asText());
            var prior=tracks.get(key);
            if(prior!=null && prior.getDetectedAt().equals(event.getDetectedAt()) && (prior.getXM().compareTo(event.getXM())!=0 || prior.getYM().compareTo(event.getYM())!=0))fail("One track has conflicting positions at the same timestamp");
            if(prior==null || prior.getDetectedAt().isBefore(event.getDetectedAt()))tracks.put(key,event);
        }
        if(request.sourceRecordCount()!=ids.size())fail("sourceRecordCount must equal verified source records");
        int[][] counts=new int[rows][cols];int max=0;
        for(var event:tracks.values()){int[] xy=cell(event,map,cell,cols,rows);max=Math.max(max,++counts[xy[1]][xy[0]]);}
        if(request.maxValue()!=null && request.maxValue().compareTo(BigDecimal.valueOf(max))!=0)fail("maxValue differs from computed grid");
        ObjectNode grid=json.createObjectNode().put("contract","BE-PERSON-HEATMAP-1").put("frame_id",map.getFrameId()).put("map_asset_hash",map.getAssetHash())
                .put("columns",cols).put("rows",rows).put("cell_size_m",cell).put("origin_x_m",map.getOriginXM()).put("origin_y_m",map.getOriginYM()).put("origin_yaw_rad",map.getOriginYawRad())
                .put("unique_tracks",tracks.size()).put("sampling_policy","LATEST_POSITION_PER_TRACK_PER_PERIOD");
        var sources=grid.putArray("source_detection_ids");ids.stream().sorted().forEach(id->sources.add(id.toString()));
        var raster=grid.putArray("counts");for(int[] row:counts){var values=raster.addArray();for(int value:row)values.add(value);}
        return new Generated(grid,BigDecimal.valueOf(max),ids.size());
    }
    private int[] cell(DetectionEvent event,FacilityMap map,double size,int cols,int rows) {
        double dx=event.getXM().doubleValue()-map.getOriginXM().doubleValue(),dy=event.getYM().doubleValue()-map.getOriginYM().doubleValue(),yaw=map.getOriginYawRad().doubleValue();
        double x=Math.cos(yaw)*dx+Math.sin(yaw)*dy,y=-Math.sin(yaw)*dx+Math.cos(yaw)*dy;
        if(!Double.isFinite(x) || !Double.isFinite(y) || x<0 || y<0 || x>=map.getWidthM().doubleValue() || y>=map.getHeightM().doubleValue())fail("Person coordinate is outside map bounds");
        int col=(int)Math.floor(x/size),row=(int)Math.floor(y/size);if(col>=cols || row>=rows)fail("Coordinate is outside heatmap grid");return new int[]{col,row};
    }
    private static void fail(String message){throw new BusinessRuleException(message);}
    private record Track(UUID robotId,String model,String producer,String trackId) { }
}
