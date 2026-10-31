package com.mediassistrover.backend.controller.pi;
import com.mediassistrover.backend.mapping.MappingArtifactService;
import com.mediassistrover.backend.security.DevicePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.UUID;
@RestController @RequestMapping("/api/v1/pi") @PreAuthorize("hasRole('DEVICE')") @RequiredArgsConstructor
public class MappingArtifactController {
    private final MappingArtifactService artifacts;
    @PutMapping(value="/mapping-sessions/{sessionId}/artifacts/{artifactId}",consumes="application/gzip")
    public MappingArtifactService.Receipt upload(@PathVariable UUID sessionId,@PathVariable UUID artifactId,@RequestHeader("X-Asset-SHA256") String assetHash,
            @RequestHeader("X-Archive-SHA256") String archiveHash,HttpServletRequest request,@AuthenticationPrincipal DevicePrincipal device) throws IOException {
        return artifacts.upload(sessionId,artifactId,assetHash,archiveHash,request.getInputStream(),device);
    }
    @GetMapping(value="/mapping-artifacts/{artifactId}",produces="application/gzip")
    public ResponseEntity<byte[]> download(@PathVariable UUID artifactId,@AuthenticationPrincipal DevicePrincipal device) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"map-"+artifactId+".tar.gz\"").cacheControl(CacheControl.noStore()).body(artifacts.download(artifactId,device));
    }
}
