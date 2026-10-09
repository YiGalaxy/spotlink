package com.spotlink.advisor.langchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.advisor.agent.*;
import com.spotlink.advisor.config.AdvisorModelSettings;
import com.spotlink.advisor.config.AdvisorModelSettingsService;
import com.spotlink.advisor.dto.AdvisorKnowledgeReference;
import com.spotlink.advisor.dto.AdvisorProductReference;
import com.spotlink.advisor.mapper.ConversationMapper;
import com.spotlink.advisor.prompt.SystemPromptBuilder;
import com.spotlink.advisor.tool.ToolCallRecorder;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.*;
import com.spotlink.shared.web.ResultCode;
import io.jsonwebtoken.Jwts;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 独立引擎的受限委托。运行状态仅驻留本实例；重启即撤销所有未完成凭证。 */
@Service
public class LangChainGateway {
    private final KeyPair signing = Jwts.SIG.RS256.keyPair().build();
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final AdvisorAgent agent;
    private final AdvisorModelSettingsService settings;
    private final SystemPromptBuilder prompts;
    private final UserAuthorityProvider authorities;
    private final ConversationMapper conversations;
    private final ObjectMapper json;
    private final String engineUrl;

    public LangChainGateway(AdvisorAgent agent, AdvisorModelSettingsService settings, SystemPromptBuilder prompts,
                           UserAuthorityProvider authorities, ConversationMapper conversations, ObjectMapper json,
                           @Value("${bulk.langchain.url:http://advisor-langchain:8090}") String engineUrl) {
        this.agent = agent; this.settings = settings; this.prompts = prompts;
        this.authorities = authorities; this.conversations = conversations; this.json = json; this.engineUrl = engineUrl;
    }

    public record ToolSpec(String name, String description, JsonNode parameters) {}
    public record ToolReply(String output, List<AdvisorKnowledgeReference> knowledge) {}
    public record EngineReply(String answer, Integer iterations, Integer inputTokens, Integer outputTokens) {}

    static final class Run {
        final String id = UUID.randomUUID().toString();
        final Instant expires = Instant.now().plusSeconds(600);
        final Long conversationId;
        final LoginUser user;
        final Set<String> permissions;
        final AdvisorModelSettings model;
        final Map<String, ToolCallback> tools = new LinkedHashMap<>();
        final String requestData;
        final List<ToolCallRecorder.Invocation> calls = new ArrayList<>();
        final Map<Long, AdvisorProductReference> products = new LinkedHashMap<>();
        final Map<Long, AdvisorKnowledgeReference> knowledge = new LinkedHashMap<>();
        int toolCalls, modelCalls, tokenCount, inputTokens, outputTokens;
        boolean usageKnown = true;
        boolean cancelled;
        Run(Long conversationId, LoginUser user, AdvisorModelSettings model, String requestData) {
            this.conversationId = conversationId; this.user = user; this.model = model;
            this.requestData = requestData; this.permissions = Set.copyOf(user.getPermissions());
        }
    }

    public Map<String, String> publicKey() {
        return Map.of("algorithm", "RS256", "key", Base64.getEncoder().encodeToString(signing.getPublic().getEncoded()));
    }

