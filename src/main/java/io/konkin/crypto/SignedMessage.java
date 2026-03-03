package io.konkin.crypto;

public record SignedMessage(Coin coin, String address, String message, String signature) {
    public SignedMessage {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (address == null || address.isBlank()) throw new IllegalArgumentException("address must not be blank");
        if (message == null) throw new IllegalArgumentException("message must not be null");
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("signature must not be blank");
    }
}
