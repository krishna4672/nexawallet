package com.nexawallet.platform.common.exception;

/**
 * Thrown when a financial operation is attempted on a FROZEN or CLOSED wallet.
 */
public class WalletFrozenException extends RuntimeException {

    public WalletFrozenException(String walletNumber) {
        super("Wallet '" + walletNumber + "' is not ACTIVE and cannot accept transactions.");
    }
}
