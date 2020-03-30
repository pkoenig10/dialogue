/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Metric;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.palantir.conjure.java.api.config.service.ServiceConfiguration;
import com.palantir.conjure.java.api.config.service.UserAgent;
import com.palantir.conjure.java.api.config.ssl.SslConfiguration;
import com.palantir.conjure.java.client.config.ClientConfiguration;
import com.palantir.conjure.java.client.config.ClientConfigurations;
import com.palantir.dialogue.Channel;
import com.palantir.dialogue.Endpoint;
import com.palantir.dialogue.HttpMethod;
import com.palantir.dialogue.Request;
import com.palantir.dialogue.Response;
import com.palantir.dialogue.TestResponse;
import com.palantir.dialogue.UrlBuilder;
import com.palantir.tracing.TestTracing;
import com.palantir.tritium.metrics.registry.DefaultTaggedMetricRegistry;
import com.palantir.tritium.metrics.registry.MetricName;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public final class DialogueChannelsTest {

    public static final UserAgent USER_AGENT = UserAgent.of(UserAgent.Agent.of("foo", "1.0.0"));
    private static final SslConfiguration SSL_CONFIG = SslConfiguration.of(
            Paths.get("src/test/resources/trustStore.jks"), Paths.get("src/test/resources/keyStore.jks"), "keystore");
    private static final ClientConfiguration stubConfig = ClientConfiguration.builder()
            .from(ClientConfigurations.of(ServiceConfiguration.builder()
                    .addUris("http://localhost")
                    .security(SSL_CONFIG)
                    .build()))
            .taggedMetricRegistry(new DefaultTaggedMetricRegistry())
            .userAgent(USER_AGENT)
            .build();

    @Mock
    private Channel delegate;

    private Endpoint endpoint = new Endpoint() {
        @Override
        public void renderPath(Map<String, String> _params, UrlBuilder _url) {}

        @Override
        public HttpMethod httpMethod() {
            return HttpMethod.GET;
        }

        @Override
        public String serviceName() {
            return "test-service";
        }

        @Override
        public String endpointName() {
            return "test-endpoint";
        }

        @Override
        public String version() {
            return "1.0.0";
        }
    };

    @Mock
    private Response response;

    private Request request = Request.builder().build();
    private DialogueChannel channel;

    @BeforeEach
    public void before() {
        channel = DialogueChannel.builder()
                .channelName("my-channel")
                .clientConfiguration(stubConfig)
                .channelFactory(_uri -> delegate)
                .build();

        ListenableFuture<Response> expectedResponse = Futures.immediateFuture(response);
        lenient().when(delegate.execute(eq(endpoint), any())).thenReturn(expectedResponse);
    }

    @Test
    public void testRequestMakesItThrough() throws ExecutionException, InterruptedException {
        assertThat(channel.execute(endpoint, request).get()).isNotNull();
    }

    @Test
    public void bad_channel_throwing_an_exception_still_returns_a_future() {
        Channel badUserImplementation = new Channel() {
            @Override
            public ListenableFuture<Response> execute(Endpoint _endpoint, Request _request) {
                throw new IllegalStateException("Always throw");
            }
        };

        channel = DialogueChannel.builder()
                .channelName("my-channel")
                .clientConfiguration(stubConfig)
                .channelFactory(uri -> badUserImplementation)
                .build();

        // this should never throw
        ListenableFuture<Response> future = channel.execute(endpoint, request);

        // only when we access things do we allow exceptions
        assertThatThrownBy(() -> Futures.getUnchecked(future)).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructing_a_client_with_zero_uris_just_queues_things_up() throws Exception {
        when(delegate.execute(any(), any())).thenReturn(Futures.immediateFuture(new TestResponse().code(200)));

        channel = DialogueChannel.builder()
                .channelName("my-channel")
                .clientConfiguration(ClientConfiguration.builder()
                        .from(stubConfig)
                        .uris(Collections.emptyList())
                        .build())
                .channelFactory(uri -> delegate)
                .build();
        ListenableFuture<Response> future1 = channel.execute(endpoint, request);
        ListenableFuture<Response> future2 = channel.execute(endpoint, request);
        ListenableFuture<Response> future3 = channel.execute(endpoint, request);
        assertThat(queuedRequestsCounter())
                .describedAs("All the requests we submitted should just be sitting on the queue")
                .isEqualTo(3);

        // live-reload from 0 -> 1
        channel.updateUris(ImmutableList.of("http://dont-care"));

        assertThat(future1.get(1, TimeUnit.MILLISECONDS).code()).isEqualTo(200);
        assertThat(future2.get(1, TimeUnit.MILLISECONDS).code()).isEqualTo(200);
        assertThat(future3.get(1, TimeUnit.MILLISECONDS).code()).isEqualTo(200);
    }

    @Test
    void live_reloading_to_zero_uris_is_allowed_because_futures_are_just_queued() throws Exception {
        when(delegate.execute(any(), any())).thenReturn(Futures.immediateFuture(new TestResponse().code(200)));

        channel = DialogueChannel.builder()
                .channelName("my-channel")
                .clientConfiguration(stubConfig)
                .channelFactory(uri -> delegate)
                .build();
        assertThat(channel.execute(endpoint, request).get().code())
                .describedAs("Initial requests should go through fine")
                .isEqualTo(200);

        // live reload from 1 -> 0
        channel.updateUris(Collections.emptyList());

        ListenableFuture<Response> future = channel.execute(endpoint, request);
        assertThat(future)
                .describedAs("Future should be unresolved while we have 0 uris")
                .isNotDone();
        assertThat(queuedRequestsCounter()).isEqualTo(1);
    }

    @Test
    public void bad_channel_throwing_an_error_still_returns_a_future() {
        Channel badUserImplementation = new Channel() {
            @Override
            public ListenableFuture<Response> execute(Endpoint _endpoint, Request _request) {
                throw new NoClassDefFoundError("something is broken");
            }
        };

        channel = DialogueChannel.builder()
                .channelName("my-channel")
                .clientConfiguration(stubConfig)
                .channelFactory(uri -> badUserImplementation)
                .build();

        // this should never throw
        ListenableFuture<Response> future = channel.execute(endpoint, request);

        // only when we access things do we allow exceptions
        assertThatThrownBy(() -> Futures.getUnchecked(future)).hasCauseInstanceOf(NoClassDefFoundError.class);
    }

    @Test
    @TestTracing(snapshot = true)
    public void traces_on_retries() throws Exception {
        when(response.code()).thenReturn(429);
        try (Response response = channel.execute(endpoint, request).get()) {
            assertThat(response.code()).isEqualTo(429);
        }
    }

    @Test
    @TestTracing(snapshot = true)
    public void traces_on_success() throws Exception {
        when(response.code()).thenReturn(200);
        try (Response response = channel.execute(endpoint, request).get()) {
            assertThat(response.code()).isEqualTo(200);
        }
    }

    private static long queuedRequestsCounter() {
        Map<MetricName, Metric> metrics = Maps.filterKeys(
                stubConfig.taggedMetricRegistry().getMetrics(),
                name -> Objects.equals(name.safeName(), "dialogue.client.requests.queued"));
        Counter counter = (Counter) Iterables.getOnlyElement(metrics.values());
        return counter.getCount();
    }
}