    private String credential(Run run, String audience) {
        return Jwts.builder().issuer("spotlink-advisor").subject(run.id).audience().add(audience).and()
                .claim("purpose", "advisor-run").claim("conversationId", run.conversationId.toString())
                .claim("userId", run.user.getUserId().toString())
                .claim("enterpriseId", Objects.toString(run.user.getEnterpriseId(), "platform"))
                .issuedAt(Date.from(Instant.now())).expiration(Date.from(run.expires))
                .signWith(signing.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private Run authorized(String bearer) {
        try {
            if (bearer == null || !bearer.startsWith("Bearer ")) throw new IllegalArgumentException();
            var claims = Jwts.parser().verifyWith(signing.getPublic()).requireIssuer("spotlink-advisor")
                    .requireAudience("advisor-tools-model").require("purpose", "advisor-run").build()
                    .parseSignedClaims(bearer.substring(7)).getPayload();
            Run run = runs.get(claims.getSubject());
            if (run == null) throw new IllegalArgumentException();
            validate(run);
            return run;
        } catch (BusinessException failure) { throw failure; }
        catch (Exception failure) { throw denied(); }
    }

    public void authorizeCallback(String bearer) { authorized(bearer); }

    private LoginUser validate(Run run) {
        if (run.cancelled || runs.get(run.id) != run || Instant.now().isAfter(run.expires)) throw denied();
        var identity = authorities.currentIdentity(run.user.getUserId());
        var authority = authorities.load(run.user.getUserId());
        var conversation = conversations.selectById(run.conversationId);
        if (identity == null || authority == null || !authority.isActive()
                || !Objects.equals(identity.getEnterpriseId(), run.user.getEnterpriseId())
                || !Objects.equals(identity.getUserType(), run.user.getUserType())
                || !Objects.equals(identity.getUsername(), run.user.getUsername())
                || !run.permissions.equals(authority.permissions())
                || conversation == null || !Objects.equals(conversation.getUserId(), run.user.getUserId())
                || !Objects.equals(conversation.getEnterpriseId(), run.user.getEnterpriseId())
                || !"langchain".equals(conversation.getEngine())) throw denied();
        return identity.withAuthority(authority);
    }

    public AgentResult run(Long conversationId, String text, List<ConversationTurn> history, LoginUser user, String note) {
        String local = AdvisorInputPolicy.localReply(text);
        if (local != null) return AgentResult.of(local, List.of(), null, null);
        var model = settings.current();
        if (!model.enabled() || !model.configured()) throw unavailable();
        AdvisorModelSettingsService.validateUrl(model.baseUrl());
        Run run = new Run(conversationId, user, model, Objects.toString(note, "") + "\n"
                + history.stream().filter(t -> "user".equals(t.role())).map(ConversationTurn::content)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n" + text);
        for (var tool : ToolCallbacks.from(agent.toolsFor(text).toArray())) run.tools.put(tool.getToolDefinition().name(), tool);
        runs.put(run.id, run);
        try {
            validate(run);
            var specs = new ArrayList<ToolSpec>();
            for (var tool : run.tools.values()) {
                var definition = tool.getToolDefinition();
                specs.add(new ToolSpec(definition.name(), definition.description(), json.readTree(definition.inputSchema())));
            }
            var request = Map.of("runId", run.id, "conversationId", conversationId.toString(),
                    "callbackToken", credential(run, "advisor-tools-model"), "model", model.model(),
                    "system", prompts.stablePrefix() + "\n" + prompts.callerSection(user), "message", text,
                    "history", ConversationContext.bounded(history, model.model().startsWith("qwen3") ? 2500 : 10000),
                    "contextNote", Objects.toString(note, ""), "tools", specs);
            var reply = client(590).post().uri(engineUrl + "/run")
                    .header("Authorization", "Bearer " + credential(run, "langchain-engine"))
                    .body(request).retrieve().body(EngineReply.class);
            validate(run);
            if (reply == null || reply.iterations() == null || reply.iterations() < 1 || reply.iterations() > 6) throw unavailable();
            String answer = AnswerCleaner.clean(reply.answer());
            if (answer == null || answer.isBlank()) throw unavailable();
            synchronized (run) {
                return new AgentResult(answer.substring(0, Math.min(answer.length(), 12000)), List.copyOf(run.calls),
                        run.usageKnown ? run.inputTokens : null, run.usageKnown ? run.outputTokens : null, List.copyOf(run.products.values()),
                        List.copyOf(run.knowledge.values()), reply.iterations(), run.id, model.model());
            }
        } catch (BusinessException failure) { throw failure; }
        catch (Exception failure) { throw unavailable(); }
        finally { synchronized (run) { run.cancelled = true; runs.remove(run.id); } }
    }

    public void cancel(Long conversationId, Long userId) {
        runs.values().stream().filter(r -> r.conversationId.equals(conversationId) && r.user.getUserId().equals(userId))
                .forEach(r -> {
                    synchronized (r) { r.cancelled = true; runs.remove(r.id); }
                    try { client(2).post().uri(engineUrl + "/cancel")
                            .header("Authorization", "Bearer " + credential(r, "langchain-engine"))
                            .body(Map.of("runId", r.id)).retrieve().toBodilessEntity(); }
                    catch (Exception ignored) { /* 凭证已经撤销，即使引擎失联也不能再查询或保存回答。 */ }
                });
    }

    public ToolReply callTool(String bearer, String name, JsonNode arguments) {
        Run run = authorized(bearer);
        synchronized (run) {
            var user = validate(run);
            ToolCallback callback = run.tools.get(name);
            if (callback == null || arguments == null || !arguments.isObject() || arguments.toString().length() > 4000
                    || ++run.toolCalls > 10) throw denied();
            var previous = SecurityContextHolder.getContext();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
            SecurityContextHolder.setContext(context);
            ToolCallRecorder.begin(run.model.model().startsWith("qwen3"));
            ToolCallRecorder.requestData(run.requestData);
            try {
                String output = callback.call(arguments.toString());
                try { var value = json.readTree(output); if (value.isTextual()) output = value.asText(); }
                catch (Exception ignored) { /* 非 JSON 工具结果原样保留。 */ }
                validate(run);
                ToolCallRecorder.products().forEach(p -> { if (run.products.size() < 12) run.products.put(p.id(), p); });
                var references = ToolCallRecorder.knowledge();
                references.forEach(k -> { if (run.knowledge.size() < 8) run.knowledge.put(k.chunkId(), k); });
                return new ToolReply(output, references);
            } finally {
                run.calls.addAll(ToolCallRecorder.drain());
                SecurityContextHolder.setContext(previous);
            }
        }
    }

    /** 仅转发非流式 OpenAI 兼容请求；不执行 Spring AI Agent。所有出站配置取运行快照。 */
    public JsonNode complete(String bearer, JsonNode body) {
        Run run = authorized(bearer);
        synchronized (run) {
            validate(run);
            if (!body.isObject() || body.toString().length() > 64000 || !body.path("messages").isArray()
                    || body.path("messages").size() > 48 || body.path("messages").isEmpty()
                    || body.path("stream").asBoolean(false) || ++run.modelCalls > 6 || run.tokenCount >= 65536) throw denied();
        }
        var safe = json.createObjectNode();
        safe.put("model", run.model.model()); safe.put("stream", false);
        safe.set("messages", body.get("messages"));
        safe.put(run.model.tokenParameter(), Math.min(run.model.maxTokens(), 8192));
        if (body.has("tools")) {
            if (!body.get("tools").isArray() || body.get("tools").size() > run.tools.size()) throw denied();
            var definitions = json.createArrayNode();
            for (var tool : body.get("tools")) {
                var callback = run.tools.get(tool.path("function").path("name").asText());
                if (callback == null) throw denied();
                var function = json.createObjectNode();
                function.put("name", callback.getToolDefinition().name());
                function.put("description", callback.getToolDefinition().description());
                try { function.set("parameters", json.readTree(callback.getToolDefinition().inputSchema())); }
                catch (Exception failure) { throw unavailable(); }
                definitions.add(json.createObjectNode().put("type", "function").set("function", function));
            }
            safe.set("tools", definitions); safe.put("tool_choice", "auto");
        }
        JsonNode response = client(run.model.timeoutSeconds()).post()
                .uri(run.model.baseUrl().replaceAll("/$", "") + run.model.completionsPath())
                .header("Authorization", "Bearer " + run.model.apiKey()).body(safe).retrieve().body(JsonNode.class);
        synchronized (run) {
            validate(run);
            if (response == null || response.toString().length() > 128000) throw unavailable();
            var usage = response.path("usage");
            if (usage.path("prompt_tokens").isIntegralNumber() && usage.path("completion_tokens").isIntegralNumber()) {
                run.inputTokens += Math.max(0, Math.min(131072, usage.path("prompt_tokens").asInt()));
                run.outputTokens += Math.max(0, Math.min(131072, usage.path("completion_tokens").asInt()));
                run.tokenCount = run.inputTokens + run.outputTokens;
            } else run.usageKnown = false;
        }
        return response;
    }

    public boolean ready() {
        try { var health = client(2).get().uri(engineUrl + "/health").retrieve().body(JsonNode.class);
            return health != null && "UP".equals(health.path("status").asText()); }
        catch (Exception failure) { return false; }
    }

    private RestClient client(int timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(timeout));
        return RestClient.builder().requestFactory(factory).defaultStatusHandler(status -> status.value() >= 300,
                (request, response) -> { throw unavailable(); }).build();
    }
    private static BusinessException denied() { return BusinessException.of(ResultCode.ADVISOR_TOOL_NOT_PERMITTED, "本轮委托无效、已结束或超出查询预算"); }
    private static BusinessException unavailable() { return BusinessException.of(ResultCode.ADVISOR_UNAVAILABLE, "LangChain 服务调用失败，请检查引擎与模型配置后重试"); }
}
