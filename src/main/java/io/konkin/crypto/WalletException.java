package io.konkin.crypto;

public sealed class WalletException extends RuntimeException
        permits WalletConnectionException, WalletOperationException {

    public WalletException(String message) {
        super(message);
    }

    public WalletException(String message, Throwable cause) {
        super(message, cause);
    }
}
