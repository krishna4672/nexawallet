package com.nexawallet.platform.modules.wallet.controller;

import com.nexawallet.platform.common.exception.DuplicateTransferException;
import com.nexawallet.platform.common.security.CustomUserDetails;
import com.nexawallet.platform.modules.wallet.dto.TransactionResponse;
import com.nexawallet.platform.modules.wallet.dto.TransferRequest;
import com.nexawallet.platform.modules.wallet.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * REST controller for peer-to-peer money transfers.
 *
 * Idempotency:
 *   Clients MUST supply an {@code Idempotency-Key} HTTP header (UUID or any opaque string ≤ 128 chars).
 *   Repeating the same key returns the cached response from the first successful execution,
 *   making the operation safe to retry on network failures.
 */
@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Peer-to-peer money transfer APIs")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * POST /api/v1/transfers
     *
     * Initiates a peer-to-peer transfer.
     * Requires {@code Idempotency-Key} header.
     */
    @PostMapping
    @Operation(
            summary = "Initiate P2P transfer",
            description = "Transfers funds from the authenticated user's wallet to a target wallet number. " +
                          "Requires an 'Idempotency-Key' header to ensure exactly-once execution on retries."
    )
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) {
        try {
            TransactionResponse response = transferService.transfer(
                    principal.getUserId(),
                    idempotencyKey.trim(),
                    request
            );
            return ResponseEntity.ok(response);

        } catch (DuplicateTransferException ex) {
            // Return the previously cached successful response (HTTP 200, idempotent)
            log.debug("Idempotent transfer replay for key '{}'", idempotencyKey);
            try {
                TransactionResponse cached = MAPPER.readValue(ex.getCachedPayload(), TransactionResponse.class);
                return ResponseEntity.ok(cached);
            } catch (Exception parseEx) {
                log.error("Failed to parse cached transfer response for idempotency key '{}'", idempotencyKey, parseEx);
                return ResponseEntity.ok().build();
            }
        }
    }
}
