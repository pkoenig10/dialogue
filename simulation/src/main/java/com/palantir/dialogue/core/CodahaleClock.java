/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.dialogue.core;

import com.codahale.metrics.Clock;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.annotations.VisibleForTesting;
import java.util.concurrent.TimeUnit;

@VisibleForTesting
final class CodahaleClock extends Clock {
    private final Ticker caffeineTicker;

    CodahaleClock(Ticker caffeineTicker) {
        this.caffeineTicker = caffeineTicker;
    }

    @Override
    public long getTick() {
        return caffeineTicker.read(); // effectively System.nanoTime()
    }

    @Override
    public long getTime() {
        return TimeUnit.MILLISECONDS.convert(getTick(), TimeUnit.NANOSECONDS);
    }
}
