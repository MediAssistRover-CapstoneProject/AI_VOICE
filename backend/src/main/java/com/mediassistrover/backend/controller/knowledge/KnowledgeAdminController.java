package com.mediassistrover.backend.controller.knowledge;
import com.mediassistrover.backend.knowledge.*;
import com.mediassistrover.backend.knowledge.KnowledgeDtos.DocumentView;
import com.mediassistrover.backend.security.AccountPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.Instant;
import java.util.*;
@RestController @RequestMapping("/api/v1/admin/knowledge/documents") @RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")
public class KnowledgeAdminController {
    private final KnowledgeAdminService service;
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public DocumentView upload(@RequestPart("file") MultipartFile file,@RequestParam String title,@RequestParam String category,@RequestParam(defaultValue="vi-VN") String language,@RequestParam(defaultValue="PUBLIC") String visibility,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant effectiveFrom,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant effectiveUntil,@RequestParam(required=false) UUID replacesDocumentId,@AuthenticationPrincipal AccountPrincipal actor){return service.upload(file,title,category,language,visibility,effectiveFrom,effectiveUntil,replacesDocumentId,actor);}
    @GetMapping public List<DocumentView> list(){return service.list();}@GetMapping("/{id}") public DocumentView get(@PathVariable UUID id){return service.get(id);}
    @PostMapping("/{id}/reindex") public DocumentView reindex(@PathVariable UUID id){return service.reindex(id);}@DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void archive(@PathVariable UUID id){service.archive(id);}
}
