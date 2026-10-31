package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.validation.StepInputScopeValidator;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StepInputScopeValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FacilityMapRepository maps = mock(FacilityMapRepository.class);
    private final EndpointRepository endpoints = mock(EndpointRepository.class);
    private final ZoneRepository zones = mock(ZoneRepository.class);
    private final EndpointGroupRepository groups = mock(EndpointGroupRepository.class);
    private final StepInputScopeValidator validator = new StepInputScopeValidator(maps, endpoints, zones, groups, mock(ServiceRequestRepository.class));

    @Test void rejectsCrossMapTargetsAndInaccessibleEndpoints() {
        FacilityMap map = FacilityMap.builder().mapId(UUID.randomUUID()).status(MapStatus.ACTIVE).build();
        Endpoint endpoint = Endpoint.builder().endpointId(UUID.randomUUID()).map(FacilityMap.builder().mapId(UUID.randomUUID()).build()).active(true).accessible(true).build();
        when(maps.findById(map.getMapId())).thenReturn(Optional.of(map));
        when(endpoints.findById(endpoint.getEndpointId())).thenReturn(Optional.of(endpoint));
        Step step = Step.builder().stepType(StepType.MOVE).stepCode("MOVE_A").build();
        var input = json.createObjectNode().put("map_id", map.getMapId().toString()).put("endpoint_id", endpoint.getEndpointId().toString());
        Job job = Job.builder().map(map).build();
        assertThatThrownBy(() -> validator.validate(step, input, job)).hasMessageContaining("MOVE_A").hasMessageContaining("another map");
        endpoint.setMap(map); endpoint.setAccessible(false);
        assertThatThrownBy(() -> validator.validate(step, input, job)).hasMessageContaining("accessible");
        endpoint.setAccessible(true); validator.validate(step, input, job);
    }

    @Test void rejectsCrossMapZoneGroupAndAnotherRequestsIdentity() {
        FacilityMap map = FacilityMap.builder().mapId(UUID.randomUUID()).build();
        FacilityMap other = FacilityMap.builder().mapId(UUID.randomUUID()).build();
        Zone zone = Zone.builder().zoneId(UUID.randomUUID()).map(other).active(true).build();
        EndpointGroup group = EndpointGroup.builder().endpointGroupId(UUID.randomUUID()).map(other).active(true).build();
        when(zones.findById(zone.getZoneId())).thenReturn(Optional.of(zone));
        when(groups.findById(group.getEndpointGroupId())).thenReturn(Optional.of(group));
        Step s = Step.builder().stepType(StepType.EXECUTE_SENSING).stepCode("SENSE").build();
        Job job = Job.builder().map(map).serviceRequest(ServiceRequest.builder().serviceRequestId(UUID.randomUUID()).build()).build();
        assertThatThrownBy(() -> validator.validate(s, json.createObjectNode().put("zone_id", zone.getZoneId().toString()), job)).hasMessageContaining("zone_id").hasMessageContaining("another map");
        assertThatThrownBy(() -> validator.validate(s, json.createObjectNode().put("endpoint_group_id", group.getEndpointGroupId().toString()), job)).hasMessageContaining("endpoint_group_id").hasMessageContaining("another map");
        assertThatThrownBy(() -> validator.validate(s, json.createObjectNode().put("service_request_id", UUID.randomUUID().toString()), job)).hasMessageContaining("outside this Job");
    }
}
