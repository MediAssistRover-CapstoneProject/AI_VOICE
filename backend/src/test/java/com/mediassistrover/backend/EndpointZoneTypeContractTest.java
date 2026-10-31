package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediassistrover.backend.dto.facility.request.FacilityRequests.EndpointRequest;
import com.mediassistrover.backend.dto.facility.request.FacilityRequests.ZoneRequest;
import com.mediassistrover.backend.entity.Endpoint;
import com.mediassistrover.backend.entity.Zone;
import com.mediassistrover.backend.enums.EndpointType;
import com.mediassistrover.backend.enums.ZoneType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointZoneTypeContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void endpointTypeContainsOnlyTheOttoContractValues() throws Exception {
        assertThat(Arrays.stream(EndpointType.values()).map(Enum::name))
                .containsExactly("WAYPOINT", "CHARGER", "PARK", "STAGING");
        assertThat(objectMapper.writeValueAsString(EndpointType.CHARGER)).isEqualTo("\"CHARGER\"");
        assertThatThrownBy(() -> objectMapper.readValue("\"PICKUP\"", EndpointType.class))
                .hasMessageContaining("PICKUP");
        assertThat(EndpointRequest.class.getRecordComponents()[5].getType()).isEqualTo(EndpointType.class);
        Schema schema = EndpointType.class.getAnnotation(Schema.class);
        assertThat(schema.enumAsRef()).isTrue();
        assertStringEnumPersistence(Endpoint.class, "endpointType");
    }

    @Test
    void zoneTypeContainsOnlyTheSupportedContractValues() throws Exception {
        assertThat(Arrays.stream(ZoneType.values()).map(Enum::name))
                .containsExactly("KEEP_OUT", "SPEED", "NO_STOP");
        assertThat(objectMapper.writeValueAsString(ZoneType.NO_STOP)).isEqualTo("\"NO_STOP\"");
        assertThatThrownBy(() -> objectMapper.readValue("\"RESTRICTED\"", ZoneType.class))
                .hasMessageContaining("RESTRICTED");
        assertThat(ZoneRequest.class.getRecordComponents()[3].getType()).isEqualTo(ZoneType.class);
        Schema schema = ZoneType.class.getAnnotation(Schema.class);
        assertThat(schema.enumAsRef()).isTrue();
        assertStringEnumPersistence(Zone.class, "zoneType");
    }

    private static void assertStringEnumPersistence(Class<?> entityType, String fieldName) throws Exception {
        Enumerated annotation = entityType.getDeclaredField(fieldName).getAnnotation(Enumerated.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(EnumType.STRING);
    }
}
