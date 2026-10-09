package com.spotlink.advisor.langchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.advisor.agent.AdvisorAgent;
import com.spotlink.advisor.config.AdvisorModelSettings;
import com.spotlink.advisor.config.AdvisorModelSettingsService;
import com.spotlink.advisor.entity.Conversation;
import com.spotlink.advisor.mapper.ConversationMapper;
import com.spotlink.advisor.prompt.SystemPromptBuilder;
import com.spotlink.advisor.tool.ToolCallRecorder;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.tool.annotation.Tool;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LangChainGatewayTest {
    final ObjectMapper json = new ObjectMapper();
    final UserAuthorityProvider authority = mock(UserAuthorityProvider.class);
    final ConversationMapper conversations = mock(ConversationMapper.class);
    final AdvisorModelSettingsService settings = mock(AdvisorModelSettingsService.class);
    final AdvisorAgent agent = mock(AdvisorAgent.class);
    final LoginUser user = LoginUser.builder().userId(9L).enterpriseId(18L).username("buyer")
            .userType(1).status(1).permissions(Set.of("read")).build();
    HttpServer server;
    LangChainGateway gateway;
    String url;
    JsonNode modelRequest;

    public static class Probe {
        @Tool(name = "read_identity", description = "读取当前身份")
        public String read() {
            String output = SecurityUtils.currentUser().getEnterpriseId().toString();
            ToolCallRecorder.record("read_identity", "{}", output);
            return output;
        }
    }

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        gateway = new LangChainGateway(agent, settings, new SystemPromptBuilder(), authority, conversations, json, url);
        when(settings.current()).thenReturn(new AdvisorModelSettings(true, url + "/v1", "configured-model", "server-only-key", 1024, 5, "max_tokens"));
        when(authority.currentIdentity(9L)).thenReturn(user);
        when(authority.load(9L)).thenReturn(UserAuthority.of(1, Set.of("read")));
        Conversation row = new Conversation(); row.setId(123L); row.setUserId(9L); row.setEnterpriseId(18L); row.setEngine("langchain");
        when(conversations.selectById(123L)).thenReturn(row);
        when(agent.toolsFor(anyString())).thenReturn(List.of(new Probe()));
        server.createContext("/cancel", exchange -> {
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes()); exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            modelRequest = json.readTree(exchange.getRequestBody());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer server-only-key");
            byte[] body = "{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6}}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    com.spotlink.advisor.agent.AgentResult invoke(Consumer<JsonNode> engine) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        server.createContext("/run", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            try { engine.accept(request); }
            catch (Throwable e) { failure.set(e); }
            byte[] body = "{\"answer\":\"已查询本企业真实数据。\",\"iterations\":2,\"inputTokens\":999999,\"outputTokens\":999999}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        try { return gateway.run(123L, "查询本企业资料", List.of(), user, null); }
        finally { if (failure.get() != null) throw new AssertionError(failure.get()); }
    }
    String bearer(JsonNode request) { return "Bearer " + request.path("callbackToken").asText(); }
    JsonNode completion() { return json.createObjectNode().put("model", "injected-model").set("messages", json.createArrayNode().add(json.createObjectNode().put("role", "user").put("content", "查货"))); }

    @Test void callbackRestoresTenantAndFinishedTokenCannotBeReplayed() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        var result = invoke(request -> {
            token.set(bearer(request));
            assertThat(request.toString()).doesNotContain("server-only-key");
            assertThat(gateway.callTool(token.get(), "read_identity", json.createObjectNode()).output()).isEqualTo("18");
            assertThat(SecurityUtils.currentUserOrNull()).isNull();
        });
        assertThat(result.toolInvocations()).hasSize(1);
        assertThatThrownBy(() -> gateway.callTool(token.get(), "read_identity", json.createObjectNode())).isInstanceOf(BusinessException.class);
    }
    @Test void loginTokensUnknownToolsAndToolBudgetAreRejected() throws Exception {
        invoke(request -> {
            assertThatThrownBy(() -> gateway.callTool("Bearer login-token", "read_identity", json.createObjectNode())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> gateway.callTool(bearer(request), "execute_sql", json.createObjectNode())).isInstanceOf(BusinessException.class);
            for (int i = 0; i < 10; i++) gateway.callTool(bearer(request), "read_identity", json.createObjectNode());
            assertThatThrownBy(() -> gateway.callTool(bearer(request), "read_identity", json.createObjectNode())).isInstanceOf(BusinessException.class);
        });
    }
    @Test void proxyUsesConfiguredModelAndTrustedUsageAndEnforcesCallBudget() throws Exception {
        var result = invoke(request -> {
            for (int i = 0; i < 6; i++) gateway.complete(bearer(request), completion());
            assertThat(modelRequest.path("model").asText()).isEqualTo("configured-model");
            assertThat(modelRequest.path("max_tokens").asInt()).isEqualTo(1024);
            assertThatThrownBy(() -> gateway.complete(bearer(request), completion())).isInstanceOf(BusinessException.class);
        });
        assertThat(result.inputTokens()).isEqualTo(72);
        assertThat(result.outputTokens()).isEqualTo(36);
    }
    @Test void permissionChangeRevokesExistingDelegation() {
        assertThatThrownBy(() -> invoke(request -> {
            when(authority.load(9L)).thenReturn(UserAuthority.of(1, Set.of()));
            assertThatThrownBy(() -> gateway.callTool(bearer(request), "read_identity", json.createObjectNode())).isInstanceOf(BusinessException.class);
        })).isInstanceOf(BusinessException.class);
    }
    @Test void enterpriseMoveRevokesDelegation() {
        assertThatThrownBy(() -> invoke(request -> {
            when(authority.currentIdentity(9L)).thenReturn(LoginUser.builder().userId(9L).username("buyer").userType(1).enterpriseId(19L).build());
            assertThatThrownBy(() -> gateway.callTool(bearer(request), "read_identity", json.createObjectNode())).isInstanceOf(BusinessException.class);
        })).isInstanceOf(BusinessException.class);
    }
    @Test void cancellationAndConversationDeletionDiscardLateResults() {
        assertThatThrownBy(() -> invoke(request -> {
            gateway.cancel(123L, 9L);
            assertThatThrownBy(() -> gateway.complete(bearer(request), completion())).isInstanceOf(BusinessException.class);
        })).isInstanceOf(BusinessException.class);
    }
    @Test void deletedConversationRejectsCallbacks() {
        assertThatThrownBy(() -> invoke(request -> {
            when(conversations.selectById(123L)).thenReturn(null);
            assertThatThrownBy(() -> gateway.callTool(bearer(request), "read_identity", json.createObjectNode())).isInstanceOf(BusinessException.class);
        })).isInstanceOf(BusinessException.class);
    }
    @Test void unreachableEngineDoesNotRunSpringAi() {
        assertThat(gateway.ready()).isFalse();
        assertThatThrownBy(() -> gateway.run(123L, "查询本企业资料", List.of(), user, null)).isInstanceOf(BusinessException.class);
        verify(agent, never()).run(anyString(), anyList(), any(), any());
    }
}
