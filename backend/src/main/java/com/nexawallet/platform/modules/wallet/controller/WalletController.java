package com.nexawallet.platform.modules.wallet.controller;

import com.nexawallet.platform.common.security.CustomUserDetails;
import com.nexawallet.platform.modules.wallet.dto.DepositRequest;
import com.nexawallet.platform.modules.wallet.dto.TransactionResponse;
import com.nexawallet.platform.modules.wallet.dto.WalletResponse;
import com.nexawallet.platform.modules.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing wallet management endpoints.
 * All endpoints require a valid Bearer JWT token.
 */
@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Wallet", description = "Wallet balance, deposit, and transaction history APIs")
@SecurityRequirement(name = "bearerAuth")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * GET /api/v1/wallet/me
     * Returns the authenticated user's wallet summary and current balance.
     */
    @GetMapping("/me")
    @Operation(summary = "Get my wallet", description = "Returns the authenticated user's wallet balance and status")
    public ResponseEntity<WalletResponse> getMyWallet(
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        WalletResponse wallet = walletService.getMyWallet(principal.getUserId());
        return ResponseEntity.ok(wallet);
    }

    /**
     * POST /api/v1/wallet/deposit
     * Simulates an external deposit into the authenticated user's wallet.
     */
    @PostMapping("/deposit")
    @Operation(summary = "Simulate deposit", description = "Adds funds to the authenticated user's wallet (demo/simulation only)")
    public ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody DepositRequest request
    ) {
        TransactionResponse response = walletService.deposit(principal.getUserId(), request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/wallet/transactions
     * Returns paginated transaction history for the authenticated user.
     */
    @GetMapping("/transactions")
    @Operation(summary = "Get transaction history", description = "Returns paginated transaction history for the authenticated user's wallet")
    public ResponseEntity<Page<TransactionResponse>> getTransactionHistory(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<TransactionResponse> history = walletService.getTransactionHistory(principal.getUserId(), pageable);
        return ResponseEntity.ok(history);
    }
}
