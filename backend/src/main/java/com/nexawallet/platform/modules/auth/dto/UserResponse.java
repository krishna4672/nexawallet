package com.nexawallet.platform.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public user profile representation.
 * Explicitly excludes password hashes and internal database implementation details.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    @Schema(description = "User unique identifier")
    private UUID id;

    @Schema(description = "User email address", example = "alex.hunter@example.com")
    private String email;

    @Schema(description = "First name", example = "Alex")
    private String firstName;

    @Schema(description = "Last name", example = "Hunter")
    private String lastName;

    @Schema(description = "Assigned authorization role", example = "ROLE_CUSTOMER")
    private String role;

    @Schema(description = "Assigned primary wallet number", example = "NX-8041-9923-4102")
    private String walletNumber;

    @Schema(description = "Current wallet balance", example = "0.0000")
    private BigDecimal walletBalance;

    @Schema(description = "Wallet currency code", example = "USD")
    private String currency;

    @Schema(description = "Account registration timestamp")
    private OffsetDateTime createdAt;
}
