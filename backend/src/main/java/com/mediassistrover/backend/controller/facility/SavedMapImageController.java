package com.mediassistrover.backend.controller.facility;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.mapping.SavedMapImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/maps") @RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','STAFF')") @Tag(name="Facility Catalog")
public class SavedMapImageController {
    private final SavedMapImageService images;

    @GetMapping(value="/{mapId}/image.png",produces=MediaType.IMAGE_PNG_VALUE)
    @Operation(summary="View the saved map as PNG",
        description="Reads the verified saved YAML/PGM assets for this map UUID. Native pixels, top-left image origin; no live robot or mapping session required. Geometry alone cannot generate a real map image.")
    public ResponseEntity<byte[]> image(@PathVariable UUID mapId) {
        var image=images.image(mapId);var meta=image.metadata();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename("map-"+mapId+".png").build().toString())
            .header("X-Map-Id",mapId.toString()).header("X-Map-Asset-Hash",meta.path("asset_hash").asText())
            .header("X-Map-Width-Px",meta.path("width_px").asText()).header("X-Map-Height-Px",meta.path("height_px").asText())
            .body(image.png());
    }

    @GetMapping("/{mapId}/image-metadata")
    @Operation(summary="Get exact saved-map geometry and pixel-to-map transform",
        description="Geometry comes from the verified YAML/PGM, including the unrounded origin and yaw. pixel_center_to_map maps [column,row,1] cell-centre indices to map-frame metres; row zero is the top PNG row.")
    public ResponseEntity<ObjectNode> metadata(@PathVariable UUID mapId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.metadata(mapId));
    }
}
