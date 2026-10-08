package com.spotlink.advisor.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.tool.annotation.Tool;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.*;

/** 真实 SDK + 本地 HTTP 替身，不调用任何真实模型或供应商。 */
class AdvisorModelClientFactoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Queue<String> responses = new ConcurrentLinkedQueue<>();
    private final Queue<JsonNode> requests = new ConcurrentLinkedQueue<>();
    private final Queue<String> paths = new ConcurrentLinkedQueue<>();
    private final Queue<String> auth = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private ExecutorService executor;
    private int status;
    private long delay;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        status = 200;
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(json.readTree(exchange.getRequestBody()));
            try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String response = responses.poll();
            byte[] body = (response == null ? completion("测试连接正常。") : response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    private AdvisorModelSettings config(String suffix, String key, String model, String tokenParameter, int timeout) {
        return new AdvisorModelSettings(true, "http://127.0.0.1:"+server.getAddress().getPort()+suffix,
                model, key, 128, timeout, tokenParameter);
    }
    private String completion(String answer) {
        return """
                {"id":"offline","object":"chat.completion","created":1,"model":"test-model",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"%s"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                """.formatted(answer);
    }

    @Test void rootAndV1UrlsUseExactlyOneV1AndClientsKeepTheirOwnCredentials() {
        var factory = new AdvisorModelClientFactory();
        var first = factory.create(config("", "offline-key-A", "model-A", "max_tokens", 5));
        var second = factory.create(config("/v1", "offline-key-B", "model-B", "max_completion_tokens", 5));
        assertThat(second.prompt().user("连接测试").call().content()).isEqualTo("测试连接正常。");
        assertThat(first.prompt().user("连接测试").call().content()).isEqualTo("测试连接正常。");
        assertThat(paths).containsExactly("/v1/chat/completions", "/v1/chat/completions");
        assertThat(auth).containsExactly("Bearer offline-key-B", "Bearer offline-key-A");
        JsonNode requestB = requests.poll(), requestA = requests.poll();
        assertThat(requestB.path("model").asText()).isEqualTo("model-B");
        assertThat(requestB.path("max_completion_tokens").asInt()).isEqualTo(128);
        assertThat(requestB.has("max_tokens")).isFalse();
        assertThat(requestA.path("model").asText()).isEqualTo("model-A");
        assertThat(requestA.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(requestA.toString()).doesNotContain("offline-key-A");
    }

    @Test void toolsAreActuallyExecutedAndReturnedToTheProviderBeforeTheFinalAnswer() {
        responses.add("""
                {"id":"offline-tool","object":"chat.completion","created":1,"model":"test-model",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call-1","type":"function","function":{"name":"offline_probe","arguments":"{}"}}]}}]}
                """);
        responses.add(completion("工具调用正常。"));
        Probe probe = new Probe();
        String answer = new AdvisorModelClientFactory().create(config("/v1", "offline-key", "model", "max_tokens", 5))
                .prompt().user("请调用工具").tools(probe).call().content();
        assertThat(answer).isEqualTo("工具调用正常。");
        assertThat(probe.calls).isEqualTo(1);
        assertThat(requests).hasSize(2);
        assertThat(requests.poll().path("tools").toString()).contains("offline_probe");
        JsonNode followup = requests.poll();
        assertThat(followup.path("messages").toString()).contains("tool", "call-1", "probe-result");
    }

    @Test void unconfiguredOrDisabledDoesNotSendAnHttpRequest() {
        var factory = new AdvisorModelClientFactory();
        assertThatThrownBy(() -> factory.create(config("", "", "model", "max_tokens", 5)))
                .hasMessageContaining("未配置");
        var disabled = new AdvisorModelSettings(false, "http://127.0.0.1", "model", "offline-key", 128, 5, "max_tokens");
        assertThatThrownBy(() -> factory.create(disabled)).hasMessageContaining("未启用");
        assertThat(requests).isEmpty();
    }

    @Test void upstreamEchoedSecretsAreStrippedAndAnErrorIsNotRetried() {
        status = 401;
        responses.add("{\"error\":\"Echo offline-secret-canary\"}");
        assertThatThrownBy(() -> new AdvisorModelClientFactory().create(config("", "offline-secret-canary", "model", "max_tokens", 5))
                .prompt().user("测试").call().content()).hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("offline-secret-canary");
        assertThat(requests).hasSize(1);
    }

    @Test void responseWaitIsBounded() {
        delay = 2200;
        assertThatThrownBy(() -> new AdvisorModelClientFactory().create(config("", "offline-key", "model", "max_tokens", 1))
                .prompt().user("测试").call().content()).isInstanceOf(Exception.class);
        assertThat(requests).hasSize(1);
    }

    @Test void urlCredentialsAndNonHttpUrlsCannotBeSaved() {
        for (String invalid : java.util.List.of("file:///etc/passwd", "https://user:pass@domain.com/v1", "https://domain.com?key=value", "https://domain.com#fragment")) {
            assertThatThrownBy(() -> AdvisorModelSettingsService.validateUrl(invalid)).hasMessageContaining("API 地址");
        }
        assertThat(AdvisorModelSettingsService.validateUrl(" http://ollama:11434/v1/ ")).isEqualTo("http://ollama:11434/v1");
    }

    public static class Probe {
        int calls;
        @Tool(name="offline_probe",description="固定离线测试") public String probe() { calls++; return "probe-result"; }
    }
}
