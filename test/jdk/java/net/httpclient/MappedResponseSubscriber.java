/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Tests mapped response subscriber
 * @library /lib/testlibrary http2/server
 * @build jdk.testlibrary.SimpleSSLContext
 * @modules java.base/sun.net.www.http
 *          java.net.http/jdk.internal.net.http.common
 *          java.net.http/jdk.internal.net.http.frame
 *          java.net.http/jdk.internal.net.http.hpack
 * @run testng/othervm MappedResponseSubscriber
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import  java.net.http.HttpResponse.BodySubscriber;
import javax.net.ssl.SSLContext;
import jdk.testlibrary.SimpleSSLContext;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static java.lang.System.out;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.net.http.HttpResponse.BodySubscriber.asString;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class MappedResponseSubscriber {

    SSLContext sslContext;
    HttpServer httpTestServer;         // HTTP/1.1    [ 4 servers ]
    HttpsServer httpsTestServer;       // HTTPS/1.1
    Http2TestServer http2TestServer;   // HTTP/2 ( h2c )
    Http2TestServer https2TestServer;  // HTTP/2 ( h2  )
    String httpURI_fixed;
    String httpURI_chunk;
    String httpsURI_fixed;
    String httpsURI_chunk;
    String http2URI_fixed;
    String http2URI_chunk;
    String https2URI_fixed;
    String https2URI_chunk;

    static final int ITERATION_COUNT = 10;
    // a shared executor helps reduce the amount of threads created by the test
    static final Executor executor = Executors.newCachedThreadPool();

    @DataProvider(name = "variants")
    public Object[][] variants() {
        return new Object[][]{
                { httpURI_fixed,    false },
                { httpURI_chunk,    false },
                { httpsURI_fixed,   false },
                { httpsURI_chunk,   false },
                { http2URI_fixed,   false },
                { http2URI_chunk,   false },
                { https2URI_fixed,  false,},
                { https2URI_chunk,  false },

                { httpURI_fixed,    true },
                { httpURI_chunk,    true },
                { httpsURI_fixed,   true },
                { httpsURI_chunk,   true },
                { http2URI_fixed,   true },
                { http2URI_chunk,   true },
                { https2URI_fixed,  true,},
                { https2URI_chunk,  true },
        };
    }

    HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                         .executor(executor)
                         .sslContext(sslContext)
                         .build();
    }

    @Test(dataProvider = "variants")
    public void testAsBytes(String uri, boolean sameClient) throws Exception {
        HttpClient client = null;
        for (int i=0; i< ITERATION_COUNT; i++) {
            if (!sameClient || client == null)
                client = newHttpClient();

            HttpRequest req = HttpRequest.newBuilder(URI.create(uri))
                                         .build();
            BodyHandler<byte[]> handler = new CRSBodyHandler();
            HttpResponse<byte[]> response = client.send(req, handler);
            byte[] body = response.body();
            assertEquals(body, bytes);
        }
    }

    static class CRSBodyHandler implements BodyHandler<byte[]> {
        @Override
        public BodySubscriber<byte[]> apply(int statusCode, HttpHeaders responseHeaders) {
            assertEquals(statusCode, 200);
            return HttpResponse.BodySubscriber.mappedFrom(
                new CRSBodySubscriber(), (s) -> s.getBytes()
            );
        }
    }

    static class CRSBodySubscriber implements BodySubscriber<String> {
        private final BodySubscriber<String> asString = asString(UTF_8);
        volatile boolean onSubscribeCalled;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            //out.println("onSubscribe ");
            onSubscribeCalled = true;
            asString.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
           // out.println("onNext " + item);
            assertTrue(onSubscribeCalled);
            asString.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            //out.println("onError");
            assertTrue(onSubscribeCalled);
            asString.onError(throwable);
        }

        @Override
        public void onComplete() {
            //out.println("onComplete");
            assertTrue(onSubscribeCalled, "onComplete called before onSubscribe");
            asString.onComplete();
        }

        @Override
        public CompletionStage<String> getBody() {
            return asString.getBody();
        }
    }


    @BeforeTest
    public void setup() throws Exception {
        sslContext = new SimpleSSLContext().get();
        if (sslContext == null)
            throw new AssertionError("Unexpected null sslContext");

        // HTTP/1.1
        HttpHandler h1_fixedLengthHandler = new HTTP1_FixedLengthHandler();
        HttpHandler h1_chunkHandler = new HTTP1_ChunkedHandler();
        InetSocketAddress sa = new InetSocketAddress("localhost", 0);
        httpTestServer = HttpServer.create(sa, 0);
        httpTestServer.createContext("/http1/fixed", h1_fixedLengthHandler);
        httpTestServer.createContext("/http1/chunk", h1_chunkHandler);
        httpURI_fixed = "http://127.0.0.1:" + httpTestServer.getAddress().getPort() + "/http1/fixed";
        httpURI_chunk = "http://127.0.0.1:" + httpTestServer.getAddress().getPort() + "/http1/chunk";

        httpsTestServer = HttpsServer.create(sa, 0);
        httpsTestServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        httpsTestServer.createContext("/https1/fixed", h1_fixedLengthHandler);
        httpsTestServer.createContext("/https1/chunk", h1_chunkHandler);
        httpsURI_fixed = "https://127.0.0.1:" + httpsTestServer.getAddress().getPort() + "/https1/fixed";
        httpsURI_chunk = "https://127.0.0.1:" + httpsTestServer.getAddress().getPort() + "/https1/chunk";

        // HTTP/2
        Http2Handler h2_fixedLengthHandler = new HTTP2_FixedLengthHandler();
        Http2Handler h2_chunkedHandler = new HTTP2_VariableHandler();

        http2TestServer = new Http2TestServer("127.0.0.1", false, 0);
        http2TestServer.addHandler(h2_fixedLengthHandler, "/http2/fixed");
        http2TestServer.addHandler(h2_chunkedHandler, "/http2/chunk");
        int port = http2TestServer.getAddress().getPort();
        http2URI_fixed = "http://127.0.0.1:" + port + "/http2/fixed";
        http2URI_chunk = "http://127.0.0.1:" + port + "/http2/chunk";

        https2TestServer = new Http2TestServer("127.0.0.1", true, 0);
        https2TestServer.addHandler(h2_fixedLengthHandler, "/https2/fixed");
        https2TestServer.addHandler(h2_chunkedHandler, "/https2/chunk");
        port = https2TestServer.getAddress().getPort();
        https2URI_fixed = "https://127.0.0.1:" + port + "/https2/fixed";
        https2URI_chunk = "https://127.0.0.1:" + port + "/https2/chunk";

        httpTestServer.start();
        httpsTestServer.start();
        http2TestServer.start();
        https2TestServer.start();
    }

    @AfterTest
    public void teardown() throws Exception {
        httpTestServer.stop(0);
        httpsTestServer.stop(0);
        http2TestServer.stop();
        https2TestServer.stop();
    }

    static byte[] bytes;

    static {
        bytes = new byte[128 * 1024];
        int b = 'A';

        for (int i=0; i< bytes.length; i++) {
            bytes[i] = (byte)b;
            b = b == 'Z'? 'A' : b + 1;
        }
    }

    static class HTTP1_FixedLengthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            out.println("HTTP1_FixedLengthHandler received request to " + t.getRequestURI());
            try (InputStream is = t.getRequestBody()) {
                is.readAllBytes();
            }
            t.sendResponseHeaders(200, bytes.length);  //no body
            OutputStream os = t.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

    static class HTTP1_ChunkedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            out.println("HTTP1_ChunkedHandler received request to " + t.getRequestURI());
            try (InputStream is = t.getRequestBody()) {
                is.readAllBytes();
            }
            t.sendResponseHeaders(200, 0); // chunked
            OutputStream os = t.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

    static class HTTP2_FixedLengthHandler implements Http2Handler {
        @Override
        public void handle(Http2TestExchange t) throws IOException {
            out.println("HTTP2_FixedLengthHandler received request to " + t.getRequestURI());
            try (InputStream is = t.getRequestBody()) {
                is.readAllBytes();
            }
            t.sendResponseHeaders(200, 0); // chunked
            OutputStream os = t.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }

    static class HTTP2_VariableHandler implements Http2Handler {
        @Override
        public void handle(Http2TestExchange t) throws IOException {
            out.println("HTTP2_VariableHandler received request to " + t.getRequestURI());
            try (InputStream is = t.getRequestBody()) {
                is.readAllBytes();
            }
            t.sendResponseHeaders(200, bytes.length);  //no body
            OutputStream os = t.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }
}
