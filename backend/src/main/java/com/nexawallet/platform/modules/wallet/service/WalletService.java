package com.nexawallet.platform.modules.wallet.service;

import com.nexawallet.platform.modules.wallet.dto.DepositRequest;
import com.nexawallet.platform.modules.wallet.dto.TransactionResponse;
import com.nexawallet.platform.modules.wallet.dto.WalletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Contract for wallet management operations.
 */
public interface WalletService {

    /**
     * Returns the primary wallet summary for the authenticated user.
     */
    WalletResponse getMyWallet(UUID userId);

    /**
     * Simulates an external deposit into the user's wallet.
     * Records a double-entry ledger posting (System Settlement → User Wallet).
     */
    TransactionResponse deposit(UUID userId, DepositRequest request);

    /**
     * Returns paginated transaction history for the authenticated user's wallet.
     */
    Page<TransactionResponse> getTransactionHistory(UUID userId, Pageable pageable);
}
