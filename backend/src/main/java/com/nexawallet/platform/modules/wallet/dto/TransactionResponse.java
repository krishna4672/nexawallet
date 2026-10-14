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
 * API response object for a completed transaction.
 * Returned from both transfer and deposit endpoints.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionResponse {

    private UUID transactionId;
    private String referenceCode;
    private String type;
    private String status;
    private BigDecimal amount;
    private String currency;
    private String sourceWalletNumber;
    private String targetWalletNumber;
    private String description;
    private OffsetDateTime createdAt;
}
