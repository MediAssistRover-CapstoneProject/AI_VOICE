package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.ServiceRequestType;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Called inside Job creation's transaction. Client data is never an identity or verification source. */
@Component
@RequiredArgsConstructor
public class RequestJobInputSnapshotFactory {
    private final ObjectMapper json;
    private final DeliveryRequestRepository deliveries;
    private final DeliveryItemRepository items;
    private final NavigationRequestRepository navigations;
    private final EscortRequestRepository escorts;
    private static final Set<String> UNTRUSTED_AUTHORITY = Set.of("authorization", "verification_id",
            "actor_account_id", "actor_user_id", "verified", "valid_until", "verified_at", "permissions",
            "required_role", "actor", "purpose", "sender_account_id", "receiver_account_id",
            "job_id", "job_step_id", "operation_key", "command_id", "context", "vars", "outputs");

    public ObjectNode forRequest(Workflow workflow, ServiceRequest request) {
        ObjectNode result = merged(workflow.getDefaultInputsJson(), request.getRequestDataJson());
        result.put("service_request_id", request.getServiceRequestId().toString());
        result.remove("requested_by_account_id");
        if (request.getRequestedByAccount() != null)
            result.put("requested_by_account_id", request.getRequestedByAccount().getAccountId().toString());
        if (request.getRequestType() == ServiceRequestType.DELIVERY) delivery(result, request);
        else if (request.getRequestType() == ServiceRequestType.NAVIGATION) {
            NavigationRequest n = navigations.findById(request.getServiceRequestId()).orElseThrow(() -> new BusinessRuleException("Navigation request detail is missing"));
            result.put("map_id", n.getMap().getMapId().toString()).put("target_mode", n.getTargetMode().name());
            result.remove(Set.of("workflow_id", "endpoint_id", "endpoint_group_id", "origin_endpoint_id"));
            if (n.getWorkflow() != null) result.put("workflow_id", n.getWorkflow().getWorkflowId().toString());
            if (n.getOriginEndpoint() != null) result.put("origin_endpoint_id", n.getOriginEndpoint().getEndpointId().toString());
            if (n.getDestinationEndpoint() != null) result.put("endpoint_id", n.getDestinationEndpoint().getEndpointId().toString());
            if (n.getDestinationGroup() != null) result.put("endpoint_group_id", n.getDestinationGroup().getEndpointGroupId().toString());
        } else if (request.getRequestType() == ServiceRequestType.ESCORT) {
            EscortRequest e = escorts.findById(request.getServiceRequestId()).orElseThrow(() -> new BusinessRuleException("Escort request detail is missing"));
            result.put("map_id", e.getMap().getMapId().toString()).put("target_mode", e.getTargetMode().name());
            result.put("patient_reference",e.getPatientReference());
            result.remove(Set.of("endpoint_id", "endpoint_group_id", "origin_endpoint_id"));
            if (e.getOriginEndpoint() != null) result.put("origin_endpoint_id", e.getOriginEndpoint().getEndpointId().toString());
            if (e.getDestinationEndpoint() != null) result.put("endpoint_id", e.getDestinationEndpoint().getEndpointId().toString());
            if (e.getDestinationGroup() != null) result.put("endpoint_group_id", e.getDestinationGroup().getEndpointGroupId().toString());
        }
        return result;
    }

    public ObjectNode merged(JsonNode defaults, JsonNode clientData) {
        ObjectNode result = json.createObjectNode();
        if (defaults != null && defaults.isObject()) result.setAll((ObjectNode) defaults.deepCopy());
        if (clientData != null && !clientData.isObject()) throw new BusinessRuleException("Job input must be a JSON object");
        if (clientData != null) result.setAll((ObjectNode) sanitized(clientData));
        return result;
    }

    private void delivery(ObjectNode result, ServiceRequest request) {
        DeliveryRequest d = deliveries.findById(request.getServiceRequestId()).orElseThrow(() -> new BusinessRuleException("Delivery request detail is missing"));
        result.put("map_id", d.getMap().getMapId().toString())
                .put("pickup_endpoint_id", d.getPickupEndpoint().getEndpointId().toString())
                .put("target_mode", d.getTargetMode().name()).put("compartment_id", "CART_MAIN");
        result.remove(Set.of("delivery_endpoint_id", "delivery_group_id", "sender_account_id", "receiver_account_id"));
        if (d.getDeliveryEndpoint() != null) result.put("delivery_endpoint_id", d.getDeliveryEndpoint().getEndpointId().toString());
        if (d.getDeliveryGroup() != null) result.put("delivery_group_id", d.getDeliveryGroup().getEndpointGroupId().toString());
        if (d.getSenderAccount() != null) result.put("sender_account_id", d.getSenderAccount().getAccountId().toString());
        if (d.getReceiverAccount() != null) result.put("receiver_account_id", d.getReceiverAccount().getAccountId().toString());
        ArrayNode businessItems = result.putArray("items");
        ArrayNode details = result.putArray("delivery_items");
        for (DeliveryItem item : items.findByDeliveryRequestServiceRequestIdOrderByItemCodeAsc(request.getServiceRequestId())) {
            businessItems.addObject().put("item_code", item.getItemCode()).put("quantity", item.getQuantity());
            details.addObject().put("delivery_item_id", item.getDeliveryItemId().toString())
                    .put("item_code", item.getItemCode()).put("item_name", item.getItemName())
                    .put("item_category", item.getItemCategory()).put("quantity", item.getQuantity())
                    .put("unit", item.getUnit()).put("sensitive", item.isSensitive())
                    .put("handling_instruction", item.getHandlingInstruction()).put("item_note", item.getItemNote());
        }
        if (businessItems.isEmpty()) throw new BusinessRuleException("Delivery requires persisted items before Job creation");
    }

    private JsonNode sanitized(JsonNode n) {
        if (n.isObject()) {
            ObjectNode copy = json.createObjectNode();
            n.fields().forEachRemaining(e -> { if (!UNTRUSTED_AUTHORITY.contains(e.getKey())) copy.set(e.getKey(), sanitized(e.getValue())); });
            return copy;
        }
        if (n.isArray()) { ArrayNode copy = json.createArrayNode(); n.forEach(v -> copy.add(sanitized(v))); return copy; }
        return n.deepCopy();
    }
}
