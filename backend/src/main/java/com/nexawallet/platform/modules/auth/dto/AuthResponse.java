package com.nexawallet.platform.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Authentication response containing signed JWT access token and user profile summary.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    @Schema(description = "Signed JWT access token")
    private String accessToken;

    @Schema(description = "HTTP Authorization token type", example = "Bearer")
    @Builder.Default
    private String tokenType = "Bearer";

    @Schema(description = "Token validity duration in seconds", example = "900")
    private long expiresIn;

    @Schema(description = "User profile summary")
    private UserResponse user;
}
