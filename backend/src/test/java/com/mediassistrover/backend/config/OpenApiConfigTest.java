package com.mediassistrover.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void reusableErrorsIncludeNestedFieldViolationSchema() {
        OpenAPI openApi=config.mediAssistRoverOpenApi();
        config.phase9ContractCustomizer().customise(openApi);
        JsonNode document=Json.mapper().valueToTree(openApi);
        assertThat(document.at("/components/schemas/FieldViolation/properties/field").isMissingNode()).isFalse();
        assertThat(document.at("/components/schemas/FieldViolation/properties/message").isMissingNode()).isFalse();
        assertLocalReferences(document,document);
    }

    private void assertLocalReferences(JsonNode node,JsonNode document) {
        if(node.isObject() && node.has("$ref")) {
            String ref=node.path("$ref").asText();
            if(ref.startsWith("#/"))assertThat(document.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
        }
        if(node.isContainerNode())node.forEach(child->assertLocalReferences(child,document));
    }

    @Test
    void customizesAnonymousDeviceAndUserOperationsWithoutAGlobalSecurityDefault() {
        OpenAPI openApi = config.mediAssistRoverOpenApi()
                .paths(new Paths()
                        .addPathItem("/api/v1/public/rooms", item(PathItem.HttpMethod.GET))
                        .addPathItem("/api/v1/pi/requests/{requestId}", item(PathItem.HttpMethod.GET))
                        .addPathItem("/api/v1/jobs/{id}", item(PathItem.HttpMethod.GET)));

        config.phase9ContractCustomizer().customise(openApi);

        assertThat(openApi.getSecurity()).isNull();
        assertThat(operation(openApi, "/api/v1/public/rooms", PathItem.HttpMethod.GET).getSecurity()).isEmpty();
        assertThat(operation(openApi, "/api/v1/pi/requests/{requestId}", PathItem.HttpMethod.GET)
                .getSecurity().getFirst()).containsKey(OpenApiConfig.DEVICE_BEARER_SCHEME);
        assertThat(operation(openApi, "/api/v1/jobs/{id}", PathItem.HttpMethod.GET)
                .getSecurity().getFirst()).containsKey(OpenApiConfig.USER_BEARER_SCHEME);
    }

    @Test
    void publishesPhase9TagsAndApplicableReusableErrors() {
        OpenAPI openApi = new OpenAPI()
                .components(new Components())
                .paths(new Paths()
                        .addPathItem("/api/v1/delivery-requests/{id}/confirm-pickup",
                                item(PathItem.HttpMethod.POST))
                        .addPathItem("/api/v1/alerts", item(PathItem.HttpMethod.GET))
                        .addPathItem("/api/v1/emergency-incidents", item(PathItem.HttpMethod.GET)));

        config.phase9ContractCustomizer().customise(openApi);

        Operation legacy = operation(openApi,
                "/api/v1/delivery-requests/{id}/confirm-pickup", PathItem.HttpMethod.POST);
        assertThat(legacy.getTags()).containsExactly("Staff Delivery");
        assertThat(legacy.getResponses()).containsKeys("200", "400", "401", "403", "404", "409", "410", "422");
        assertThat(legacy.getResponses().get("410").get$ref())
                .isEqualTo("#/components/responses/LegacyExecutionRetired");
        assertThat(operation(openApi, "/api/v1/alerts", PathItem.HttpMethod.GET).getTags())
                .containsExactly("Staff Alerts");
        assertThat(operation(openApi, "/api/v1/emergency-incidents", PathItem.HttpMethod.GET).getTags())
                .containsExactly("Staff Incidents");
        assertThat(openApi.getComponents().getResponses()).containsKeys(
                "ValidationError", "Unauthorized", "Forbidden", "ResourceNotFound", "Conflict",
                "LegacyExecutionRetired", "BusinessRuleViolation");
        assertThat(openApi.getComponents().getSchemas()).containsKey("ApiErrorResponse");
        assertThat(openApi.getComponents().getSchemas().get("EndpointType").getEnum())
                .isEqualTo(List.of("WAYPOINT", "CHARGER", "PARK", "STAGING"));
        assertThat(openApi.getComponents().getSchemas().get("ZoneType").getEnum())
                .isEqualTo(List.of("KEEP_OUT", "SPEED", "NO_STOP"));

        JsonNode document = Json.mapper().valueToTree(openApi);
        assertThat(document.at("/components/schemas/EndpointType/type").asText()).isEqualTo("string");
        assertThat(document.at("/components/schemas/EndpointType/enum"))
                .isEqualTo(Json.mapper().valueToTree(List.of("WAYPOINT", "CHARGER", "PARK", "STAGING")));
        assertThat(document.at("/components/schemas/ZoneType/type").asText()).isEqualTo("string");
        assertThat(document.at("/components/schemas/ZoneType/enum"))
                .isEqualTo(Json.mapper().valueToTree(List.of("KEEP_OUT", "SPEED", "NO_STOP")));
    }

    private PathItem item(PathItem.HttpMethod method) {
        Operation operation = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("OK")));
        PathItem item = new PathItem();
        item.operation(method, operation);
        return item;
    }

    private Operation operation(OpenAPI openApi, String path, PathItem.HttpMethod method) {
        Map<PathItem.HttpMethod, Operation> operations = openApi.getPaths().get(path).readOperationsMap();
        return operations.get(method);
    }
}
