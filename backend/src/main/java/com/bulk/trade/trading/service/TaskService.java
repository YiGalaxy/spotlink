package com.bulk.trade.trading.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.contract.entity.Contract;
import com.bulk.trade.contract.mapper.ContractMapper;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.trading.dto.TaskView;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what an enterprise currently owes the platform.
 *
 * <p><b>This is the single definition of "pending".</b> Both the web console
 * and the AI advisor read it; neither re-derives it. The reason is that the
 * rule is not a query — it is a judgement about <em>who owes the next move</em>,
 * and it is asymmetric: a contract awaiting my signature is my task, the same
 * contract awaiting theirs is not. Two copies of that judgement would eventually
 * disagree, and a task list that quietly omits something is worse than no task
 * list, because the user stops looking.
 *
 * <p>Nothing here is tenant-parameterised. The caller passes their own
 * enterprise and every underlying query is scoped by it — the same rule as
 * everywhere else, enforced by the fact that there is no way to express the
 * other thing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    /**
     * Most urgent first: the kinds in the order below, and within a kind the
     * nearest deadline first, undated work after dated work.
     *
     * <p>Ordered by what the caller's silence costs. A waiting acceptance
     * expires and takes the deal with it; an unsigned contract blocks the
     * order; drafting is the caller's own housekeeping and can wait. Sorting by
     * creation time instead would list tasks in whatever order the platform
     * happened to produce them, which is the one order that means nothing.
     */
    private static final List<String> URGENCY = List.of(
            TaskView.Kind.ACCEPTANCE_PENDING,
            TaskView.Kind.CONTRACT_TO_SIGN,
            TaskView.Kind.DELIVERY_TO_START,
            TaskView.Kind.DELIVERY_TO_COMPLETE,
            TaskView.Kind.CONTRACT_TO_DRAFT);

    private static final Comparator<TaskView> MOST_URGENT_FIRST =
            Comparator.comparingInt((TaskView task) -> URGENCY.indexOf(task.kind()))
                    .thenComparing(TaskView::deadline,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    private final OrderService orderService;
    private final ContractMapper contractMapper;
    private final EnterpriseMapper enterpriseMapper;

    /**
     * Everything waiting on this enterprise, most urgent first.
     *
     * <p>Reads the orders once and walks them per kind, rather than querying
     * once per kind: one query answering five questions beats five answering
     * one each, and the classification stays in a single readable place.
     */
    public List<TaskView> findTasks(Long enterpriseId) {
        if (enterpriseId == null) {
            // A platform account has no tenant, so it owes nothing. Not an
            // error — an operator is a legitimate account type that simply has
            // no orders of its own to answer.
            return List.of();
        }

        Names names = new Names(enterpriseId);
        List<Order> orders = orderService.listMine(enterpriseId, null);
        List<TaskView> tasks = new ArrayList<>();

        for (Order order : orders) {
            // Only the lister can answer a waiting acceptance. The counterparty
            // is waiting too, but waiting is not a task — listing it for them
            // would invent work that does not exist and bury the work that does.
            if (OrderStatus.PENDING_CONFIRM.equals(order.getStatus()) && isLister(order, enterpriseId)) {
                tasks.add(new TaskView(
                        TaskView.Kind.ACCEPTANCE_PENDING,
                        "确认或拒绝摘牌",
                        TaskView.TargetType.ORDER,
                        order.getId(),
                        order.getOrderNo(),
                        order.getCommodityName(),
                        names.counterpartyOf(order),
                        order.getQuantity(),
                        order.getUnit(),
                        order.getAmount(),
                        "对方已摘牌，等你答复",
                        order.getConfirmDeadline()));
            }
        }

        for (Contract contract : myContracts(enterpriseId)) {
            if (Contract.Status.PENDING_SIGN.equals(contract.getStatus())
                    && !hasSigned(contract, enterpriseId)) {
                tasks.add(new TaskView(
                        TaskView.Kind.CONTRACT_TO_SIGN,
                        "签署合同",
                        TaskView.TargetType.CONTRACT,
                        contract.getId(),
                        contract.getContractNo(),
                        contract.getTitle(),
                        names.counterpartyOf(contract),
                        contract.getQuantity(),
                        contract.getUnit(),
                        contract.getAmount(),
                        hasOtherPartySigned(contract, enterpriseId) ? "对方已签，等你签署" : "双方均未签署",
                        null));
            }
        }

        for (Order order : orders) {
            // Confirmed but nothing drafted — the caller's own housekeeping, so
            // no deadline: nothing lapses if they are slow.
            if (OrderStatus.CONFIRMED.equals(order.getStatus()) && order.getContractId() == null) {
                tasks.add(orderTask(order, names, TaskView.Kind.CONTRACT_TO_DRAFT,
                        "起草合同", "订单已确认，尚未起草合同"));
            }

            // Delivery. Both parties see these: either may start and either may
            // complete, so unlike confirmation this is not one named party's move.
            if (OrderStatus.CONTRACTED.equals(order.getStatus())) {
                tasks.add(orderTask(order, names, TaskView.Kind.DELIVERY_TO_START,
                        "开始交收", "合同已生效，可以开始交收"));
            }
            if (OrderStatus.DELIVERING.equals(order.getStatus())) {
                tasks.add(orderTask(order, names, TaskView.Kind.DELIVERY_TO_COMPLETE,
                        "确认完成", "交收进行中，完成后确认"));
            }
        }

        tasks.sort(MOST_URGENT_FIRST);
        return tasks;
    }

    /** Just the count, for a badge that does not need the rows. */
    public int countTasks(Long enterpriseId) {
        return findTasks(enterpriseId).size();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private TaskView orderTask(Order order, Names names, String kind, String action, String detail) {
        return new TaskView(
                kind,
                action,
                TaskView.TargetType.ORDER,
                order.getId(),
                order.getOrderNo(),
                order.getCommodityName(),
                names.counterpartyOf(order),
                order.getQuantity(),
                order.getUnit(),
                order.getAmount(),
                detail,
                null);
    }

    /**
     * Whether the caller published the listing this order came from.
     *
     * <p>Read from the order's own columns: an order under a SELL listing has
     * the lister as its seller, and only MANUAL listings ever leave an order
     * waiting — an invariant the database enforces through
     * {@code ck_listing_manual_needs_frozen_goods}.
     */
    private boolean isLister(Order order, Long enterpriseId) {
        return enterpriseId.equals(order.getSellerId());
    }

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

    private boolean hasOtherPartySigned(Contract contract, Long enterpriseId) {
        return contract.isBuyer(enterpriseId)
                ? contract.getSellerSignedAt() != null
                : contract.getBuyerSignedAt() != null;
    }

    /**
     * Name lookups, cached for one call.
     *
     * <p>Scoped to the call rather than to the bean: a task list is short and
     * an enterprise has few counterparties, so a local map removes the repeated
     * lookups without holding company names in memory for the life of the
     * process. An instance field here would be an unbounded map on a singleton
     * — a slow leak, and a copy of every tenant's name outliving any request
     * that had a right to it.
     */
    private final class Names {
        private final Map<Long, String> cache = new HashMap<>();
        private final Long self;

        private Names(Long self) {
            this.self = self;
        }

        String counterpartyOf(Order order) {
            return of(self.equals(order.getBuyerId()) ? order.getSellerId() : order.getBuyerId());
        }

        String counterpartyOf(Contract contract) {
            return of(contract.isBuyer(self) ? contract.getSellerId() : contract.getBuyerId());
        }

        private String of(Long id) {
            if (id == null) {
                return "—";
            }
            return cache.computeIfAbsent(id, key -> {
                Enterprise enterprise = enterpriseMapper.selectById(key);
                return enterprise == null ? "—" : enterprise.getName();
            });
        }
    }
}
