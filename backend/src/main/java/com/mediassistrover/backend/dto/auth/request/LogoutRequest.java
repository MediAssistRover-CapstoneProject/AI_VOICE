package com.mediassistrover.backend.dto.auth.request;

import jakarta.validation.constraints.Size;

public record LogoutRequest(@Size(max = 500) String refreshToken) {
}
