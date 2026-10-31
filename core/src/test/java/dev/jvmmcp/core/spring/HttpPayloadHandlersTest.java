package dev.jvmmcp.core.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpPayloadHandlersTest {

    private static final String RESPONSE = "{\"status\":\"UP\",\"service\":\"jvm-mcp\"}";

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream compressor = new GZIPOutputStream(output)) {
            compressor.write(content);
        }
        return output.toByteArray();
    }

    private static byte[] deflate(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream compressor = new DeflaterOutputStream(output)) {
            compressor.write(content);
        }
        return output.toByteArray();
    }

    private void serve(String path, String encoding, byte[] body) {
        server.createContext(
                path,
                exchange -> {
                    if (encoding != null) {
                        exchange.getResponseHeaders().set("Content-Encoding", encoding);
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
    }

    private String fetch(String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpPayloadHandlers.limitingStringHandler(4096)).body();
        }
    }

    @Test
    void decodesGzipEncoding() throws Exception {
        byte[] body = gzip(RESPONSE.getBytes(StandardCharsets.UTF_8));
        serve("/gzip", "gzip", body);

        assertThat(fetch("/gzip")).isEqualTo(RESPONSE);
    }

    @Test
    void decodesLegacyXGzipEncoding() throws Exception {
        byte[] body = gzip(RESPONSE.getBytes(StandardCharsets.UTF_8));
        serve("/x-gzip", "x-gzip", body);

        assertThat(fetch("/x-gzip")).isEqualTo(RESPONSE);
    }

    @Test
    void decodesCommaSeparatedEncodingsInReverseOrder() throws Exception {
        byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
        body = gzip(body);
        body = deflate(body);

        serve("/multiple", " GZip , DeFlAtE ", body);

        assertThat(fetch("/multiple")).isEqualTo(RESPONSE);
    }

    @Test
    void acceptsIdentityEncoding() throws Exception {
        byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
        serve("/identity", "identity", body);

        assertThat(fetch("/identity")).isEqualTo(RESPONSE);
    }

    @Test
    void acceptsResponsesWithoutContentEncoding() throws Exception {
        byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
        serve("/plain", null, body);

        assertThat(fetch("/plain")).isEqualTo(RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"br", "zstd"})
    void rejectsUnsupportedEncodings(String encoding) {
        byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
        serve("/unsupported", encoding, body);

        assertThatThrownBy(() -> fetch("/unsupported"))
                .hasStackTraceContaining("Unsupported Content-Encoding: " + encoding);
    }
}
