package com.mediassistrover.backend.dto.account.response;
import java.util.UUID;
public record StaffResponse(UUID staffProfileId, AccountResponse account, String staffCode,
                            String departmentCode, String jobTitle, String contactExtension, boolean active) {}
