package com.mediassistrover.backend.dto.shift.response;

import com.mediassistrover.backend.enums.ShiftStatus;
import com.mediassistrover.backend.enums.SlotStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ShiftResponses {
    private ShiftResponses() { }

    public record SlotResponse(
            UUID slotId,
            int sequenceNo,
            Instant startsAt,
            Instant endsAt,
            SlotStatus status,
            String note,
            Instant createdAt
    ) { }

    public record ShiftResponse(
            UUID shiftId,
            UUID staffProfileId,
            String staffCode,
            UUID roomId,
            String roomCode,
            String shiftCode,
            String shiftName,
            Instant startsAt,
            Instant endsAt,
            ShiftStatus status,
            String note,
            List<SlotResponse> slots,
            Instant createdAt,
            Instant updatedAt
    ) { }
}
