/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;

import com.linecorp.armeria.client.encoding.DeflateStreamDecoderFactory;
import com.linecorp.armeria.client.encoding.HttpDecodingClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;
import com.linecorp.armeria.server.encoding.HttpEncodingService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

class HttpClientIntegrationTest {

    private static final AtomicReference<ByteBuf> releasedByteBuf = new AtomicReference<>();

    // Used to communicate with test when the response can't be used.
    private static final AtomicReference<Boolean> completed = new AtomicReference<>();

    private static final class PoolUnawareDecorator extends SimpleDecoratingService<HttpRequest, HttpResponse> {

        private PoolUnawareDecorator(Service<HttpRequest, HttpResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final HttpResponse res = delegate().serve(ctx, req);
            final HttpResponseWriter decorated = HttpResponse.streaming();
            res.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject httpObject) {
                    decorated.write(httpObject);
                }

                @Override
                public void onError(Throwable t) {
                    decorated.close(t);
                }

                @Override
                public void onComplete() {
                    decorated.close();
                }
            });
            return decorated;
        }
    }

    private static final class PoolAwareDecorator extends SimpleDecoratingService<HttpRequest, HttpResponse> {

        private PoolAwareDecorator(Service<HttpRequest, HttpResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final HttpResponse res = delegate().serve(ctx, req);
            final HttpResponseWriter decorated = HttpResponse.streaming();
            res.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject httpObject) {
                    if (httpObject instanceof ByteBufHolder) {
                        try {
                            decorated.write(HttpData.copyOf(((ByteBufHolder) httpObject).content()));
                        } finally {
                            ReferenceCountUtil.safeRelease(httpObject);
                        }
                    } else {
                        decorated.write(httpObject);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    decorated.close(t);
                }

                @Override
                public void onComplete() {
                    decorated.close();
                }
            }, WITH_POOLED_OBJECTS);
            return decorated;
        }
    }

    private static class PooledContentService extends AbstractHttpService {

        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            final ByteBuf buf = ctx.alloc().buffer();
            buf.writeCharSequence("pooled content", StandardCharsets.UTF_8);
            releasedByteBuf.set(buf);
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, new ByteBufHttpData(buf, false));
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/httptestbody", new AbstractHttpService() {

                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return doGetOrPost(req);
                }

                @Override
                protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
                    return doGetOrPost(req);
                }

                private HttpResponse doGetOrPost(HttpRequest req) {
                    final MediaType contentType = req.contentType();
                    if (contentType != null) {
                        throw new IllegalArgumentException(
                                "Serialization format is none, so content type should not be set: " +
                                contentType);
                    }

                    final String accept = req.headers().get(HttpHeaderNames.ACCEPT);
                    if (!"utf-8".equals(accept)) {
                        throw new IllegalArgumentException(
                                "Serialization format is none, so accept should not be overridden: " +
                                accept);
                    }

                    return HttpResponse.from(req.aggregate().handle((aReq, cause) -> {
                        if (cause != null) {
                            return HttpResponse.of(
                                    HttpStatus.INTERNAL_SERVER_ERROR,
                                    MediaType.PLAIN_TEXT_UTF_8, Exceptions.traceText(cause));
                        }

                        return HttpResponse.of(
                                ResponseHeaders.of(HttpStatus.OK,
                                                   HttpHeaderNames.CACHE_CONTROL, "alwayscache"),
                                HttpData.ofUtf8(
                                        "METHOD: %s|ACCEPT: %s|BODY: %s",
                                        req.method().name(), accept,
                                        aReq.contentUtf8()));
                    }).exceptionally(CompletionActions::log));
                }
            });

            sb.service("/not200", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.NOT_FOUND);
                }
            });

            sb.service("/useragent", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final String ua = req.headers().get(HttpHeaderNames.USER_AGENT, "undefined");
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, ua);
                }
            });

            sb.service("/authority", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final String ua = req.headers().get(HttpHeaderNames.AUTHORITY, "undefined");
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, ua);
                }
            });

            sb.service("/hello/world", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "success");
                }
            });

            sb.service("/encoding", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK),
                            HttpData.ofUtf8("some content to compress "),
                            HttpData.ofUtf8("more content to compress"));
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/encoding-toosmall", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "small content");
                }
            }.decorate(HttpEncodingService.class));

            sb.service("/pooled", new PooledContentService());

            sb.service("/pooled-aware", new PooledContentService().decorate(PoolAwareDecorator::new));

            sb.service("/pooled-unaware", new PooledContentService().decorate(PoolUnawareDecorator::new));

            sb.service("/stream-closed", (ctx, req) -> {
                ctx.setRequestTimeout(Duration.ZERO);
                final HttpResponseWriter res = HttpResponse.streaming();
                res.write(ResponseHeaders.of(HttpStatus.OK));
                req.subscribe(new Subscriber<HttpObject>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(HttpObject httpObject) {
                    }

                    @Override
                    public void onError(Throwable t) {
                        completed.set(true);
                    }

                    @Override
                    public void onComplete() {
                    }
                }, ctx.eventLoop());
                return res;
            });

            sb.service("glob:/oneparam/**", (ctx, req) -> {
                // The client was able to send a request with an escaped path param. Armeria servers always
                // decode the path so ctx.path == '/oneparam/foo/bar' here.
                if ("/oneparam/foo%2Fbar".equals(req.headers().path()) &&
                    "/oneparam/foo/bar".equals(ctx.path())) {
                    return HttpResponse.of("routed");
                }
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
            });

            // To check https://github.com/line/armeria/issues/1895
            sb.serviceUnder("/", (ctx, req) -> {
                if (!completed.compareAndSet(false, true)) {
                    return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                } else {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });
        }
    };

    private static final ClientFactory clientFactory = ClientFactory.DEFAULT;

    @BeforeEach
    void clearError() {
        completed.set(false);
        releasedByteBuf.set(null);
    }

    /**
     * When the content of a request is empty, the encoded request should never have 'content-length' or
     * 'transfer-encoding' header.
     */
    @Test
    void testRequestNoBodyWithoutExtraHeaders() throws Exception {
        testSocketOutput(
                "/foo",
                port -> "GET /foo HTTP/1.1\r\n" +
                        "host: 127.0.0.1:" + port + "\r\n" +
                        "user-agent: " + HttpHeaderUtil.USER_AGENT + "\r\n\r\n");
    }

    @Test
    void testRequestNoBody() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.GET, "/httptestbody",
                                  HttpHeaderNames.ACCEPT, "utf-8")).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("alwayscache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals("METHOD: GET|ACCEPT: utf-8|BODY: ", response.contentUtf8());
    }

    @Test
    void testRequestWithBody() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST, "/httptestbody",
                                  HttpHeaderNames.ACCEPT, "utf-8"),
                "requestbody日本語").aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("alwayscache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        assertEquals("METHOD: POST|ACCEPT: utf-8|BODY: requestbody日本語", response.contentUtf8());
    }

    @Test
    void testResolvedEndpointWithAlternateAuthority() throws Exception {
        final EndpointGroup group = new StaticEndpointGroup(Endpoint.of("localhost", server.httpPort())
                                                                    .withIpAddr("127.0.0.1"));
        testEndpointWithAlternateAuthority(group);
    }

    @Test
    void testUnresolvedEndpointWithAlternateAuthority() throws Exception {
        final EndpointGroup group = new StaticEndpointGroup(Endpoint.of("localhost", server.httpPort()));
        testEndpointWithAlternateAuthority(group);
    }

    private static void testEndpointWithAlternateAuthority(EndpointGroup group) {
        final String groupName = "testEndpointWithAlternateAuthority";
        EndpointGroupRegistry.register(groupName, group, EndpointSelectionStrategy.ROUND_ROBIN);
        try {
            final HttpClient client = new HttpClientBuilder("http://group:" + groupName)
                    .setHttpHeader(HttpHeaderNames.AUTHORITY, "255.255.255.255.xip.io")
                    .build();

            final AggregatedHttpResponse res = client.get("/hello/world").aggregate().join();
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentUtf8()).isEqualTo("success");
        } finally {
            EndpointGroupRegistry.unregister(groupName);
        }
    }

    @Test
    void testNot200() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        final AggregatedHttpResponse response = client.get("/not200").aggregate().get();

        assertEquals(HttpStatus.NOT_FOUND, response.status());
    }

    /**
     * :authority header should be overridden by ClientOption.HTTP_HEADER
     */
    @Test
    void testAuthorityOverridableByClientOption() throws Exception {

        testHeaderOverridableByClientOption("/authority", HttpHeaderNames.AUTHORITY, "foo:8080");
    }

    @Test
    void testAuthorityOverridableByRequestHeader() throws Exception {

        testHeaderOverridableByRequestHeader("/authority", HttpHeaderNames.AUTHORITY, "bar:8080");
    }

    /**
     * User-agent header should be overridden by ClientOption.HTTP_HEADER
     */
    @Test
    void testUserAgentOverridableByClientOption() throws Exception {
        testHeaderOverridableByClientOption("/useragent", HttpHeaderNames.USER_AGENT, "foo-agent");
    }

    @Test
    void testUserAgentOverridableByRequestHeader() throws Exception {
        testHeaderOverridableByRequestHeader("/useragent", HttpHeaderNames.USER_AGENT, "bar-agent");
    }

    private static void testHeaderOverridableByClientOption(String path, AsciiString headerName,
                                                            String headerValue) throws Exception {
        final HttpHeaders headers = HttpHeaders.of(headerName, headerValue);
        final ClientOptions options = ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(headers));
        final HttpClient client = HttpClient.of(server.uri("/"), options);

        final AggregatedHttpResponse response = client.get(path).aggregate().get();

        assertEquals(headerValue, response.contentUtf8());
    }

    private static void testHeaderOverridableByRequestHeader(String path, AsciiString headerName,
                                                             String headerValue) throws Exception {
        final HttpHeaders headers = HttpHeaders.of(headerName, headerValue);
        final ClientOptions options = ClientOptions.of(ClientOption.HTTP_HEADERS.newValue(headers));
        final HttpClient client = HttpClient.of(server.uri("/"), options);

        final String OVERRIDDEN_VALUE = "Overridden";

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, path,
                                                 headerName, OVERRIDDEN_VALUE))
                      .aggregate().get();

        assertEquals(OVERRIDDEN_VALUE, response.contentUtf8());
    }

    @Test
    void httpDecoding() throws Exception {
        final HttpClient client = new HttpClientBuilder(server.uri("/"))
                .factory(clientFactory).decorator(HttpDecodingClient.newDecorator()).build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.contentUtf8()).isEqualTo(
                "some content to compress more content to compress");
    }

    @Test
    void httpDecoding_deflate() throws Exception {
        final HttpClient client = new HttpClientBuilder(server.uri("/"))
                .factory(clientFactory)
                .decorator(HttpDecodingClient.newDecorator(new DeflateStreamDecoderFactory())).build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("deflate");
        assertThat(response.contentUtf8()).isEqualTo(
                "some content to compress more content to compress");
    }

    @Test
    void httpDecoding_noEncodingApplied() throws Exception {
        final HttpClient client = new HttpClientBuilder(server.uri("/"))
                .factory(clientFactory)
                .decorator(HttpDecodingClient.newDecorator(new DeflateStreamDecoderFactory())).build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-toosmall")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        assertThat(response.contentUtf8()).isEqualTo("small content");
    }

    private static void testSocketOutput(String path,
                                         IntFunction<String> expectedResponse) throws IOException {
        Socket s = null;
        try (ServerSocket ss = new ServerSocket(0)) {
            final int port = ss.getLocalPort();
            final String expected = expectedResponse.apply(port);

            // Send a request. Note that we do not wait for a response anywhere because we are only interested
            // in testing what client sends.
            Clients.newClient(clientFactory, "none+h1c://127.0.0.1:" + port, HttpClient.class).get(path);
            ss.setSoTimeout(10000);
            s = ss.accept();

            final byte[] buf = new byte[expected.length()];
            final InputStream in = s.getInputStream();

            // Read the encoded request.
            s.setSoTimeout(10000);
            ByteStreams.readFully(in, buf);

            // Ensure that the encoded request matches.
            assertThat(new String(buf, StandardCharsets.US_ASCII)).isEqualTo(expected);

            // Should not send anything more.
            s.setSoTimeout(1000);
            assertThatThrownBy(in::read).isInstanceOf(SocketTimeoutException.class);
        } finally {
            Closeables.close(s, true);
        }
    }

    @Test
    void givenHttpClientUriPathAndRequestPath_whenGet_thenRequestToConcatenatedPath() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/hello"));

        final AggregatedHttpResponse response = client.get("/world").aggregate().get();

        assertEquals("success", response.contentUtf8());
    }

    @Test
    void givenRequestPath_whenGet_thenRequestToPath() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        final AggregatedHttpResponse response = client.get("/hello/world").aggregate().get();

        assertEquals("success", response.contentUtf8());
    }

    @Test
    void testPooledResponseDefaultSubscriber() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.GET, "/pooled")).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertThat(response.contentUtf8()).isEqualTo("pooled content");
        await().untilAsserted(() -> assertThat(releasedByteBuf.get().refCnt()).isZero());
    }

    @Test
    void testPooledResponsePooledSubscriber() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.GET, "/pooled-aware")).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertThat(response.contentUtf8()).isEqualTo("pooled content");
        await().untilAsserted(() -> assertThat(releasedByteBuf.get().refCnt()).isZero());
    }

    @Test
    void testUnpooledResponsePooledSubscriber() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.GET, "/pooled-unaware")).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());
        assertThat(response.contentUtf8()).isEqualTo("pooled content");
        await().untilAsserted(() -> assertThat(releasedByteBuf.get().refCnt()).isZero());
    }

    @Test
    void testCloseClientFactory() throws Exception {
        final ClientFactory factory = new ClientFactoryBuilder().build();
        final HttpClient client = factory.newClient("none+" + server.uri("/"), HttpClient.class);
        final HttpRequestWriter req = HttpRequest.streaming(RequestHeaders.of(HttpMethod.GET,
                                                                              "/stream-closed"));
        final HttpResponse res = client.execute(req);
        final AtomicReference<HttpObject> obj = new AtomicReference<>();
        res.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                obj.set(httpObject);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        req.write(HttpData.ofUtf8("not finishing this stream, sorry."));
        await().untilAsserted(() -> assertThat(obj).hasValue(ResponseHeaders.of(HttpStatus.OK)));
        factory.close();
        await().untilAsserted(() -> assertThat(completed).hasValue(true));
    }

    @Test
    void testEscapedPathParam() throws Exception {
        final HttpClient client = HttpClient.of(server.uri("/"));

        final AggregatedHttpResponse response = client.get("/oneparam/foo%2Fbar").aggregate().get();

        assertEquals("routed", response.contentUtf8());
    }

    @Test
    void givenClients_thenBuildClient() throws Exception {
        final Endpoint endpoint = newEndpoint();
        final ClientFactory factory = new ClientFactoryBuilder().build();

        HttpClient client = Clients.newClient(factory, SessionProtocol.HTTP, SerializationFormat.NONE,
                                              endpoint, HttpClient.class);
        checkGetRequest("/hello/world", client);

        client = Clients.newClient(factory, SessionProtocol.HTTP, SerializationFormat.NONE, endpoint,
                                   HttpClient.class, ClientOptions.DEFAULT);
        checkGetRequest("/hello/world", client);

        client = Clients.newClient(SessionProtocol.HTTP, SerializationFormat.NONE, endpoint, HttpClient.class);
        checkGetRequest("/hello/world", client);

        client = Clients.newClient(SessionProtocol.HTTP, SerializationFormat.NONE, endpoint, HttpClient.class,
                                   ClientOptions.DEFAULT);
        checkGetRequest("/hello/world", client);
    }

    @Test
    void givenHttpClient_thenBuildClient() throws Exception {
        final Endpoint endpoint = newEndpoint();
        final ClientFactory factory = new ClientFactoryBuilder().build();

        HttpClient client = HttpClient.of(factory, SessionProtocol.HTTP, endpoint);
        checkGetRequest("/hello/world", client);

        client = HttpClient.of(factory, SessionProtocol.HTTP, endpoint, ClientOptions.DEFAULT);
        checkGetRequest("/hello/world", client);

        client = HttpClient.of(SessionProtocol.HTTP, endpoint);
        checkGetRequest("/hello/world", client);

        client = HttpClient.of(SessionProtocol.HTTP, endpoint, ClientOptions.DEFAULT);
        checkGetRequest("/hello/world", client);
    }

    @Test
    void givenClientBuilder_thenBuildClient() throws Exception {
        final Endpoint endpoint = newEndpoint();
        final ClientFactory factory = new ClientFactoryBuilder().build();

        HttpClient client = new ClientBuilder(SessionProtocol.HTTP, endpoint)
                .serializationFormat(SerializationFormat.NONE)
                .factory(factory)
                .build(HttpClient.class);
        checkGetRequest("/hello/world", client);

        client = new ClientBuilder(SessionProtocol.HTTP, endpoint)
                .build(HttpClient.class);
        checkGetRequest("/hello/world", client);

        client = new ClientBuilder("none+http", endpoint)
                .path("/hello")
                .build(HttpClient.class);
        checkGetRequest("/world", client);

        client = new ClientBuilder(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP), endpoint)
                .path("/hello")
                .build(HttpClient.class);
        checkGetRequest("/world", client);

        client = new ClientBuilder(SessionProtocol.HTTP, endpoint)
                .serializationFormat(SerializationFormat.NONE)
                .path("/hello")
                .build(HttpClient.class);
        checkGetRequest("/world", client);

        assertThatThrownBy(() -> new ClientBuilder("none+http", endpoint)
                .serializationFormat(SerializationFormat.NONE)
                .build(HttpClient.class));
    }

    @Test
    void testUpgradeRequestExecutesLogicOnlyOnce() throws Exception {
        final ClientFactory clientFactory = new ClientFactoryBuilder()
                .useHttp2Preface(false)
                .build();
        final HttpClient client = new HttpClientBuilder(server.httpUri("/"))
                .factory(clientFactory)
                .decorator(HttpDecodingClient.newDecorator())
                .build();

        final AggregatedHttpResponse response = client.execute(
                AggregatedHttpRequest.of(HttpMethod.GET, "/only-once/request")).aggregate().get();

        assertEquals(HttpStatus.OK, response.status());

        clientFactory.close();
    }

    private static void checkGetRequest(String path, HttpClient client) throws Exception {
        final AggregatedHttpResponse response = client.get(path).aggregate().get();
        assertEquals("success", response.contentUtf8());
    }

    private static Endpoint newEndpoint() {
        final URI uri = URI.create(server.httpUri("/"));
        return Endpoint.of(uri.getHost()).withDefaultPort(uri.getPort());
    }
}
