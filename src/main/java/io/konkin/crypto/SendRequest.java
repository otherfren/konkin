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

import java.math.BigDecimal;
import java.util.Map;

public record SendRequest(Coin coin, String toAddress, BigDecimal amount, Map<String, String> extras) {
    public SendRequest {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (toAddress == null || toAddress.isBlank()) throw new IllegalArgumentException("toAddress must not be blank");
        if (amount == null) throw new IllegalArgumentException("amount must not be null");
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}