package com.mediassistrover.backend.mapper;

import com.mediassistrover.backend.dto.account.response.AccountResponse;
import com.mediassistrover.backend.entity.Account;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AccountMapper {
    @Mapping(target = "role", source = "role")
    AccountResponse toResponse(Account account);
}
