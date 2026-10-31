package com.mediassistrover.backend.dto.shift.request;

import com.mediassistrover.backend.enums.ShiftStatus;
import com.mediassistrover.backend.enums.SlotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ShiftRequests {
    private ShiftRequests() { }

    public record SlotInput(
            @Min(1) int sequenceNo,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull SlotStatus status,
            @Size(max = 4000) String note
    ) { }

    @Schema(name="ShiftCreateRequest") public record Create(
            @NotNull UUID staffProfileId,
            @NotNull UUID roomId,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]*") String shiftCode,
            @NotBlank @Size(max = 100) String shiftName,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Size(max = 4000) String note,
            @Valid @NotNull List<SlotInput> slots
    ) { }

    @Schema(name="ShiftUpdateRequest") public record Update(
            @NotNull UUID staffProfileId,
            @NotNull UUID roomId,
            @NotBlank @Size(max = 100) String shiftName,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Size(max = 4000) String note,
            @Valid @NotNull List<SlotInput> slots
    ) { }

    public record Transition(@NotNull ShiftStatus status, @Size(max = 4000) String note) { }

    public record SlotCreate(
            @Min(1) int sequenceNo,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull SlotStatus status,
            @Size(max = 4000) String note
    ) { }

    public record SlotUpdate(
            @Min(1) int sequenceNo,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull SlotStatus status,
            @Size(max = 4000) String note
    ) { }

    public record SlotTransition(@NotNull SlotStatus status, @Size(max = 4000) String note) { }
}
