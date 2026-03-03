package io.konkin.crypto;

public sealed class WalletOperationException extends WalletException
        permits WalletInsufficientFundsException {

    public WalletOperationException(String message) {
        super(message);
    }

    public WalletOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
