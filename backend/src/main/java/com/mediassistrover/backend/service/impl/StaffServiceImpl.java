package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.dto.account.request.StaffRequests;
import com.mediassistrover.backend.dto.account.response.StaffResponse;
import com.mediassistrover.backend.entity.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.mapper.StaffMapper;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class StaffServiceImpl implements StaffService {
    private final AccountRepository accounts;
    private final StaffProfileRepository profiles;
    private final PasswordEncoder encoder;
    private final StaffMapper mapper;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final Clock clock;

    @Override @Transactional
    public StaffResponse create(StaffRequests.Create r, UUID actorId, RequestMetadata md) {
        String username=r.username().trim().toLowerCase(Locale.ROOT);
        String email=r.email().trim().toLowerCase(Locale.ROOT);
        String phone=phone(r.phone());
        if(accounts.existsByUsernameIgnoreCase(username)) throw new ConflictException("Username already exists");
        unique(null,email,phone,r.staffCode().trim());
        Account account=accounts.save(Account.builder().accountCode("STF-"+UUID.randomUUID())
                .username(username).fullName(r.fullName().trim()).email(email).phone(phone)
                .role(Role.STAFF).authProvider(AuthProvider.LOCAL).status(AccountStatus.ACTIVE)
                .passwordHash(encoder.encode(r.temporaryPassword())).mustChangePassword(true)
                .passwordChangedAt(Instant.now(clock)).build());
        StaffProfile profile=profiles.save(StaffProfile.builder().account(account).staffCode(r.staffCode().trim())
                .departmentCode(r.departmentCode()).jobTitle(r.jobTitle()).contactExtension(r.contactExtension())
                .active(true).build());
        record(actorId,"STAFF_CREATED",profile,md);
        return mapper.toResponse(profile);
    }
    @Override @Transactional
    public StaffResponse update(UUID id, StaffRequests.Update r, UUID actorId, RequestMetadata md) {
        StaffProfile profile=require(id); Account account=profile.getAccount();
        String email=r.email().trim().toLowerCase(Locale.ROOT), phone=phone(r.phone());
        unique(profile,email,phone,r.staffCode().trim());
        account.setFullName(r.fullName().trim());account.setEmail(email);account.setPhone(phone);
        profile.setStaffCode(r.staffCode().trim());profile.setDepartmentCode(r.departmentCode());
        profile.setJobTitle(r.jobTitle());profile.setContactExtension(r.contactExtension());
        record(actorId,"STAFF_UPDATED",profile,md);return mapper.toResponse(profile);
    }
    @Override public StaffResponse get(UUID id){return mapper.toResponse(require(id));}
    @Override public Page<StaffResponse> list(Pageable p){return profiles.findAll(p).map(mapper::toResponse);}
    @Override @Transactional
    public void deactivate(UUID id,UUID actorId,RequestMetadata md){
        StaffProfile profile=require(id);Account account=profile.getAccount();
        if(!profile.isActive() && account.getStatus()==AccountStatus.INACTIVE)return;
        profile.setActive(false);account.setStatus(AccountStatus.INACTIVE);
        account.setTokenVersion(account.getTokenVersion()+1);
        refreshTokens.revokeAll(account,RevocationReason.ACCOUNT_LOCKED);
        record(actorId,"STAFF_DEACTIVATED",profile,md);
    }
    private void unique(StaffProfile current,String email,String phone,String code){
        Account account=current==null?null:current.getAccount();
        if((account==null || !email.equalsIgnoreCase(account.getEmail())) && accounts.existsByEmailIgnoreCase(email))throw new ConflictException("Email already exists");
        if(phone!=null && (account==null || !phone.equals(account.getPhone())) && accounts.existsByPhone(phone))throw new ConflictException("Phone already exists");
        if((current==null || !code.equalsIgnoreCase(current.getStaffCode())) && profiles.existsByStaffCodeIgnoreCase(code))throw new ConflictException("Staff code already exists");
    }
    private String phone(String value){return value==null||value.isBlank()?null:value.trim().replaceAll("[ .-]","");}
    private StaffProfile require(UUID id){return profiles.findById(id).orElseThrow(()->new ResourceNotFoundException("Staff not found"));}
    private void record(UUID actorId,String action,StaffProfile profile,RequestMetadata md){
        Account actor=accounts.findById(actorId).orElseThrow(()->new ResourceNotFoundException("Account not found"));
        audit.record(actor,action,"StaffProfile",profile.getStaffProfileId(),null,Map.of("accountId",profile.getAccount().getAccountId(),"active",profile.isActive()),md);
    }
}
