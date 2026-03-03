package io.konkin.crypto;

public final class WalletConnectionException extends WalletException {

    public WalletConnectionException(String message) {
        super(message);
    }

    public WalletConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
