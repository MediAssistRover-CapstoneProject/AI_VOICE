package com.mediassistrover.backend.repository;

import com.mediassistrover.backend.entity.StaffProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {
    Optional<StaffProfile> findByAccountAccountId(UUID accountId);
    boolean existsByStaffCode(String staffCode);
    boolean existsByStaffCodeIgnoreCase(String staffCode);
}
