package com.mediassistrover.backend.controller.facility;
import com.mediassistrover.backend.mapping.MapBundleService;
import com.mediassistrover.backend.security.DevicePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.UUID;

@RestController @RequiredArgsConstructor @Validated @Tag(name="Facility Catalog")
public class MapBundleController {
    private final MapBundleService maps;
    @PostMapping("/api/v1/maps/{mapId}/navigation-copy") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary="Create a new DRAFT map with corrected trinary reload thresholds; copies endpoints and preserves the original map/hash")
    public MapBundleService.Imported navigationCopy(@PathVariable UUID mapId){return maps.navigationCopy(mapId);}
    public record NavigationPoint(@jakarta.validation.constraints.NotNull Double xM,@jakarta.validation.constraints.NotNull Double yM) { }
    @PostMapping("/api/v1/maps/{mapId}/validate-navigation-point") @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary="Validate a point centre against verified saved occupancy. Nav2 still checks the full footprint and route.")
    public java.util.Map<String,Object> point(@PathVariable UUID mapId,@jakarta.validation.Valid @RequestBody NavigationPoint body){
        if(!Double.isFinite(body.xM())||!Double.isFinite(body.yM()))throw new com.mediassistrover.backend.exception.BusinessRuleException("Finite map coordinates required");
        maps.requireFreePoint(mapId,body.xM(),body.yM());return java.util.Map.of("mapId",mapId,"knownFree",true);
    }
    @PostMapping(value="/api/v1/maps/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="Upload map.yaml and map.pgm; creates a DRAFT map with verified geometry/hash")
    public MapBundleService.Imported upload(@RequestParam UUID floorId,@RequestParam @NotBlank @Size(max=64) String mapCode,
        @RequestParam(defaultValue="1") @Min(1) int versionNo,@RequestParam @NotBlank @Size(max=160) String mapName,
        @RequestPart MultipartFile yaml,@RequestPart MultipartFile pgm) throws IOException {
        if(yaml.getSize()>65536||pgm.getSize()>4_100_000)throw new com.mediassistrover.backend.exception.BusinessRuleException("Map file too large");
        return maps.importMap(floorId,mapCode,versionNo,mapName,yaml.getBytes(),pgm.getBytes());
    }
    @GetMapping(value="/api/v1/pi/maps/{mapId}/bundle",produces="application/zip")
    @PreAuthorize("hasAnyRole('DEVICE','ADMIN')")
    @Operation(summary="Download verified YAML/PGM/metadata ZIP; DEVICE is limited to its assigned map")
    public ResponseEntity<byte[]> download(@PathVariable UUID mapId,@AuthenticationPrincipal Object principal) {
        if(!(principal instanceof DevicePrincipal) && org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication().getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")))
            throw new org.springframework.security.access.AccessDeniedException("Device identity is required");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("map-"+mapId+".zip").build().toString())
            .cacheControl(CacheControl.noStore()).body(maps.bundle(mapId,principal instanceof DevicePrincipal p?p:null));
    }
}
