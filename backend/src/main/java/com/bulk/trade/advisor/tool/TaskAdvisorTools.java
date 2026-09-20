package com.bulk.trade.advisor.tool;

import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.trading.dto.TaskView;
import com.bulk.trade.trading.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Tells the caller what they have to act on, across every module at once.
 *
 * <p><b>Why one tool and not four.</b> "我还有什么要处理的" is the question people
 * actually ask, and it does not know which module the answer lives in.
 * Answering it by hand means the model must remember to check orders, then
 * contracts, then listings — and a model that forgets one step produces a
 * confident, complete-sounding, wrong answer. That is the worst failure mode
 * available here, so the gathering is one call that cannot forget.
 *
 * <p><b>It also does not decide anything.</b> Which party owes the next move is
 * a rule, and rules belong in code that can be tested rather than in a prompt
 * that can be paraphrased — so {@link TaskService} owns the judgement and this
 * class only renders it. The web console reads the same service, which is what
 * keeps the assistant and the screen from disagreeing about what is pending.
 */
@Component
@RequiredArgsConstructor
public class TaskAdvisorTools {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TaskService taskService;

    @Tool(name = "list_my_tasks",
            description = """
                    Everything the caller currently has to act on, gathered from every module:
                    acceptances waiting for their answer, contracts waiting for their signature,
                    orders ready to be contracted, delivered or completed. Each item names what
                    to do next. Use this — not the per-module list tools — for anything like
                    "我有什么要处理的", "还有多少订单没处理", "有什么待办", "有什么等我做".
                    Returns an empty-handed answer when there is genuinely nothing pending.""")
    public String listMyTasks() {
        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，没有待办事项。";
        }

        List<TaskView> tasks = taskService.findTasks(enterpriseId);
        if (tasks.isEmpty()) {
            return "当前没有需要你处理的事项。进行中的订单要么在等对方，要么已经完结。";
        }

        StringBuilder sb = new StringBuilder("待处理事项共 ").append(tasks.size()).append(" 项：\n");
        for (TaskView task : tasks) {
            sb.append("- ").append(task.action()).append("：")
              .append(task.commodityName());
            if (task.quantity() != null) {
                sb.append(' ').append(plain(task.quantity())).append(' ').append(task.unit());
            }
            sb.append("（").append(task.targetNo()).append("）")
              .append(" — 对手 ").append(task.counterparty());
            if (task.amount() != null) {
                sb.append("，金额 ").append(plain(task.amount())).append(" 元");
            }
            sb.append("，").append(task.detail());
            if (task.deadline() != null) {
                sb.append("，截止 ").append(task.deadline().format(DATE));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
