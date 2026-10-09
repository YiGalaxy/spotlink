package com.spotlink.knowledge;

import com.spotlink.knowledge.service.EmbeddingService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EmbeddingServiceTest {
    @Test void springAiCallsCompatibleEndpointAndReadsTheActualVector() throws Exception {
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.6,0.8]}],\"model\":\"test-embedding\",\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var model = new EmbeddingService(endpoint, "test-embedding", true, 2, "test-only", "1", 2);
            assertThat(model.embed("中文交收规则")).containsExactly(0.6f, 0.8f);
            assertThat(request.get()).contains("test-embedding", "中文交收规则");
            assertThat(new EmbeddingService(endpoint + "/v1", "test-embedding", true, 2, "test-only", "1", 2).embed("规则"))
                    .containsExactly(0.6f, 0.8f);
            assertThat(new EmbeddingService(endpoint, "test-embedding", true, 3, "test-only", "1", 2).embed("错误维度")).isNull();
        } finally { server.stop(0); }
    }
    @Test void indexIdentityChangesWithProviderModelDimensionsAndRevisionButNotSecret() {
        var first = new EmbeddingService("http://localhost:11434", "a", false, 2, "secret1", "1", 2);
        assertThat(first.fingerprint()).isEqualTo(new EmbeddingService("http://localhost:11434/", "a", false, 2, "secret2", "1", 2).fingerprint());
        for (var changed : java.util.List.of(
                new EmbeddingService("http://localhost:11435", "a", false, 2, "", "1", 2),
                new EmbeddingService("http://localhost:11434", "b", false, 2, "", "1", 2),
                new EmbeddingService("http://localhost:11434", "a", false, 3, "", "1", 2),
                new EmbeddingService("http://localhost:11434", "a", false, 2, "", "2", 2))) {
            assertThat(changed.fingerprint()).isNotEqualTo(first.fingerprint());
        }
        assertThat(first.embed("规则")).isNull();
    }
    @Test void rejectsMalformedBinaryAndNonFiniteVectors() {
        assertThat(EmbeddingService.fromBytes(new byte[5])).isEmpty();
        assertThat(EmbeddingService.cosineSimilarity(new float[]{Float.NaN}, new float[]{1})).isZero();
        assertThat(EmbeddingService.cosineSimilarity(new float[]{Float.POSITIVE_INFINITY}, new float[]{1})).isZero();
        assertThat(EmbeddingService.cosineSimilarity(new float[]{0}, new float[]{0})).isZero();
        assertThat(EmbeddingService.fromBytes(EmbeddingService.toBytes(new float[]{1, 0.5f}))).containsExactly(1f, 0.5f);
    }
}
