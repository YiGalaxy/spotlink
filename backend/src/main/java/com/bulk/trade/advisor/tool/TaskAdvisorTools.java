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
 * 一次就告诉调用方，跨所有模块，有哪些事等着他做。
 *
 * <p><b>为什么是一个工具，而不是四个。</b>「我还有什么要办的」是人们真正会问的问题，
 * 而这个问题并不知道答案住在哪个模块里。手工回答它，就意味着模型必须记得先查订单、
 * 再查合同、再查挂牌——而一个漏掉某一步的模型，会给出一个自信、听起来完整、却是错的
 * 答案。这是这里最糟的失败模式，所以收集做成了一次调用，忘不掉。
 *
 * <p><b>它什么也不判断。</b>下一步该谁动是一条规则，规则应该待在能被测试的代码里，
 * 而不是待在一段可以被转述的提示词里——所以判断归 {@link TaskService}，这个类只负责
 * 把它渲染出来。Web 后台读的是同一个 service，这正是助手和页面不会对「什么是待办」
 * 各说各话的原因。
 */
@Component
@RequiredArgsConstructor
public class TaskAdvisorTools {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TaskService taskService;

    @Tool(name = "list_my_tasks",
            description = """
                    调用方当前需要处理的一切，从每个模块汇总而来：等他答复的摘牌、
                    等他签署的合同、可以签约、交收或完成的订单。每一项都写明下一步做什么。
                    凡是「我有什么要处理的」「还有多少订单没处理」「有什么待办」
                    「有什么等我做」这类问题，都用这个——不要用各模块的列表工具。
                    确实没有待办时，它会给出一个空手而归的回答。""")
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
