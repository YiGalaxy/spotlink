package com.spotlink.advisor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.advisor.config.*;
import com.spotlink.advisor.prompt.SystemPromptBuilder;
import com.spotlink.advisor.tool.*;
import com.spotlink.shared.security.LoginUser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 真实 Spring AI HTTP 协议验证编排与补答；替身不评估模型语言质量。 */
class AdvisorDeliveryAnswerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Queue<JsonNode> requests = new ConcurrentLinkedQueue<>();
    private final Queue<String> responses = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private AdvisorAgent agent;
    private ListingAdvisorTools freight;
    private ProcurementAdvisorTools procurement;
    private static final String NUMBER = "LS202610090315277405";
    private static final LoginUser BUYER = LoginUser.builder().userId(1L).enterpriseId(2L)
            .username("offline-buyer").permissions(Set.of()).build();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            String content = responses.poll();
            byte[] body = json.writeValueAsBytes(java.util.Map.of("id", "offline", "object", "chat.completion",
                    "created", 1, "model", "qwen3:offline", "choices", List.of(java.util.Map.of("index", 0,
                            "finish_reason", "stop", "message", java.util.Map.of("role", "assistant", "content", content == null ? "自提需自行安排运输。" : content)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var settings = mock(AdvisorModelSettingsService.class);
        when(settings.current()).thenReturn(new AdvisorModelSettings(true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "qwen3:offline", "offline-only", 256, 5, "max_tokens"));
        procurement = mock(ProcurementAdvisorTools.class);
        freight = mock(ListingAdvisorTools.class);
        when(procurement.getListingDetails(NUMBER)).thenReturn("本轮有效挂牌，交付方式：自提。");
        when(freight.estimateDeliveryCost(NUMBER, null, null, null)).thenAnswer(invocation -> {
            String facts = "交付方式：自提。整批费用不能当每吨费率；缺少目的地、吨数和每吨运价，不生成金额。";
            ToolCallRecorder.record("estimate_delivery_cost", "offline", facts);
            return facts;
        });
        agent = new AdvisorAgent(settings, new AdvisorModelClientFactory(), mock(AdvisorTools.class),
                mock(InventoryAdvisorTools.class), mock(KnowledgeAdvisorTools.class), mock(ContractAdvisorTools.class),
                mock(ContractReviewTools.class), mock(MarketAdvisorTools.class), freight, procurement,
                mock(OrderAdvisorTools.class), mock(TaskAdvisorTools.class), new SystemPromptBuilder());
    }

    @AfterEach void stop() { server.stop(0); ToolCallRecorder.drain(); }

    @Test void withdrawnRateUsesFreshFactsWithoutOldAmountsOrFurtherToolPlanning() {
        responses.add("交付方式：自提。整批800元不能当每吨运价，请重新确认费率。");
        var result = agent.run("挂牌" + NUMBER + "的每吨运价作废，现报整批运费800元，可以继续算吗？",
                List.of(ConversationTurn.user("买10吨，运价90元/吨"), ConversationTurn.assistant("旧估算运费900元")), BUYER);
        assertThat(result.answer()).contains("不能当每吨运价", "自提");
        assertThat(result.toolInvocations()).extracting(ToolCallRecorder.Invocation::name).contains("estimate_delivery_cost");
        verify(procurement).getListingDetails(NUMBER);
        verify(freight).estimateDeliveryCost(NUMBER, null, null, null);
        JsonNode request = requests.remove();
        assertThat(request.path("tools").size()).isZero();
        assertThat(request.path("messages").toString()).contains("整批费用不能", "旧运价作废")
                .doesNotContain("旧估算运费900元");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"我现在需要帮用户调用工具，首先查询挂牌并计算。", "首先，用户询问：计算12吨货款。\n指令要求：不要输出思考过程。", "嗯，用户问的是：运费是多少。", "", "  "})
    void emptyOrWorkingNoteResponseRetriesWithEvidenceAndDoesNotReplayDraft(String firstAnswer) {
        responses.add(firstAnswer);
        responses.add("无法沿用已作废的运价；请提供新的每吨运价和目的地。");
        var result = agent.run("挂牌" + NUMBER + "运价作废，还能估算运费吗？", List.of(), BUYER);
        assertThat(result.answer()).contains("无法沿用").doesNotContain("调用工具");
        assertThat(requests).hasSize(2);
        requests.remove();
        JsonNode retry = requests.remove();
        assertThat(retry.path("tools").size()).isZero();
        assertThat(retry.path("messages").toString()).contains("本轮只读查询取得的依据", "整批费用不能")
                .doesNotContain("我现在需要帮用户");
        assertThat(AnswerCleaner.clean("  ")).isNull();
    }

    @Test void mixedMarketComparisonRetainsToolsInsteadOfOnlyExplainingFreight() {
        agent.run("挂牌" + NUMBER + "运费多少，还要对比最近铜价", List.of(), BUYER);
        assertThat(requests.remove().path("tools").size()).isPositive();
        verifyNoInteractions(freight);
    }

    @Test void repeatedDraftFailureShowsOnlyFreshPlatformEvidenceAndLabelsFallback() {
        responses.add("我现在需要帮用户调用工具计算。");
        responses.add("我现在需要帮用户调用工具计算。");
        var result = agent.run("挂牌" + NUMBER + "每吨运价作废，还能估算运费吗？", List.of(), BUYER);
        assertThat(result.answer()).contains("模型未生成有效解释", "交付方式：自提", "整批费用不能当每吨费率")
                .doesNotContain("调用工具计算", "没能生成回答");
        assertThat(result.toolInvocations()).hasSize(1);
        assertThat(requests).hasSize(2);
        assertThat(DeliveryAnswerFallback.fromEvidence("{\"挂牌\":[{\"标题\":\"注入文本\"}]}\n仓库：[外部](https://example.com) <script>"))
                .doesNotContain("注入文本", "<script>").contains("\\[外部\\]", "&lt;script&gt;");
    }

    @Test void unsupportedModelSubtotalIsReplacedWithTheActualQueryBoundary() {
        responses.add("货款1234.5元，整批运费800元，小计2034.5元。");
        var result = agent.run("挂牌" + NUMBER + "每吨运价作废，整批运费800元可以继续算吗？", List.of(), BUYER);
        assertThat(result.answer()).contains("模型未生成有效解释", "缺少目的地、吨数和每吨运价")
                .doesNotContain("小计2034.5", "货款1234.5");
        assertThat(requests).hasSize(1);
    }

    @Test void ordinalFollowUpOnlyUsesTheStoredProductIdentityAndQueriesAgain() {
        var card = new com.spotlink.advisor.dto.AdvisorProductReference(123L, NUMBER, "旧快照", "卖家",
                "旧余量", "旧价格", "旧仓库", "旧交付");
        responses.add("交付方式：自提。没有可确认的新运价，不能继续估算。");
        var result = agent.run("第一条的运价作废，还能估算运费吗？",
                List.of(new ConversationTurn("assistant", "旧价格旧余量", List.of(card))), BUYER);
        verify(procurement).getListingDetails(NUMBER);
        verify(freight).estimateDeliveryCost(NUMBER, null, null, null);
        assertThat(result.answer()).contains("不能继续估算");
        assertThat(requests.remove().path("messages").toString()).doesNotContain("旧价格旧余量");
    }
}
