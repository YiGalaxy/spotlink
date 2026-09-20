package com.bulk.trade.advisor.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.Usage;
import com.bulk.trade.advisor.client.AdvisorClientFactory;
import com.bulk.trade.advisor.config.AdvisorProperties;
import com.bulk.trade.advisor.prompt.SystemPromptBuilder;
import com.bulk.trade.advisor.tool.AdvisorContext;
import com.bulk.trade.advisor.tool.AdvisorTool;
import com.bulk.trade.advisor.tool.ToolRegistry;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.web.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tool-calling loop, written by hand.
 *
 * <p>The SDK ships a {@code BetaToolRunner} that drives this loop automatically,
 * and it is the right tool when your tools are self-contained functions. It is
 * not the right tool here: these tools must run inside the caller's security
 * context and call ordinary Spring services, so every turn needs an
 * authorisation check and an audit record. Owning the loop is what makes that
 * possible.
 *
 * <p>Three things this loop has to get right:
 *
 * <ol>
 *   <li><b>The assistant turn is appended whole.</b> Not just its text — the
 *       {@code tool_use} blocks go back too, so each result can be matched to
 *       the call it answers.</li>
 *   <li><b>All tool results return in a single user message.</b> Splitting them
 *       across several messages discourages the model from issuing parallel
 *       calls, and costs more, not less.</li>
 *   <li><b>The loop has a hard ceiling.</b> Without one, a model that keeps
 *       calling tools spins forever, and every turn is billed.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final AdvisorClientFactory clientFactory;
    private final ToolRegistry toolRegistry;
    private final SystemPromptBuilder promptBuilder;

    /**
     * Runs one user turn to completion.
     *
     * @param history prior turns, oldest first; the conversation service is
     *                responsible for trimming it to the caller's budget
     */
    public AgentResult run(String userMessage,
                           List<ConversationTurn> history,
                           AdvisorContext context) {

        AnthropicClient client = clientFactory.get();
        AdvisorProperties properties = clientFactory.properties();

        List<MessageParam> messages = new ArrayList<>(history.size() + 1);
        for (ConversationTurn turn : history) {
            messages.add(MessageParam.builder()
                    .role(toSdkRole(turn.role()))
                    .content(turn.content())
                    .build());
        }
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userMessage)
                .build());

        List<Tool> sdkTools = buildTools();
        List<AgentResult.ToolInvocation> invocations = new ArrayList<>();

        long inputTokens = 0, outputTokens = 0, cacheReadTokens = 0, cacheCreationTokens = 0;
        int maxIterations = properties.effectiveMaxIterations();

        for (int iteration = 0; iteration < maxIterations; iteration++) {

            MessageCreateParams.Builder requestBuilder = MessageCreateParams.builder()
                    .model(properties.model())
                    .maxTokens(properties.effectiveMaxTokens())
                    .systemOfTextBlockParams(buildSystemBlocks(context))
                    .messages(messages);
            for (Tool tool : sdkTools) {
                requestBuilder.addTool(tool);
            }

            Message response = client.messages().create(requestBuilder.build());

            Usage usage = response.usage();
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            cacheReadTokens += usage.cacheReadInputTokens().orElse(0L);
            cacheCreationTokens += usage.cacheCreationInputTokens().orElse(0L);

            // Append the assistant turn verbatim: tool_use blocks must survive
            // so the tool_result blocks that follow stay linked to them.
            messages.add(response.toParam());

            List<ContentBlock> toolUses = response.content().stream()
                    .filter(block -> block.toolUse().isPresent())
                    .toList();

            if (toolUses.isEmpty()) {
                log.debug("Advisor finished in {} iteration(s); input={}, output={}, cacheRead={}",
                        iteration + 1, inputTokens, outputTokens, cacheReadTokens);
                return AgentResult.of(extractText(response), invocations, iteration + 1,
                        inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens);
            }

            List<ContentBlockParam> toolResults = new ArrayList<>(toolUses.size());
            for (ContentBlock block : toolUses) {
                block.toolUse().ifPresent(toolUse -> {
                    String toolName = toolUse.name();
                    JsonNode toolInput = toolUse._input().convert(JsonNode.class);
                    String output = toolRegistry.execute(toolName, toolInput, context);

                    invocations.add(new AgentResult.ToolInvocation(
                            toolName,
                            toolInput == null ? "{}" : toolInput.toString(),
                            output));

                    // One user message carries every result — see rule 2 above.
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                });
            }

            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());
        }

        log.warn("Advisor hit the {} iteration ceiling for user {}", maxIterations, context.username());
        throw BusinessException.of(ResultCode.ADVISOR_ITERATION_LIMIT);
    }

    /**
     * Two blocks: the cached, tenant-independent rules first, then the caller
     * section. The cache breakpoint sits on the first block, so a change to the
     * caller's identity does not invalidate it.
     */
    private List<TextBlockParam> buildSystemBlocks(AdvisorContext context) {
        return List.of(
                TextBlockParam.builder()
                        .text(promptBuilder.stablePrefix())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                TextBlockParam.builder()
                        .text(promptBuilder.callerSection(context))
                        .build());
    }

    private MessageParam.Role toSdkRole(String role) {
        return ConversationTurn.ROLE_USER.equals(role)
                ? MessageParam.Role.USER
                : MessageParam.Role.ASSISTANT;
    }

    private String extractText(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    /**
     * Converts registered tools to their wire form.
     *
     * <p>Iterates the registry's name-ordered list, never a hash map's key set:
     * the tool list is part of the cached prefix and has to be byte-identical
     * between requests.
     */
    private List<Tool> buildTools() {
        return toolRegistry.all().stream().map(this::toSdkTool).toList();
    }

    private Tool toSdkTool(AdvisorTool tool) {
        Map<String, Object> schema = tool.inputSchema();

        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        if (schema.get("properties") instanceof Map<?, ?> propertyMap) {
            propertyMap.forEach((key, value) ->
                    properties.putAdditionalProperty(String.valueOf(key), JsonValue.from(value)));
        }

        Tool.InputSchema.Builder inputSchema = Tool.InputSchema.builder()
                .properties(properties.build());

        if (schema.get("required") instanceof List<?> required) {
            inputSchema.required(required.stream().map(String::valueOf).toList());
        }

        return Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(inputSchema.build())
                .build();
    }
}
