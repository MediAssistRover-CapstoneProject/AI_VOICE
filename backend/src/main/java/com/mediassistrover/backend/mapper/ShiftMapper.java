package com.mediassistrover.backend.mapper;

import com.mediassistrover.backend.dto.shift.request.ShiftRequests;
import com.mediassistrover.backend.dto.shift.response.ShiftResponses;
import com.mediassistrover.backend.entity.Shift;
import com.mediassistrover.backend.entity.Slot;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ShiftMapper {
    @Mapping(target = "staffProfileId", source = "staffProfile.staffProfileId")
    @Mapping(target = "staffCode", source = "staffProfile.staffCode")
    @Mapping(target = "roomId", source = "room.roomId")
    @Mapping(target = "roomCode", source = "room.roomCode")
    ShiftResponses.ShiftResponse toResponse(Shift entity);

    ShiftResponses.SlotResponse toResponse(Slot entity);

    @Mapping(target = "shiftId", ignore = true)
    @Mapping(target = "staffProfile", ignore = true)
    @Mapping(target = "room", ignore = true)
    @Mapping(target = "status", constant = "PLANNED")
    @Mapping(target = "slots", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Shift toEntity(ShiftRequests.Create request);

    @Mapping(target = "slotId", ignore = true)
    @Mapping(target = "shift", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Slot toEntity(ShiftRequests.SlotInput request);

    @Mapping(target = "slotId", ignore = true)
    @Mapping(target = "shift", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Slot toEntity(ShiftRequests.SlotCreate request);
}
