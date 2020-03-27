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

package com.palantir.conjure.java.dialogue.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.palantir.conjure.java.api.errors.ErrorType;
import com.palantir.conjure.java.api.errors.RemoteException;
import com.palantir.conjure.java.api.errors.SerializableError;
import com.palantir.conjure.java.api.errors.ServiceException;
import com.palantir.dialogue.BodySerDe;
import com.palantir.dialogue.RequestBody;
import com.palantir.dialogue.Response;
import com.palantir.dialogue.TypeMarker;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import javax.ws.rs.core.HttpHeaders;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ConjureBodySerDeTest {

    private static final TypeMarker<String> TYPE = new TypeMarker<String>() {};
    private static final TypeMarker<Optional<String>> OPTIONAL_TYPE = new TypeMarker<Optional<String>>() {};

    @Mock
    private ErrorDecoder errorDecoder;

    @Test
    public void testRequestContentType() throws IOException {
        Encoding json = new StubEncoding("application/json");
        Encoding plain = new StubEncoding("text/plain");

        TestResponse response = new TestResponse();
        response.contentType("text/plain");
        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(json), WeightedEncoding.of(plain)));
        String value = serializers.deserializer(TYPE).deserialize(response);
        assertThat(value).isEqualTo(plain.getContentType());
    }

    @Test
    public void testRequestOptionalEmpty() {
        TestResponse response = new TestResponse();
        response.code = 204;
        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(new StubEncoding("application/json"))));
        Optional<String> value = serializers.deserializer(OPTIONAL_TYPE).deserialize(response);
        assertThat(value).isEmpty();
    }

    @Test
    public void testRequestNoContentType() {
        TestResponse response = new TestResponse();
        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(new StubEncoding("application/json"))));
        assertThatThrownBy(() -> serializers.deserializer(TYPE).deserialize(response))
                .isInstanceOf(SafeIllegalArgumentException.class)
                .hasMessageContaining("Response is missing Content-Type header");
    }

    @Test
    public void testUnsupportedRequestContentType() {
        TestResponse response = new TestResponse();
        response.contentType("application/unknown");
        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(new StubEncoding("application/json"))));
        assertThatThrownBy(() -> serializers.deserializer(TYPE).deserialize(response))
                .isInstanceOf(SafeRuntimeException.class)
                .hasMessageContaining("Unsupported Content-Type");
    }

    @Test
    public void testDefaultContentType() throws IOException {
        Encoding json = new StubEncoding("application/json");
        Encoding plain = new StubEncoding("text/plain");

        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(plain), WeightedEncoding.of(json)));
        // first encoding is default
        RequestBody body = serializers.serializer(TYPE).serialize("test");
        assertThat(body.contentType()).isEqualTo(plain.getContentType());
        assertThat(serializers.deserializer(TYPE).accepts()).hasValue("text/plain, application/json");
    }

    @Test
    public void testAcceptBasedOnWeight() throws IOException {
        Encoding json = new StubEncoding("application/json");
        Encoding plain = new StubEncoding("text/plain");

        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(plain, .5), WeightedEncoding.of(json, 1)));
        // first encoding is default
        RequestBody body = serializers.serializer(TYPE).serialize("test");
        assertThat(body.contentType()).isEqualTo(plain.getContentType());
        assertThat(serializers.deserializer(TYPE).accepts()).hasValue("application/json, text/plain");
    }

    @Test
    public void testResponseNoContentType() throws IOException {
        Encoding json = new StubEncoding("application/json");
        Encoding plain = new StubEncoding("text/plain");

        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(json), WeightedEncoding.of(plain)));
        RequestBody body = serializers.serializer(TYPE).serialize("test");
        assertThat(body.contentType()).isEqualTo(json.getContentType());
    }

    @Test
    public void testResponseUnknownContentType() throws IOException {
        Encoding json = new StubEncoding("application/json");
        Encoding plain = new StubEncoding("text/plain");

        TestResponse response = new TestResponse();
        response.contentType("application/unknown");
        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(json), WeightedEncoding.of(plain)));
        RequestBody body = serializers.serializer(TYPE).serialize("test");
        assertThat(body.contentType()).isEqualTo(json.getContentType());
    }

    @Test
    public void testErrorsDecoded() {
        TestResponse response = new TestResponse();
        response.code = 400;

        ServiceException serviceException = new ServiceException(ErrorType.INVALID_ARGUMENT);
        SerializableError serialized = SerializableError.forException(serviceException);
        when(errorDecoder.isError(response)).thenReturn(true);
        when(errorDecoder.decode(response)).thenReturn(new RemoteException(serialized, 400));

        BodySerDe serializers = new ConjureBodySerDe(
                ImmutableList.of(WeightedEncoding.of(new StubEncoding("text/plain"))), errorDecoder);

        assertThatExceptionOfType(RemoteException.class)
                .isThrownBy(() -> serializers.deserializer(TYPE).deserialize(response));
    }

    @Test
    public void testBinary() {
        TestResponse response = new TestResponse();
        response.code = 200;
        response.contentType("application/octet-stream");
        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(new StubEncoding("application/json"))));
        assertThat(serializers.inputStreamDeserializer().deserialize(response)).hasContent("");
    }

    @Test
    public void testBinary_optional_present() {
        TestResponse response = new TestResponse();
        response.code = 200;
        response.contentType("application/octet-stream");
        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(new StubEncoding("application/json"))));
        assertThat(serializers.optionalInputStreamDeserializer().deserialize(response))
                .hasValueSatisfying(stream -> assertThat(stream).hasContent(""));
    }

    @Test
    public void testBinary_optional_empty() {
        TestResponse response = new TestResponse();
        response.code = 204;
        BodySerDe serializers =
                new ConjureBodySerDe(ImmutableList.of(WeightedEncoding.of(new StubEncoding("application/json"))));
        assertThat(serializers.optionalInputStreamDeserializer().deserialize(response))
                .isEmpty();
    }

    /** Deserializes requests as the configured content type. */
    public static final class StubEncoding implements Encoding {

        private final String contentType;

        StubEncoding(String contentType) {
            this.contentType = contentType;
        }

        @Override
        public <T> Serializer<T> serializer(TypeMarker<T> _type) {
            return (value, output) -> {
                // nop
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Deserializer<T> deserializer(TypeMarker<T> type) {
            return input -> {
                Preconditions.checkArgument(TYPE.equals(type), "This stub encoding only supports String");
                return (T) getContentType();
            };
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean supportsContentType(String input) {
            return contentType.equals(input);
        }

        @Override
        public String toString() {
            return "StubEncoding{" + contentType + '}';
        }
    }

    private static final class TestResponse implements Response {

        private final CloseableInputStream body =
                new CloseableInputStream(new ByteArrayInputStream(new byte[]{}));
        private int code = 0;
        private ListMultimap<String, String> headers = ImmutableListMultimap.of();

        @Override
        public CloseableInputStream body() {
            return body;
        }

        @Override
        public int code() {
            return code;
        }

        @Override
        public ListMultimap<String, String> headers() {
            return headers;
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException e) {
                throw new SafeRuntimeException("Failed to close", e);
            }
        }

        @VisibleForTesting
        void contentType(String contentType) {
            this.headers = ImmutableListMultimap.of(HttpHeaders.CONTENT_TYPE, contentType);
        }
    }
}
