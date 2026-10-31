package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class Phase9OpenApiIntegrationTest extends MySqlIntegrationTest {

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options", "trace"
    );

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void publishesExplicitTrustBoundariesTagsAndReusableErrors() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/public/rooms'].get.security").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/public/rooms'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/public/rooms'].get.tags[0]")
                        .value("Patient Public Catalog"))
                .andExpect(jsonPath("$.paths['/api/v1/pi/requests/{requestId}'].get.security[0].deviceBearerAuth")
                        .isArray())
                .andExpect(jsonPath("$.paths['/api/v1/pi/requests/{requestId}'].get.tags[0]")
                        .value("Robot Screen Requests"))
                .andExpect(jsonPath("$.paths['/api/v1/jobs/{id}'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/jobs/{id}'].get.tags[0]").value("Staff Operations"))
                .andExpect(jsonPath("$.paths['/api/v1/delivery-requests/{id}/confirm-pickup'].post.tags[0]")
                        .value("Staff Delivery"))
                .andExpect(jsonPath("$.paths['/api/v1/delivery-requests/{id}/confirm-pickup'].post.responses['410'].$ref")
                        .value("#/components/responses/LegacyExecutionRetired"))
                .andExpect(jsonPath("$.paths['/api/v1/alerts'].get.tags[0]").value("Staff Alerts"))
                .andExpect(jsonPath("$.paths['/api/v1/emergency-incidents'].get.tags[0]")
                        .value("Staff Incidents"))
                .andExpect(jsonPath("$.components.responses.ValidationError.content['application/json'].schema.$ref")
                        .value("#/components/schemas/ApiErrorResponse"))
                .andExpect(jsonPath("$.components.responses.Unauthorized").exists())
                .andExpect(jsonPath("$.components.responses.Forbidden").exists())
                .andExpect(jsonPath("$.components.responses.ResourceNotFound").exists())
                .andExpect(jsonPath("$.components.responses.Conflict").exists())
                .andExpect(jsonPath("$.components.responses.BusinessRuleViolation").exists());
    }

    @Test
    void publishesEndpointAndZoneEnumsAsClosedStringContracts() throws Exception {
        JsonNode document = objectMapper.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(document.at("/components/schemas/EndpointRequest/properties/endpointType/$ref").asText())
                .isEqualTo("#/components/schemas/EndpointType");
        assertThat(document.at("/components/schemas/EndpointType/type").asText()).isEqualTo("string");
        assertThat(document.at("/components/schemas/EndpointType/enum"))
                .isEqualTo(objectMapper.valueToTree(List.of("WAYPOINT", "CHARGER", "PARK", "STAGING")));
        assertThat(document.at("/components/schemas/ZoneRequest/properties/zoneType/$ref").asText())
                .isEqualTo("#/components/schemas/ZoneType");
        assertThat(document.at("/components/schemas/ZoneType/type").asText()).isEqualTo("string");
        assertThat(document.at("/components/schemas/ZoneType/enum"))
                .isEqualTo(objectMapper.valueToTree(List.of("KEEP_OUT", "SPEED", "NO_STOP")));
    }

    @Test
    void keepsEveryPhase0PathAndOperationForBackwardCompatibility() throws Exception {
        Path baselinePath = Path.of("docs", "baselines", "openapi-phase0.json");
        assertThat(Files.exists(baselinePath)).as("Phase 0 OpenAPI baseline must be available").isTrue();

        JsonNode baseline = objectMapper.readTree(Files.readString(baselinePath));
        JsonNode current = objectMapper.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        List<String> removedOperations = new ArrayList<>();
        baseline.path("paths").fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode currentPath = current.path("paths").path(path);
            Iterator<String> names = pathEntry.getValue().fieldNames();
            while (names.hasNext()) {
                String method = names.next();
                if (HTTP_METHODS.contains(method) && currentPath.path(method).isMissingNode()) {
                    removedOperations.add(method.toUpperCase() + " " + path);
                }
            }
        });

        assertThat(removedOperations)
                .as("Phase 9 must remain additive; removed operations require an explicit compatibility route")
                .isEmpty();
    }
}
