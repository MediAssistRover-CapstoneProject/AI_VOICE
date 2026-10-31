package com.mediassistrover.backend.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.zip.ZipInputStream;

/** Additive image view of the existing verified bundle; never fabricates occupancy from geometry. */
@Service @RequiredArgsConstructor
public class SavedMapImageService {
    private final MapBundleService bundles;
    private final ObjectMapper json;
    private record Saved(byte[] pgm,ObjectNode metadata) { }
    public record Image(byte[] png,ObjectNode metadata) { }

    public Image image(UUID mapId) {
        Saved saved=load(mapId);
        return new Image(PgmPngRenderer.render(saved.pgm()),saved.metadata());
    }

    public ObjectNode metadata(UUID mapId) {
        ObjectNode meta=load(mapId).metadata();
        meta.put("image_url","/api/v1/maps/"+mapId+"/image.png");
        meta.put("image_format","png").put("pixel_origin","TOP_LEFT").put("row_order","TOP_TO_BOTTOM");
        meta.put("navigation_reload_safe",bundles.navigationSafe(mapId));
        // Matrix maps image cell-centre indices [u,v,1] into map-frame metres.
        double r=meta.path("resolution_m_per_pixel").asDouble(),h=meta.path("height_px").asDouble();
        double yaw=meta.path("origin_yaw_rad").asDouble(),c=Math.cos(yaw),s=Math.sin(yaw);
        var transform=meta.putArray("pixel_center_to_map");
        transform.addArray().add(r*c).add(r*s).add(meta.path("origin_x_m").asDouble()+r*c*.5-r*s*(h-.5));
        transform.addArray().add(r*s).add(-r*c).add(meta.path("origin_y_m").asDouble()+r*s*.5+r*c*(h-.5));
        transform.addArray().add(0).add(0).add(1);
        return meta;
    }

    private Saved load(UUID mapId) {
        // Reuse all existing UUID, source, asset-hash and rounded catalog-geometry checks.
        // The existing ZIP API and its payload are unchanged.
        byte[] verified=bundles.bundle(mapId,null);
        byte[] pgm=null;ObjectNode metadata=null;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(verified))) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if("map.pgm".equals(entry.getName())) {
                    pgm=zip.readNBytes(4_100_001);if(pgm.length>4_100_000)throw invalid();
                } else if("metadata.json".equals(entry.getName())) {
                    byte[] bytes=zip.readNBytes(65_537);if(bytes.length>65_536)throw invalid();
                    if(!(json.readTree(bytes) instanceof ObjectNode object))throw invalid();metadata=object;
                }
            }
        } catch(IOException e){throw invalid();}
        if(pgm==null||metadata==null)throw invalid();
        return new Saved(pgm,metadata);
    }
    private BusinessRuleException invalid(){return new BusinessRuleException("Saved map image files are not available");}
}
