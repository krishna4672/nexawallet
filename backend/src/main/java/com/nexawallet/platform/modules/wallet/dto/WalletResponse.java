package com.nexawallet.platform.modules.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * API response object representing a user's wallet state.
 * Never includes internal IDs or sensitive fields.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WalletResponse {

    private UUID id;
    private String walletNumber;
    private BigDecimal balance;
    private String currency;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
