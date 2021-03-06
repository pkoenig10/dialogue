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
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Combined ScheduledExecutorService and Clock. All tasks get executed on the main thread. */
final class Simulation {
    private static final Logger log = LoggerFactory.getLogger(Simulation.class);

    private final NanosecondPrecisionDeterministicScheduler deterministicExecutor =
            new NanosecondPrecisionDeterministicScheduler();
    private final ListeningScheduledExecutorService listenableExecutor;

    private final TestCaffeineTicker ticker = new TestCaffeineTicker();
    private final SimulationMetricsReporter metrics = new SimulationMetricsReporter(this);
    private final CodahaleClock codahaleClock = new CodahaleClock(ticker);
    private final EventMarkers eventMarkers = new EventMarkers(ticker);
    private final TaggedMetrics taggedMetrics = new TaggedMetrics(codahaleClock);
    private final Random random = new Random(3218974678L);

    Simulation() {
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> log.error("Uncaught throwable", e));
        this.listenableExecutor = new ExternalDeterministicScheduler(deterministicExecutor, ticker);
    }

    public Ticker clock() {
        return ticker; // read only!
    }

    public ListeningScheduledExecutorService scheduler() {
        return listenableExecutor;
    }

    public CodahaleClock codahaleClock() {
        return codahaleClock;
    }

    public TaggedMetrics taggedMetrics() {
        return taggedMetrics;
    }

    public SimulationMetricsReporter metricsReporter() {
        return metrics;
    }

    public EventMarkers events() {
        return eventMarkers;
    }

    public void runClockToInfinity(Optional<Duration> infinity) {
        deterministicExecutor.tick(infinity.orElseGet(() -> Duration.ofDays(1)).toNanos(), TimeUnit.NANOSECONDS);
    }

    // note this is internally mutable
    public Random pseudoRandom() {
        return random;
    }
}
