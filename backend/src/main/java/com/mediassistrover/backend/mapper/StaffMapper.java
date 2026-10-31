package com.mediassistrover.backend.mapper;
import com.mediassistrover.backend.dto.account.response.StaffResponse;
import com.mediassistrover.backend.entity.StaffProfile;
import org.mapstruct.Mapper;
@Mapper(componentModel="spring", uses=AccountMapper.class)
public interface StaffMapper { StaffResponse toResponse(StaffProfile profile); }
