package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.dto.shift.request.ShiftRequests.*;
import com.mediassistrover.backend.dto.shift.response.ShiftResponses.*;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mapper.ShiftMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftServiceImpl implements ShiftService {
    private final ShiftRepository shiftRepository;
    private final SlotRepository slotRepository;
    private final StaffProfileRepository staffRepository;
    private final RoomRepository roomRepository;
    private final AccountRepository accountRepository;
    private final ShiftMapper mapper;
    private final AuditService auditService;

    @Override
    @Transactional
    public ShiftResponse create(Create request, UUID actorId, RequestMetadata metadata) {
        if (shiftRepository.existsByShiftCodeIgnoreCase(request.shiftCode())) {
            throw new ConflictException("Shift code already exists");
        }
        StaffProfile staff = activeStaff(request.staffProfileId());
        Room room = activeRoom(request.roomId());
        validateShiftPeriod(request.startsAt(), request.endsAt());
        validateNoShiftOverlap(staff.getStaffProfileId(), request.startsAt(), request.endsAt(), null);
        validateSlots(request.slots(), request.startsAt(), request.endsAt());

        Shift shift = mapper.toEntity(request);
        shift.setShiftCode(request.shiftCode().trim().toUpperCase(Locale.ROOT));
        shift.setShiftName(request.shiftName().trim());
        shift.setStaffProfile(staff);
        shift.setRoom(room);
        request.slots().forEach(input -> shift.addSlot(mapper.toEntity(input)));
        shiftRepository.saveAndFlush(shift);
        Account actor = account(actorId);
        auditService.record(actor, "SHIFT_CREATED", "Shift", shift.getShiftId(), null, auditData(shift), metadata);
        return mapper.toResponse(shift);
    }

    @Override
    @Transactional
    public ShiftResponse update(UUID shiftId, Update request, UUID actorId, RequestMetadata metadata) {
        Shift shift = shift(shiftId);
        requirePlanned(shift);
        StaffProfile staff = activeStaff(request.staffProfileId());
        Room room = activeRoom(request.roomId());
        validateShiftPeriod(request.startsAt(), request.endsAt());
        validateNoShiftOverlap(staff.getStaffProfileId(), request.startsAt(), request.endsAt(), shiftId);
        validateSlots(request.slots(), request.startsAt(), request.endsAt());
        Map<String, Object> before = auditData(shift);

        shift.setStaffProfile(staff);
        shift.setRoom(room);
        shift.setShiftName(request.shiftName().trim());
        shift.setStartsAt(request.startsAt());
        shift.setEndsAt(request.endsAt());
        shift.setNote(request.note());
        shift.getSlots().clear();
        shiftRepository.flush();
        request.slots().forEach(input -> shift.addSlot(mapper.toEntity(input)));
        shiftRepository.saveAndFlush(shift);
        Account actor = account(actorId);
        auditService.record(actor, "SHIFT_UPDATED", "Shift", shiftId, before, auditData(shift), metadata);
        return mapper.toResponse(shift);
    }

    @Override
    @Transactional
    public ShiftResponse transition(UUID shiftId, Transition request, UUID actorId, RequestMetadata metadata) {
        Shift shift = shift(shiftId);
        ShiftStatus current = shift.getStatus();
        if (current == request.status()) return mapper.toResponse(shift);
        boolean allowed = switch (current) {
            case PLANNED -> request.status() == ShiftStatus.ACTIVE || request.status() == ShiftStatus.CANCELLED;
            case ACTIVE -> request.status() == ShiftStatus.COMPLETED || request.status() == ShiftStatus.CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
        if (!allowed) throw new BusinessRuleException("Invalid Shift transition: " + current + " -> " + request.status());
        Map<String, Object> before = auditData(shift);
        shift.setStatus(request.status());
        if (request.note() != null) shift.setNote(request.note());
        Account actor = account(actorId);
        auditService.record(actor, "SHIFT_STATUS_CHANGED", "Shift", shiftId, before, auditData(shift), metadata);
        return mapper.toResponse(shift);
    }

    @Override public ShiftResponse get(UUID shiftId) { return mapper.toResponse(shift(shiftId)); }

    @Override
    public Page<ShiftResponse> list(UUID staffProfileId, UUID roomId, ShiftStatus status, Pageable pageable) {
        return shiftRepository.search(staffProfileId, roomId, status, pageable).map(mapper::toResponse);
    }

    @Override
    public Page<ShiftResponse> listOwn(UUID accountId, ShiftStatus status, Pageable pageable) {
        return shiftRepository.findOwn(accountId, status, pageable).map(mapper::toResponse);
    }

    @Override
    public ShiftResponse getOwn(UUID shiftId, UUID accountId) {
        Shift shift = shift(shiftId);
        if (!shift.getStaffProfile().getAccount().getAccountId().equals(accountId)) {
            throw new AccessDeniedException("Shift belongs to another Staff account");
        }
        return mapper.toResponse(shift);
    }

    @Override
    @Transactional
    public void delete(UUID shiftId, UUID actorId, RequestMetadata metadata) {
        Shift shift = shift(shiftId);
        requirePlanned(shift);
        Account actor = account(actorId);
        Map<String, Object> before = auditData(shift);
        shiftRepository.delete(shift);
        auditService.record(actor, "SHIFT_DELETED", "Shift", shiftId, before, null, metadata);
    }

    @Override
    @Transactional
    public SlotResponse addSlot(UUID shiftId, SlotCreate request, UUID actorId, RequestMetadata metadata) {
        Shift shift = shift(shiftId);
        requirePlanned(shift);
        validateSlot(request.sequenceNo(), request.startsAt(), request.endsAt(), shift, null);
        Slot slot = mapper.toEntity(request);
        slot.setShift(shift);
        slotRepository.saveAndFlush(slot);
        auditService.record(account(actorId), "SLOT_CREATED", "Slot", slot.getSlotId(), null, slotAuditData(slot), metadata);
        return mapper.toResponse(slot);
    }

    @Override
    @Transactional
    public SlotResponse updateSlot(UUID shiftId, UUID slotId, SlotUpdate request, UUID actorId, RequestMetadata metadata) {
        Shift shift = shift(shiftId);
        requirePlanned(shift);
        Slot slot = slot(shiftId, slotId);
        validateSlot(request.sequenceNo(), request.startsAt(), request.endsAt(), shift, slotId);
        Map<String, Object> before = slotAuditData(slot);
        slot.setSequenceNo(request.sequenceNo());
        slot.setStartsAt(request.startsAt());
        slot.setEndsAt(request.endsAt());
        slot.setStatus(request.status());
        slot.setNote(request.note());
        slotRepository.flush();
        auditService.record(account(actorId), "SLOT_UPDATED", "Slot", slotId, before, slotAuditData(slot), metadata);
        return mapper.toResponse(slot);
    }

    @Override
    @Transactional
    public SlotResponse transitionSlot(UUID shiftId, UUID slotId, SlotTransition request, UUID actorId, RequestMetadata metadata) {
        Shift shift = shift(shiftId);
        if (shift.getStatus() == ShiftStatus.COMPLETED || shift.getStatus() == ShiftStatus.CANCELLED) {
            throw new BusinessRuleException("Slots of a terminal Shift cannot be changed");
        }
        Slot slot = slot(shiftId, slotId);
        SlotStatus current = slot.getStatus();
        if (current == request.status()) return mapper.toResponse(slot);
        boolean allowed = current != SlotStatus.CLOSED && (request.status() == SlotStatus.AVAILABLE
                || request.status() == SlotStatus.BLOCKED || request.status() == SlotStatus.CLOSED);
        if (!allowed) throw new BusinessRuleException("Invalid Slot transition: " + current + " -> " + request.status());
        Map<String, Object> before = slotAuditData(slot);
        slot.setStatus(request.status());
        if (request.note() != null) slot.setNote(request.note());
        auditService.record(account(actorId), "SLOT_STATUS_CHANGED", "Slot", slotId, before, slotAuditData(slot), metadata);
        return mapper.toResponse(slot);
    }

    @Override
    @Transactional
    public void deleteSlot(UUID shiftId, UUID slotId, UUID actorId, RequestMetadata metadata) {
        Shift shift = shift(shiftId);
        requirePlanned(shift);
        Slot slot = slot(shiftId, slotId);
        Map<String, Object> before = slotAuditData(slot);
        slotRepository.delete(slot);
        auditService.record(account(actorId), "SLOT_DELETED", "Slot", slotId, before, null, metadata);
    }

    private void validateShiftPeriod(Instant startsAt, Instant endsAt) {
        if (!startsAt.isBefore(endsAt)) throw new BusinessRuleException("Shift startsAt must be before endsAt");
    }

    private void validateNoShiftOverlap(UUID staffId, Instant startsAt, Instant endsAt, UUID excludeId) {
        if (shiftRepository.overlaps(staffId, startsAt, endsAt, excludeId)) {
            throw new ConflictException("Staff already has an overlapping Shift");
        }
    }

    private void validateSlots(List<SlotInput> slots, Instant shiftStart, Instant shiftEnd) {
        Set<Integer> sequences = new HashSet<>();
        List<SlotInput> sorted = new ArrayList<>(slots);
        sorted.sort(Comparator.comparing(SlotInput::startsAt));
        Instant previousEnd = null;
        for (SlotInput slot : sorted) {
            if (!sequences.add(slot.sequenceNo())) throw new ConflictException("Duplicate Slot sequenceNo: " + slot.sequenceNo());
            if (!slot.startsAt().isBefore(slot.endsAt())) throw new BusinessRuleException("Slot startsAt must be before endsAt");
            if (slot.startsAt().isBefore(shiftStart) || slot.endsAt().isAfter(shiftEnd)) {
                throw new BusinessRuleException("Every Slot must be inside the Shift period");
            }
            if (previousEnd != null && slot.startsAt().isBefore(previousEnd)) {
                throw new ConflictException("Slots in the same Shift cannot overlap");
            }
            previousEnd = slot.endsAt();
        }
    }

    private void validateSlot(int sequence, Instant startsAt, Instant endsAt, Shift shift, UUID excludeId) {
        if (!startsAt.isBefore(endsAt)) throw new BusinessRuleException("Slot startsAt must be before endsAt");
        if (startsAt.isBefore(shift.getStartsAt()) || endsAt.isAfter(shift.getEndsAt())) {
            throw new BusinessRuleException("Slot must be inside the Shift period");
        }
        if (shift.getSlots().stream().anyMatch(s -> !s.getSlotId().equals(excludeId) && s.getSequenceNo() == sequence)) {
            throw new ConflictException("Slot sequenceNo already exists in Shift");
        }
        if (slotRepository.overlaps(shift.getShiftId(), startsAt, endsAt, excludeId)) {
            throw new ConflictException("Slots in the same Shift cannot overlap");
        }
    }

    private Shift shift(UUID id) {
        return shiftRepository.findDetailedById(id).orElseThrow(() -> new ResourceNotFoundException("Shift not found: " + id));
    }
    private Slot slot(UUID shiftId, UUID slotId) {
        return slotRepository.findBySlotIdAndShiftShiftId(slotId, shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Slot not found: " + slotId));
    }
    private StaffProfile activeStaff(UUID id) {
        StaffProfile staff = staffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("StaffProfile not found: " + id));
        if (!staff.isActive() || staff.getAccount().getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("Shift can only be assigned to active Staff");
        }
        return staff;
    }
    private Room activeRoom(UUID id) {
        Room room = roomRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Room not found: " + id));
        if (!room.isActive()) throw new BusinessRuleException("Shift can only use an active Room");
        return room;
    }
    private Account account(UUID id) {
        return accountRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Account not found: " + id));
    }
    private void requirePlanned(Shift shift) {
        if (shift.getStatus() != ShiftStatus.PLANNED) throw new BusinessRuleException("Only a PLANNED Shift can be edited or deleted");
    }
    private Map<String, Object> auditData(Shift shift) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shiftCode", shift.getShiftCode());
        data.put("staffProfileId", shift.getStaffProfile().getStaffProfileId());
        data.put("roomId", shift.getRoom().getRoomId());
        data.put("startsAt", shift.getStartsAt());
        data.put("endsAt", shift.getEndsAt());
        data.put("status", shift.getStatus().name());
        return data;
    }
    private Map<String, Object> slotAuditData(Slot slot) {
        return Map.of("shiftId", slot.getShift().getShiftId(), "sequenceNo", slot.getSequenceNo(),
                "startsAt", slot.getStartsAt(), "endsAt", slot.getEndsAt(), "status", slot.getStatus().name());
    }
}
