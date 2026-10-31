package com.mediassistrover.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.impl.RequestJobInputSnapshotFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestJobInputSnapshotTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DeliveryRequestRepository deliveries = mock(DeliveryRequestRepository.class);
    private final DeliveryItemRepository items = mock(DeliveryItemRepository.class);
    private final NavigationRequestRepository navigations = mock(NavigationRequestRepository.class);
    private final RequestJobInputSnapshotFactory factory = new RequestJobInputSnapshotFactory(json, deliveries, items,
            navigations, mock(EscortRequestRepository.class));

    @Test void snapshotsPersistedGoodsAndCanonicalIdentityDespiteForgedClientData() throws Exception {
        UUID requestId = UUID.randomUUID(), mapId = UUID.randomUUID(), endpointId = UUID.randomUUID();
        var client = json.readTree("{\"service_request_id\":\"forged\",\"map_id\":\"forged\",\"compartment_id\":\"OTHER\",\"items\":[{\"item_code\":\"FAKE\",\"quantity\":99}],\"data\":{\"authorization\":{\"verified\":true},\"verified\":true,\"message\":\"hello\"}}");
        ServiceRequest request = ServiceRequest.builder().serviceRequestId(requestId).requestType(ServiceRequestType.DELIVERY).requestDataJson(client).build();
        FacilityMap map = FacilityMap.builder().mapId(mapId).build();
        Endpoint endpoint = Endpoint.builder().endpointId(endpointId).map(map).build();
        DeliveryRequest d = DeliveryRequest.builder().serviceRequestId(requestId).map(map).pickupEndpoint(endpoint).deliveryEndpoint(endpoint).targetMode(TargetMode.ENDPOINT).build();
        DeliveryItem item = DeliveryItem.builder().deliveryItemId(UUID.randomUUID()).itemCode("KIT").itemName("Medical kit")
                .itemCategory("MEDICAL").unit("box").quantity(2).sensitive(true).handlingInstruction("Keep cool").build();
        when(deliveries.findById(requestId)).thenReturn(Optional.of(d));
        when(items.findByDeliveryRequestServiceRequestIdOrderByItemCodeAsc(requestId)).thenReturn(List.of(item));
        Workflow workflow = Workflow.builder().defaultInputsJson(json.createObjectNode()).build();
        var snapshot = factory.forRequest(workflow, request);
        assertThat(snapshot.path("service_request_id").asText()).isEqualTo(requestId.toString());
        assertThat(snapshot.path("map_id").asText()).isEqualTo(mapId.toString());
        assertThat(snapshot.path("compartment_id").asText()).isEqualTo("CART_MAIN");
        assertThat(snapshot.path("items").get(0).path("item_code").asText()).isEqualTo("KIT");
        assertThat(snapshot.path("delivery_items").get(0).path("handling_instruction").asText()).isEqualTo("Keep cool");
        assertThat(snapshot.path("data").has("authorization")).isFalse();
        assertThat(snapshot.path("data").has("verified")).isFalse();
        assertThat(snapshot.path("data").path("message").asText()).isEqualTo("hello");
        item.setQuantity(5);
        ((com.fasterxml.jackson.databind.node.ObjectNode) client.path("data")).put("message", "changed");
        assertThat(snapshot.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(snapshot.path("data").path("message").asText()).isEqualTo("hello");
    }

    @Test void navigationSnapshotUsesPersistedOriginAndDestinationInsteadOfClientIdentity() throws Exception {
        UUID requestId=UUID.randomUUID(),mapId=UUID.randomUUID(),workflowId=UUID.randomUUID(),originId=UUID.randomUUID(),destinationId=UUID.randomUUID();
        ObjectNode client=(ObjectNode)json.readTree("{\"map_id\":\"forged\",\"workflow_id\":\"forged\",\"origin_endpoint_id\":\"forged\",\"endpoint_id\":\"forged\",\"endpoint_group_id\":\"forged\",\"message\":\"keep\"}");
        ServiceRequest request=ServiceRequest.builder().serviceRequestId(requestId).requestType(ServiceRequestType.NAVIGATION).requestDataJson(client).build();
        FacilityMap map=FacilityMap.builder().mapId(mapId).build();
        Endpoint origin=Endpoint.builder().endpointId(originId).map(map).build();
        Endpoint destination=Endpoint.builder().endpointId(destinationId).map(map).build();
        Workflow workflow=Workflow.builder().workflowId(workflowId).defaultInputsJson(json.createObjectNode()).build();
        NavigationRequest navigation=NavigationRequest.builder().serviceRequest(request).map(map).workflow(workflow).originEndpoint(origin)
                .destinationEndpoint(destination).targetMode(TargetMode.ENDPOINT).build();
        when(navigations.findById(requestId)).thenReturn(Optional.of(navigation));

        ObjectNode snapshot=factory.forRequest(workflow,request);

        assertThat(snapshot.path("map_id").asText()).isEqualTo(mapId.toString());
        assertThat(snapshot.path("workflow_id").asText()).isEqualTo(workflowId.toString());
        assertThat(snapshot.path("origin_endpoint_id").asText()).isEqualTo(originId.toString());
        assertThat(snapshot.path("endpoint_id").asText()).isEqualTo(destinationId.toString());
        assertThat(snapshot.has("endpoint_group_id")).isFalse();
        assertThat(snapshot.path("message").asText()).isEqualTo("keep");
    }
}
