package com.mediassistrover.backend.config;

import com.mediassistrover.backend.enums.EndpointType;
import com.mediassistrover.backend.enums.ZoneType;
import com.mediassistrover.backend.exception.ApiErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
public class OpenApiConfig {

    public static final String USER_BEARER_SCHEME = "bearerAuth";
    public static final String DEVICE_BEARER_SCHEME = "deviceBearerAuth";

    private static final String PATIENT_PUBLIC_TAG = "Patient Public Catalog";
    private static final String ROBOT_REQUEST_TAG = "Robot Screen Requests";
    private static final String ROBOT_EMERGENCY_TAG = "Robot Emergency";
    private static final String STAFF_OPERATIONS_TAG = "Staff Operations";
    private static final String STAFF_DELIVERY_TAG = "Staff Delivery";
    private static final String STAFF_ALERTS_TAG = "Staff Alerts";
    private static final String STAFF_INCIDENTS_TAG = "Staff Incidents";

    private static final Set<PathItem.HttpMethod> MUTATING_METHODS = Set.of(
            PathItem.HttpMethod.POST,
            PathItem.HttpMethod.PUT,
            PathItem.HttpMethod.PATCH,
            PathItem.HttpMethod.DELETE
    );

    @Bean
    OpenAPI mediAssistRoverOpenApi() {
        Components components = new Components()
                .addSecuritySchemes(USER_BEARER_SCHEME, bearerScheme("JWT dành cho ADMIN và STAFF"))
                .addSecuritySchemes(DEVICE_BEARER_SCHEME, bearerScheme("Device JWT dành riêng cho Raspberry Pi"));

        return new OpenAPI()
                .info(new Info()
                        .title("MediAssistRover Backend API")
                        .version("v1")
                        .description("REST API quản lý bệnh viện, workflow, job và giao tiếp Server–Raspberry Pi."))
                .components(components);
    }

