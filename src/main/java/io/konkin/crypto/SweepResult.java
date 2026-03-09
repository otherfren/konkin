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
import java.util.List;
import java.util.Map;

public record SweepResult(Coin coin, List<String> txIds, BigDecimal totalAmount, BigDecimal totalFee, Map<String, String> extras) {
    public SweepResult {
        if (coin == null) throw new IllegalArgumentException("coin must not be null");
        if (txIds == null || txIds.isEmpty()) throw new IllegalArgumentException("txIds must not be empty");
        if (totalAmount == null) throw new IllegalArgumentException("totalAmount must not be null");
        if (totalFee == null) throw new IllegalArgumentException("totalFee must not be null");
        txIds = List.copyOf(txIds);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
