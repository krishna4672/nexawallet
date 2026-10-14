package com.nexawallet.platform.modules.wallet.service;

import com.nexawallet.platform.modules.wallet.dto.TransactionResponse;
import com.nexawallet.platform.modules.wallet.dto.TransferRequest;

import java.util.UUID;

/**
 * Contract for peer-to-peer money transfer operations.
 */
public interface TransferService {

    /**
     * Initiates and settles a peer-to-peer transfer from the authenticated user's
     * wallet to the specified target wallet number.
     *
     * @param senderId        UUID of the authenticated sender
     * @param idempotencyKey  Client-supplied idempotency key (UUID string)
     * @param request         Transfer request body
     * @return Settled transaction response
     */
    TransactionResponse transfer(UUID senderId, String idempotencyKey, TransferRequest request);
}
