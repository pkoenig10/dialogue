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

@FunctionalInterface
interface LimitedChannelListener {

    /**
     * Invoked when requests may succeed. There is no guarantee that requests will be accepted.
     * This is only necessary if edge-triggering is not sufficient. For example if permits are based
     * on the number of active requests, when existing requests complete this is triggered automatically.
     * LimitedChannel implementations <i>should</i> invoke {@link #onChannelReady()} when another
     * event (scheduled timeout) allows requests to proceed.
     */
    void onChannelReady();
}