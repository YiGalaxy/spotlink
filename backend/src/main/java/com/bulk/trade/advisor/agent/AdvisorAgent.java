package com.bulk.trade.advisor.agent;

import com.bulk.trade.advisor.prompt.SystemPromptBuilder;
import com.bulk.trade.advisor.tool.AdvisorTools;
import com.bulk.trade.advisor.tool.ContractAdvisorTools;
import com.bulk.trade.advisor.tool.ContractReviewTools;
import com.bulk.trade.advisor.tool.InventoryAdvisorTools;
import com.bulk.trade.advisor.tool.KnowledgeAdvisorTools;
import com.bulk.trade.advisor.tool.MarketAdvisorTools;
import com.bulk.trade.advisor.tool.OrderAdvisorTools;
import com.bulk.trade.advisor.tool.TaskAdvisorTools;
import com.bulk.trade.advisor.tool.ToolCallRecorder;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one advisor turn through Spring AI.
 *
 * <p>Spring AI owns the tool-calling loop: the prompt goes out, the model
 * requests tools, the framework executes them and feeds the results back, and
 * what returns here is the finished answer. That removes the loop this module
 * used to hand-write — and with it, two things the hand-written loop could do
 * and this one cannot. Both are recorded in {@link AgentResult} rather than
 * quietly dropped.
 *
 * <p>What Spring AI does <em>not</em> remove is the tenant rule. Tools still
 * read the caller's enterprise from the security context, and the tool methods
 * run on this thread, so that context is present when they do.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisorAgent {

    private final ChatClient.Builder chatClientBuilder;
    private final AdvisorTools advisorTools;
    private final InventoryAdvisorTools inventoryAdvisorTools;
    private final KnowledgeAdvisorTools knowledgeAdvisorTools;
    private final ContractAdvisorTools contractAdvisorTools;
    private final ContractReviewTools contractReviewTools;
    private final MarketAdvisorTools marketAdvisorTools;
    private final OrderAdvisorTools orderAdvisorTools;
    private final TaskAdvisorTools taskAdvisorTools;
    private final SystemPromptBuilder promptBuilder;

    /**
     * Every bean whose {@code @Tool} methods this agent can hand to the model.
     *
     * <p><b>One list, three readers.</b> The prompt specification that makes the
     * tools callable, the readiness endpoint that reports what is callable, and
     * the test that asserts what is <em>not</em> callable all read this. Keeping
     * a second list is what let that endpoint advertise nine tools while
     * thirteen were registered — drift that stays invisible precisely because
     * the report is what people trust instead of checking.
     *
     * <p>Adding a tool class is a one-line change in one place, and forgetting
     * it is no longer possible: a tool not in this list is not callable either,
     * so the failure is a missing feature rather than a confident wrong answer
     * about what exists.
     */
    public List<Object> toolBeans() {
        return List.of(advisorTools, inventoryAdvisorTools, knowledgeAdvisorTools,
                contractAdvisorTools, contractReviewTools, marketAdvisorTools,
                orderAdvisorTools, taskAdvisorTools);
    }

    /**
     * The tools available for one turn.
     *
     * <p>Everything except the contract text, which has to be asked for. Every
     * tool result enters the prompt and the prompt leaves for an external
     * provider, so the tool list is the switch that decides what leaves — and
     * a switch that is always on is not a switch.
     *
     * <p>Decided in Java from the user's own words, not by the model: a model
     * that can call a tool will call it whenever it seems useful, and "seems
     * useful" is the judgement this exists to keep out of the loop. See
     * {@link ContractReviewTrigger} for what the rule is and why a crude one is
     * the right shape for it.
     */
    List<Object> toolsFor(String userMessage) {
        List<Object> tools = new ArrayList<>(toolBeans());
        if (!ContractReviewTrigger.requested(userMessage)) {
            tools.remove(contractReviewTools);
        }
        return tools;
    }

    public AgentResult run(String userMessage, List<ConversationTurn> history, LoginUser user) {
        List<Message> messages = new ArrayList<>(history.size() + 1);
        for (ConversationTurn turn : history) {
            messages.add(ConversationTurn.ROLE_USER.equals(turn.role())
                    ? new UserMessage(turn.content())
                    : new AssistantMessage(turn.content()));
        }
        messages.add(new UserMessage(userMessage));

        // Opened before the call: Spring AI executes tools inside it, and the
        // aspect records into whatever slot is open on this thread.
        ToolCallRecorder.begin();
        try {
            ChatResponse response = chatClientBuilder.build()
                    .prompt()
                    // Two separate system blocks, not one concatenated string.
                    // The first is identical for every tenant, so with
                    // SYSTEM_ONLY caching one cache entry serves all callers;
                    // merging them would change the prefix per user.
                    .system(system -> system
                            .text(promptBuilder.stablePrefix())
                            .text(promptBuilder.callerSection(user)))
                    .messages(messages)
                    // Every @Tool method on these beans becomes callable.
                    .tools(toolsFor(userMessage).toArray())
                    .call()
                    .chatResponse();

            return AgentResult.of(
                    AnswerCleaner.clean(extractText(response)),
                    ToolCallRecorder.drain(),
                    promptTokens(response),
                    completionTokens(response));

        } catch (BusinessException e) {
            ToolCallRecorder.drain();
            throw e;
        } catch (Exception e) {
            // The trail is discarded, not recorded: a failed call produced no
            // answer to attach it to.
            ToolCallRecorder.drain();
            log.error("Advisor call failed for user {}", user.getUsername(), e);
            throw BusinessException.of(ResultCode.ADVISOR_UNAVAILABLE,
                    "AI 服务调用失败：" + rootMessage(e));
        }
    }

    /**
     * Pulls the answer out of the response.
     *
     * <p><b>The results list holds one entry per model round-trip, in order, and
     * only the last one is the answer.</b> {@code getResult()} returns the
     * first, so reading it yields a blank answer whenever a tool was used.
     *
     * <p>Taking the first generation that <em>has</em> text is equally wrong.
     * Tool-calling rounds can carry text too — intermediate reasoning such as
     * "Let me also check the enterprise info… The question is just about
     * accounts. Provide answer." — and that would be surfaced to the user as if
     * it were the reply. Walk the whole list and keep the last non-blank text,
     * which is the one produced after the final tool result came back.
     */
    private String extractText(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            log.warn("Advisor returned an empty ChatResponse");
            return "（模型没有返回内容）";
        }

        String answer = null;
        for (var generation : response.getResults()) {
            var output = generation.getOutput();
            if (output == null) {
                continue;
            }
            String text = output.getText();
            if (text != null && !text.isBlank()) {
                answer = text;
            }
        }

        if (answer == null) {
            log.warn("Advisor turn produced {} generation(s) but no text",
                    response.getResults().size());
            return "（模型没有返回内容）";
        }
        return answer;
    }

    private Integer promptTokens(ChatResponse response) {
        Usage usage = usageOf(response);
        return usage == null ? null : usage.getPromptTokens();
    }

    private Integer completionTokens(ChatResponse response) {
        Usage usage = usageOf(response);
        return usage == null ? null : usage.getCompletionTokens();
    }

    private Usage usageOf(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null
                : response.getMetadata().getUsage();
    }

    /** SDK exceptions wrap the useful message one or two levels down. */
    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank()
                ? cursor.getClass().getSimpleName()
                : message;
    }
}
