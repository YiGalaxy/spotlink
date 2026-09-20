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
 * 算出某个企业当前欠平台什么。
 *
 * <p><b>这里是“待办”的唯一定义。</b>Web 控制台和 AI 顾问都读它，两者都不
 * 自行重新推导。原因在于这条规则不是一次查询——它是关于<em>下一步该谁走
 * </em>的判断，而且是不对称的：一份等我签署的合同是我的任务，同一份合同在等
 * 对方签署时就不是。这份判断若有两份副本，它们终将产生分歧；而一个悄悄漏掉
 * 了某件事的待办列表，比没有待办列表更糟，因为用户会就此不再去看它。
 *
 * <p>这里没有任何一处以租户为参数。调用方传入自己的企业，每一个底层查询都
 * 以它为范围——与别处同一条规则，而它之所以成立，是因为根本没有办法表达
 * 另一种写法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    /**
     * 最紧急的排在前面：先按下方的类型顺序，同一类型内按截止时间由近到远，
     * 有期限的排在无期限的之前。
     *
     * <p>按调用方沉默的代价排序。一个悬而未决的摘牌会过期并连带拖垮整笔交易；
     * 一份未签署的合同会卡住订单；起草合同是调用方自家的事务，可以等。若改为
     * 按创建时间排序，任务就会按平台碰巧产生的次序排列，而那是唯一毫无意义的
     * 顺序。
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
     * 该企业名下所有待办，最紧急的排在前面。
     *
     * <p>只读一次订单，然后逐类型遍历，而不是每种类型查一次：一次查询回答
     * 五个问题，胜过五次查询每次回答一个，而且分类逻辑留在一处可读的地方。
     */
    public List<TaskView> findTasks(Long enterpriseId) {
        if (enterpriseId == null) {
            // 平台账号没有租户，因此它什么都不欠。这不是错误——运营方是一种
            // 合法的账号类型，只是它名下没有任何需要答复的订单。
            return List.of();
        }

        Names names = new Names(enterpriseId);
        List<Order> orders = orderService.listMine(enterpriseId, null);
        List<TaskView> tasks = new ArrayList<>();

        for (Order order : orders) {
            // 只有挂牌方能答复一笔等待中的摘牌。对手方也在等，但等待不是任务
            // ——把它列给对手方会凭空造出并不存在的工作，并把真正存在的工作
            // 淹没掉。
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
            // 已确认但还没起草——这是调用方自家的事务，所以不设截止时间：
            // 他慢一点也不会有什么失效。
            if (OrderStatus.CONFIRMED.equals(order.getStatus()) && order.getContractId() == null) {
                tasks.add(orderTask(order, names, TaskView.Kind.CONTRACT_TO_DRAFT,
                        "起草合同", "订单已确认，尚未起草合同"));
            }

            // 交收。双方都会看到这些：开始交收双方都可以，完成交收双方也都可以，
            // 所以与确认环节不同，这不是某一个具名主体独有的动作。
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

    /** 只取数量，供不需要具体条目的角标使用。 */
    public int countTasks(Long enterpriseId) {
        return findTasks(enterpriseId).size();
    }

    // ------------------------------------------------------------------
    // 内部实现
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
     * 调用方是否发布了这笔订单所来自的那份挂牌。
     *
     * <p>从订单自身的列读出：SELL 挂牌下的订单，其挂牌方即卖方，而只有 MANUAL
     * 挂牌才会让订单停在等待状态——这一不变式由数据库通过
     * {@code ck_listing_manual_needs_frozen_goods} 强制保证。
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
     * 名称查询，在一次调用内做缓存。
     *
     * <p>作用域是本次调用而不是这个 bean：待办列表很短，一家企业的对手方也
     * 不多，所以一个局部 map 就能消除重复查询，又不必把公司名称在进程的整个
     * 生命周期里留在内存中。若把它写成实例字段，那就是单例上一个无上界的 map
     * ——一处缓慢的泄漏，而且每个租户的名称都会活得比任何有权使用它的请求更久。
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