    /**
     * Makes the generated contract explicit instead of relying on a global bearer default. This is important for
     * clients because Patient catalog routes, Device routes and Staff routes use three different trust boundaries.
     */
    @Bean
    OpenApiCustomizer phase9ContractCustomizer() {
        return openApi -> {
            registerErrorContract(openApi);
            registerClosedEnumContracts(openApi);
            registerPatientStaffTags(openApi);
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap()
                    .forEach((method, operation) -> customizeOperation(path, method, operation)));
        };
    }

    private void customizeOperation(String path, PathItem.HttpMethod method, Operation operation) {
        if (isAnonymous(path)) {
            operation.setSecurity(List.of());
        } else if (path.equals("/api/v1/pi/speech/prepare")) {
            operation.setSecurity(List.of(new SecurityRequirement().addList(USER_BEARER_SCHEME)));
        } else if (path.equals("/api/v1/pi/speech/synthesize") || path.matches("/api/v1/pi/maps/\\{[^}]+}/bundle")) {
            operation.setSecurity(List.of(new SecurityRequirement().addList(DEVICE_BEARER_SCHEME),
                    new SecurityRequirement().addList(USER_BEARER_SCHEME)));
        } else if (isDevice(path)) {
            operation.setSecurity(List.of(new SecurityRequirement().addList(DEVICE_BEARER_SCHEME)));
        } else {
            operation.setSecurity(List.of(new SecurityRequirement().addList(USER_BEARER_SCHEME)));
        }

        String patientStaffTag = patientStaffTag(path);
        if (patientStaffTag == null) {
            return;
        }
        operation.setTags(List.of(patientStaffTag));
        ensureSuccessResponse(operation);
        addError(operation, "400", "ValidationError");
        addError(operation, "404", "ResourceNotFound");
        addError(operation, "422", "BusinessRuleViolation");
        if (!isAnonymous(path)) {
            addError(operation, "401", "Unauthorized");
            addError(operation, "403", "Forbidden");
        }
        if (MUTATING_METHODS.contains(method)) {
            addError(operation, "409", "Conflict");
        }
        if (isLegacyDeliveryConfirmation(path)) {
            addError(operation, "410", "LegacyExecutionRetired");
        }
    }

    private void ensureSuccessResponse(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        boolean hasSuccess = operation.getResponses().keySet().stream().anyMatch(code -> code.startsWith("2"));
        if (!hasSuccess) {
            operation.getResponses().addApiResponse("200", new ApiResponse().description("Successful response"));
        }
    }

    private void addError(Operation operation, String status, String componentName) {
        operation.getResponses().putIfAbsent(status,
                new ApiResponse().$ref("#/components/responses/" + componentName));
    }

    private boolean isAnonymous(String path) {
        return path.startsWith("/api/v1/public/")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout")
                || path.equals("/api/v1/pi/auth/token")
                || path.equals("/actuator/health");
    }

    private boolean isDevice(String path) {
        return path.startsWith("/api/v1/pi/") && !path.equals("/api/v1/pi/auth/token");
    }

    private boolean isLegacyDeliveryConfirmation(String path) {
        return path.matches("/api/v1/delivery-requests/\\{[^}]+}/confirm-(pickup|delivery)");
    }

    private String patientStaffTag(String path) {
        if (path.startsWith("/api/v1/public/")) {
            return PATIENT_PUBLIC_TAG;
        }
        if (path.startsWith("/api/v1/pi/requests")) {
            return ROBOT_REQUEST_TAG;
        }
        if (path.startsWith("/api/v1/pi/incidents")) {
            return ROBOT_EMERGENCY_TAG;
        }
        if (isStaffDeliveryPath(path)) {
            return STAFF_DELIVERY_TAG;
        }
        if (path.startsWith("/api/v1/alerts")) {
            return STAFF_ALERTS_TAG;
        }
        if (path.startsWith("/api/v1/emergency-incidents")) {
            return STAFF_INCIDENTS_TAG;
        }
        return isStaffOperationsPath(path) ? STAFF_OPERATIONS_TAG : null;
    }

    private boolean isStaffDeliveryPath(String path) {
        return path.startsWith("/api/v1/delivery-requests")
                || path.startsWith("/api/v1/auth-verifications")
                || path.matches("/api/v1/jobs/\\{[^}]+}/(auth-verifications|qr-challenges|qr-verifications)")
                || path.matches("/api/v1/job-steps/\\{[^}]+}/responses");
    }

    private boolean isStaffOperationsPath(String path) {
        return List.of(
                "/api/v1/staff/",
                "/api/v1/hospital-information",
                "/api/v1/floors",
                "/api/v1/maps",
                "/api/v1/zones",
                "/api/v1/rooms",
                "/api/v1/endpoints",
                "/api/v1/endpoint-groups",
                "/api/v1/voice/",
                "/api/v1/workflows",
                "/api/v1/information-requests",
                "/api/v1/navigation-requests",
                "/api/v1/escort-requests",
                "/api/v1/operational-requests",
                "/api/v1/service-requests",
                "/api/v1/jobs",
                "/api/v1/robots",
                "/api/v1/schedules"
        ).stream().anyMatch(path::startsWith);
    }

    private void registerPatientStaffTags(OpenAPI openApi) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(PATIENT_PUBLIC_TAG, "Anonymous, patient-safe hospital catalog");
        tags.put(ROBOT_REQUEST_TAG, "Patient requests submitted through an authenticated robot screen");
        tags.put(ROBOT_EMERGENCY_TAG, "Patient SOS and incident status through an authenticated robot");
        tags.put(STAFF_OPERATIONS_TAG, "Staff operational requests, jobs, schedules and read-only references");
        tags.put(STAFF_DELIVERY_TAG, "Delivery, QR verification and physical confirmation workflows");
        tags.put(STAFF_ALERTS_TAG, "Scoped Staff alert lifecycle");
        tags.put(STAFF_INCIDENTS_TAG, "Scoped Staff emergency incident lifecycle");
        tags.forEach((name, description) -> {
            if (openApi.getTags() == null || openApi.getTags().stream().noneMatch(tag -> name.equals(tag.getName()))) {
                openApi.addTagsItem(new Tag().name(name).description(description));
            }
        });
    }

    private void registerErrorContract(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        ModelConverters.getInstance().readAll(ApiErrorResponse.class)
                .forEach(components::addSchemas);
        components.addResponses("ValidationError", errorResponse("Request validation failed"));
        components.addResponses("Unauthorized", errorResponse("Missing, invalid or expired credential"));
        components.addResponses("Forbidden", errorResponse("Authenticated actor is outside the resource scope"));
        components.addResponses("ResourceNotFound", errorResponse("Resource does not exist"));
        components.addResponses("Conflict", errorResponse("Idempotency conflict or concurrent update"));
        components.addResponses("LegacyExecutionRetired", errorResponse("Legacy schema 2 execution is unavailable for this workflow"));
        components.addResponses("BusinessRuleViolation", errorResponse("Request violates a workflow or domain rule"));
    }

    private void registerClosedEnumContracts(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        components.addSchemas(EndpointType.class.getSimpleName(), enumSchema(
                EndpointType.values(), "OTTO endpoint type"));
        components.addSchemas(ZoneType.class.getSimpleName(), enumSchema(
                ZoneType.values(), "Robot navigation zone type"));
    }

    private Schema<String> enumSchema(Enum<?>[] values, String description) {
        return new StringSchema()
                .description(description)
                ._enum(java.util.Arrays.stream(values).map(Enum::name).toList());
    }

    private ApiResponse errorResponse(String description) {
        MediaType mediaType = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiErrorResponse"));
        return new ApiResponse().description(description)
                .content(new Content().addMediaType("application/json", mediaType));
    }

    private SecurityScheme bearerScheme(String description) {
        return new SecurityScheme()
                .name("Authorization")
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description(description);
    }
}
