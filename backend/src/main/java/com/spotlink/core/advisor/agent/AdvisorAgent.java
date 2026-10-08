package com.spotlink.advisor.agent;

import com.spotlink.advisor.prompt.SystemPromptBuilder;
import com.spotlink.advisor.tool.AdvisorTools;
import com.spotlink.advisor.tool.ContractAdvisorTools;
import com.spotlink.advisor.tool.ContractReviewTools;
import com.spotlink.advisor.tool.InventoryAdvisorTools;
import com.spotlink.advisor.tool.KnowledgeAdvisorTools;
import com.spotlink.advisor.tool.ListingAdvisorTools;
import com.spotlink.advisor.tool.MarketAdvisorTools;
import com.spotlink.advisor.tool.OrderAdvisorTools;
import com.spotlink.advisor.tool.TaskAdvisorTools;
import com.spotlink.advisor.tool.ToolCallRecorder;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.web.ResultCode;
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
 * 通过 Spring AI 执行一轮顾问对话。
 *
 * <p>工具调用循环由 Spring AI 掌管：提示词发出去，模型请求工具，框架执行工具并把结果
 * 回灌，最后返回这里的就是完整答案。这去掉了本模块原先手写的那个循环 —— 随之也去掉了
 * 手写循环能做、而这个循环做不到的两件事。这两件事都记录在 {@link AgentResult} 里，
 * 而不是被悄悄丢弃。
 *
 * <p>Spring AI <em>没有</em>去掉的是租户规则。工具仍然从安全上下文中读取调用方所属的
 * 企业，而工具方法就在当前线程上执行，所以它们执行时该上下文是存在的。
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
    private final ListingAdvisorTools listingAdvisorTools;
    private final OrderAdvisorTools orderAdvisorTools;
    private final TaskAdvisorTools taskAdvisorTools;
    private final SystemPromptBuilder promptBuilder;

    /**
     * 本 Agent 能交给模型的全部带有 {@code @Tool} 方法的 Bean。
     *
     * <p><b>一份清单，三个读取方。</b>让工具可被调用的提示词规格、报告哪些工具可调用的
     * 就绪探测接口，以及断言哪些工具<em>不</em>可调用的测试，读的都是它。正是维护了第二份
     * 清单，才让那个接口声称有九个工具、而实际注册了十三个 —— 这种漂移之所以一直隐形，
     * 恰恰因为人们信任那份报告而不去核对。
     *
     * <p>新增一个工具类只需在一处改一行，而且再也忘不掉：不在这份清单里的工具同样不可调用，
     * 于是故障表现为少了一个功能，而不是模型自信地给出一个关于有哪些工具的错误答案。
     */
    public List<Object> toolBeans() {
        return List.of(advisorTools, inventoryAdvisorTools, knowledgeAdvisorTools,
                contractAdvisorTools, contractReviewTools, marketAdvisorTools,
                listingAdvisorTools, orderAdvisorTools, taskAdvisorTools);
    }

    /**
     * 一轮对话可用的工具。
     *
     * <p>除合同文本之外的全部工具，合同文本必须先被请求才能用。每个工具结果都会进入提示词，
     * 而提示词会离开本服务、发往外部的模型提供商，所以这份工具清单就是决定什么东西外发的
     * 开关 —— 而一个永远打开的开关不算开关。
     *
     * <p>在 Java 里依据用户自己说的话判断，而不是交给模型判断：一个能调用某工具的方法，
     * 只要它显得有用就会去调用，而显得有用正是这里要挡在决策环路之外的判断。规则本身是什么、
     * 以及为什么粗糙的规则才是它应有的形态，见 {@link ContractReviewTrigger}。
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

        // 在调用之前开启：Spring AI 会在其中执行工具，切面则记录到当前线程上打开的那个槽位。
        ToolCallRecorder.begin();
        try {
            ChatResponse response = chatClientBuilder.build()
                    .prompt()
                    // 两个独立的 system 块，而不是拼接成的一个字符串。第一块对所有租户都完全
                    // 相同，因此在 SYSTEM_ONLY 缓存下一条缓存就能服务所有调用方；把两者合并
                    // 会让每个用户的前缀都不一样。
                    .system(system -> system
                            .text(promptBuilder.stablePrefix())
                            .text(promptBuilder.callerSection(user)))
                    .messages(messages)
                    // 这些 Bean 上的每个 @Tool 方法都会变成可调用的。
                    .tools(toolsFor(userMessage).toArray())
                    .call()
                    .chatResponse();

            String answer = AnswerCleaner.clean(extractText(response));
            if (answer == null) {
                // 模型做完了分析却没有下结论就停住了。出现在需要把多行数据相互权衡的问题上 ——
                // 比如一次合同审查，或者「帮我找便宜的大宗铜」—— 它会写满一页英文的推演过程，
                // 却不给答案。它需要的每个工具结果都已经在对话里了，所以再问一次只花一个来回，
                // 就能把死路变成一次回答。
                log.info("No answer line produced; asking once more without tools");
                answer = AnswerCleaner.clean(retryForAnswer(messages));
            }
            if (answer == null) {
                // 两次就够了。那段文本已被清理器打进日志，所以这是可诊断的，而不是无迹可寻。
                answer = "抱歉，这次没能生成回答。请把问题再发一次，或换个说法。";
            }

            return AgentResult.of(
                    answer,
                    ToolCallRecorder.drain(),
                    promptTokens(response),
                    completionTokens(response));

        } catch (BusinessException e) {
            ToolCallRecorder.drain();
            throw e;
        } catch (Exception e) {
            // 轨迹被丢弃而不是记录：这次调用失败了，没有答案可以挂靠这段轨迹。
            ToolCallRecorder.drain();
            log.error("Advisor call failed for user {}", user.getUsername(), e);
            throw BusinessException.of(ResultCode.ADVISOR_UNAVAILABLE,
                    "AI 服务调用失败：" + rootMessage(e));
        }
    }

    /**
     * 把工具拿走之后重新问一次。
     *
     * <p>拿走工具本身就是目的，而不是一种优化：刚花了一整轮做分析的模型，下一轮很可能又去
     * 调用更多工具，第一次尝试之所以什么都没得出结论正是如此。没有工具可用时它能产出的只有
     * 文本，而对话里已经装着它需要的一切。
     *
     * <p>草稿内容故意<em>不</em>作为 assistant 轮次回灌。把模型自己未完成的推理再喂回去、
     * 让它接着往下写，是得到更多同类内容的好办法。
     */
    private String retryForAnswer(List<Message> messages) {
        List<Message> followUp = new ArrayList<>(messages);
        followUp.add(new UserMessage(
                "请直接给出最终答案。只输出结论和依据，不要输出任何分析过程或思考步骤。"));
        try {
            ChatResponse response = chatClientBuilder.build()
                    .prompt()
                    .messages(followUp)
                    .call()
                    .chatResponse();
            return extractText(response);
        } catch (Exception e) {
            log.warn("Follow-up for a final answer failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从响应里把答案取出来。
     *
     * <p><b>results 列表按顺序、每次模型往返各存一项，只有最后一项才是答案。</b>
     * {@code getResult()} 返回的是第一项，所以只要用过工具，读它拿到的就是空答案。
     *
     * <p>取第一个<em>带</em>文本的 generation 同样是错的。工具调用轮次也可能带文本 ——
     * 例如 "Let me also check the enterprise info… The question is just about
     * accounts. Provide answer." 这类中间推理 —— 而那会被当成正式回复呈现给用户。
     * 正确做法是遍历整个列表、保留最后一段非空文本，那才是最后一个工具结果返回之后产出的。
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

    /** SDK 异常会把有用的信息包在一两层 cause 之下。 */
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
