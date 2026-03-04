/*
 * Copyright 2026 Peter Geschel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.konkin.crypto;

public record SignedMessage(Coin coin, String address, String message, String signature) {
    public SignedMessage {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (address == null || address.isBlank()) throw new IllegalArgumentException("address must not be blank");
        if (message == null) throw new IllegalArgumentException("message must not be null");
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("signature must not be blank");
    }
}