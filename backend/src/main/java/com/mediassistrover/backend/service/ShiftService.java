package com.mediassistrover.backend.service;

import com.mediassistrover.backend.dto.shift.request.ShiftRequests.*;
import com.mediassistrover.backend.dto.shift.response.ShiftResponses.*;
import com.mediassistrover.backend.enums.ShiftStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ShiftService {
    ShiftResponse create(Create request, UUID actorId, RequestMetadata metadata);
    ShiftResponse update(UUID shiftId, Update request, UUID actorId, RequestMetadata metadata);
    ShiftResponse transition(UUID shiftId, Transition request, UUID actorId, RequestMetadata metadata);
    ShiftResponse get(UUID shiftId);
    Page<ShiftResponse> list(UUID staffProfileId, UUID roomId, ShiftStatus status, Pageable pageable);
    Page<ShiftResponse> listOwn(UUID accountId, ShiftStatus status, Pageable pageable);
    ShiftResponse getOwn(UUID shiftId, UUID accountId);
    void delete(UUID shiftId, UUID actorId, RequestMetadata metadata);
    SlotResponse addSlot(UUID shiftId, SlotCreate request, UUID actorId, RequestMetadata metadata);
    SlotResponse updateSlot(UUID shiftId, UUID slotId, SlotUpdate request, UUID actorId, RequestMetadata metadata);
    SlotResponse transitionSlot(UUID shiftId, UUID slotId, SlotTransition request, UUID actorId, RequestMetadata metadata);
    void deleteSlot(UUID shiftId, UUID slotId, UUID actorId, RequestMetadata metadata);
}
