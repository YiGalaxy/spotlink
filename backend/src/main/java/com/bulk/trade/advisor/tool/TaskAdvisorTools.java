package com.bulk.trade.advisor.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.contract.entity.Contract;
import com.bulk.trade.contract.mapper.ContractMapper;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.bulk.trade.trading.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * What the caller has to act on, across every module at once.
 *
 * <p><b>Why this is one tool and not four.</b> "我还有什么要处理的" is the
 * question people actually ask, and it does not know which module the answer
 * lives in. Answering it by hand means the model must remember to check orders,
 * then contracts, then listings — and a model that forgets one step produces a
 * confident, complete-sounding, wrong answer. That is the worst failure mode
 * available here. One tool that cannot forget is worth more than four that can.
 *
 * <p>The work is done in Java rather than left to the model for the same
 * reason. Which party owes the next move is a rule — a contract awaiting <em>my
 * </em> signature is a task, the same contract awaiting theirs is not — and
 * rules belong in code that can be tested, not in a prompt that can be
 * paraphrased.
 */
@Component
@RequiredArgsConstructor
public class TaskAdvisorTools {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final OrderService orderService;
    private final ContractMapper contractMapper;

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

        List<Order> orders = orderService.listMine(enterpriseId, null);
        List<String> tasks = new ArrayList<>();

        for (Order order : orders) {
            // Only the lister answers a waiting acceptance. The counterparty is
            // waiting too, but waiting is not a task — showing it to them would
            // invent work that does not exist and hide the work that does.
            if (OrderStatus.PENDING_CONFIRM.equals(order.getStatus()) && isLister(order, enterpriseId)) {
                tasks.add(item("确认或拒绝摘牌", order,
                        "对方摘牌 %s %s，等你答复%s".formatted(
                                plain(order.getQuantity()), order.getUnit(),
                                order.getConfirmDeadline() == null
                                        ? ""
                                        : "，截止 " + format(order.getConfirmDeadline()))));
            }
        }

        for (Contract contract : myContracts(enterpriseId)) {
            if (Contract.Status.PENDING_SIGN.equals(contract.getStatus())
                    && !hasSigned(contract, enterpriseId)) {
                tasks.add("· 签署合同：%s（%s，%s 元）".formatted(
                        contract.getContractNo(), contract.getTitle(), plain(contract.getAmount())));
            }
        }

        for (Order order : orders) {
            if (!OrderStatus.CONFIRMED.equals(order.getStatus())) {
                continue;
            }
            if (order.getContractId() == null) {
                tasks.add(item("起草合同", order, "订单已确认，尚未起草合同"));
            }
        }

        for (Order order : orders) {
            if (OrderStatus.CONTRACTED.equals(order.getStatus())) {
                tasks.add(item("开始交收", order, "合同已生效，可以开始交收"));
            }
            if (OrderStatus.DELIVERING.equals(order.getStatus())) {
                tasks.add(item("确认完成", order, "交收进行中，完成后确认"));
            }
        }

        if (tasks.isEmpty()) {
            return "当前没有需要你处理的事项。进行中的订单要么在等对方，要么已经完结。";
        }

        StringBuilder sb = new StringBuilder("待处理事项共 ").append(tasks.size()).append(" 项：\n");
        tasks.forEach(task -> sb.append(task).append('\n'));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private String item(String action, Order order, String detail) {
        return "· %s：%s（%s %s，订单 %s）— %s".formatted(
                action, order.getCommodityName(),
                plain(order.getQuantity()), order.getUnit(),
                order.getOrderNo(), detail);
    }

    /**
     * Whether the caller published the listing this order came from.
     *
     * <p>Read from the order's own columns rather than by loading the listing:
     * an order under a SELL listing has the lister as its seller, and one under
     * a BUY listing has them as its buyer. The rule that makes this correct is
     * written down where it can be checked — {@code Listing.ConfirmMode} — and
     * only MANUAL listings ever leave an order in this state.
     */
    private boolean isLister(Order order, Long enterpriseId) {
        return enterpriseId.equals(order.getSellerId());
    }

    /** Contracts the caller is a party to. */
    private List<Contract> myContracts(Long enterpriseId) {
        return contractMapper.selectList(Wrappers.<Contract>lambdaQuery()
                .and(w -> w.eq(Contract::getBuyerId, enterpriseId)
                        .or()
                        .eq(Contract::getSellerId, enterpriseId))
                .orderByDesc(Contract::getId)
                .last("limit 50"));
    }

    private boolean hasSigned(Contract contract, Long enterpriseId) {
        return contract.isBuyer(enterpriseId)
                ? contract.getBuyerSignedAt() != null
                : contract.getSellerSignedAt() != null;
    }

    private String format(OffsetDateTime time) {
        return time == null ? "—" : time.format(DATE);
    }

    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
