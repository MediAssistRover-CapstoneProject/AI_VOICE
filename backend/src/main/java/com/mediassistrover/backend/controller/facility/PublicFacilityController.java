package com.mediassistrover.backend.controller.facility;

import com.mediassistrover.backend.dto.common.PageResponse;
import com.mediassistrover.backend.dto.facility.response.PublicFacilityResponses.*;
import com.mediassistrover.backend.enums.EndpointType;
import com.mediassistrover.backend.enums.ZoneType;
import com.mediassistrover.backend.service.PublicFacilityCatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
@Validated
@Tag(name = "Public Facility Catalog", description = "Anonymous patient-safe active/public catalog")
public class PublicFacilityController {
    private final PublicFacilityCatalogService service;

    @GetMapping("/hospital-information")
    public PageResponse<Hospital> hospitals(@RequestParam(required=false) String q,
            @RequestParam(required=false) String category, @RequestParam(required=false) String languageCode,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return PageResponse.from(service.hospitals(q, category, languageCode, page(page,size,"title")));
    }
    @GetMapping("/hospital-information/{id}") public Hospital hospital(@PathVariable UUID id){return service.hospital(id);}
    @GetMapping("/hospital-information/{hospitalId}/articles")
    public PageResponse<Article> articles(@PathVariable UUID hospitalId, @RequestParam(required=false) String q,
            @RequestParam(required=false) String category, @RequestParam(required=false) String languageCode,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return PageResponse.from(service.articles(hospitalId,q,category,languageCode,page(page,size,"articleCode")));
    }
    @GetMapping("/hospital-information/{hospitalId}/articles/{articleId}")
    public Article article(@PathVariable UUID hospitalId,@PathVariable UUID articleId){return service.article(hospitalId,articleId);}

    @GetMapping("/floors") public PageResponse<Floor> floors(@RequestParam(required=false) UUID hospitalInformationId,
            @RequestParam(required=false) String q,@RequestParam(defaultValue="0")@Min(0)int page,
            @RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.floors(hospitalInformationId,q,page(page,size,"displayOrder")));}
    @GetMapping("/floors/{id}") public Floor floor(@PathVariable UUID id){return service.floor(id);}

    @GetMapping("/zones") public PageResponse<Zone> zones(@RequestParam(required=false) UUID mapId,
            @RequestParam(required=false) ZoneType zoneType,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.zones(mapId,zoneType,q,page(page,size,"zoneCode")));}
    @GetMapping("/zones/{id}") public Zone zone(@PathVariable UUID id){return service.zone(id);}

    @GetMapping("/rooms") public PageResponse<Room> rooms(@RequestParam(required=false) UUID floorId,
            @RequestParam(required=false) String roomType,@RequestParam(required=false) String departmentCode,
            @RequestParam(required=false) String q,@RequestParam(defaultValue="0")@Min(0)int page,
            @RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.rooms(floorId,roomType,departmentCode,q,page(page,size,"roomCode")));}
    @GetMapping("/rooms/{id}") public Room room(@PathVariable UUID id){return service.room(id);}

    @GetMapping("/endpoints") public PageResponse<Endpoint> endpoints(@RequestParam(required=false) UUID mapId,
            @RequestParam(required=false) UUID roomId,@RequestParam(required=false) EndpointType endpointType,
            @RequestParam(required=false) Boolean accessible,@RequestParam(required=false) String q,
            @RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.endpoints(mapId,roomId,endpointType,accessible,q,page(page,size,"endpointCode")));}
    @GetMapping("/endpoints/{id}") public Endpoint endpoint(@PathVariable UUID id){return service.endpoint(id);}

    @GetMapping("/endpoint-groups") public PageResponse<EndpointGroup> groups(@RequestParam(required=false) UUID mapId,
            @RequestParam(required=false) String q,@RequestParam(defaultValue="0")@Min(0)int page,
            @RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.endpointGroups(mapId,q,page(page,size,"groupCode")));}
    @GetMapping("/endpoint-groups/{id}") public EndpointGroup group(@PathVariable UUID id){return service.endpointGroup(id);}
    @GetMapping("/endpoint-groups/{id}/members") public PageResponse<Endpoint> members(@PathVariable UUID id,
            @RequestParam(defaultValue="0")@Min(0)int page,@RequestParam(defaultValue="20")@Min(1)@Max(100)int size){return PageResponse.from(service.endpointGroupMembers(id,page(page,size,"sequenceNo")));}

    private PageRequest page(int page,int size,String sort){return PageRequest.of(page,size,Sort.by(sort).ascending());}
}
