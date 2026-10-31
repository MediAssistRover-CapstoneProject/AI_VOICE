package com.mediassistrover.backend;

import com.mediassistrover.backend.dto.facility.request.FacilityRequests.EndpointRequest;
import com.mediassistrover.backend.entity.Endpoint;
import com.mediassistrover.backend.enums.EndpointType;
import com.mediassistrover.backend.mapper.FacilityMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FacilityMapperCoordinatesTest {
    private final FacilityMapper mapper = Mappers.getMapper(FacilityMapper.class);

    @Test
    void responseContainsPersistedEndpointCoordinates() {
        Endpoint endpoint = Endpoint.builder()
                .endpointCode("EP_GAZEBO_NORTH_04")
                .endpointName("Gazebo North 04")
                .endpointType(EndpointType.WAYPOINT)
                .xM(new BigDecimal("3.5000"))
                .yM(new BigDecimal("2.1250"))
                .yawRad(BigDecimal.ZERO)
                .build();

        var response = mapper.toResponse(endpoint);
        assertEquals(new BigDecimal("3.5000"), response.xM());
        assertEquals(new BigDecimal("2.1250"), response.yM());
    }

    @Test
    void createRequestRetainsCoordinatesInEntity() {
        var request = new EndpointRequest(
                null, null, null, "EP_GAZEBO_NORTH_04", "Gazebo North 04",
                EndpointType.WAYPOINT, new BigDecimal("3.5"),
                new BigDecimal("2.125"), BigDecimal.ZERO,
                false, true, false, "Mapper coordinate regression");
        Endpoint endpoint = mapper.toEntity(request);
        assertEquals(new BigDecimal("3.5"), endpoint.getXM());
        assertEquals(new BigDecimal("2.125"), endpoint.getYM());
    }
}