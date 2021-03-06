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

import com.github.benmanes.caffeine.cache.Ticker;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;

final class TestCaffeineTicker implements Ticker {

    private long nanos = 0;

    @Override
    public long read() {
        return nanos;
    }

    void advanceTo(long newNanos) {
        if (newNanos < nanos) {
            long difference = nanos - newNanos;
            throw new SafeIllegalStateException(
                    "Time rewind - this is likely a bug in the test harness", SafeArg.of("difference", difference));
        }

        nanos = newNanos;
    }
}
