package com.spotlink.contract.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.spotlink.contract.entity.Contract;
import com.spotlink.contract.mapper.ContractMapper;
import com.spotlink.trading.event.TaskChangedEvent;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.web.ResultCode;
import com.spotlink.trading.entity.Order;
import com.spotlink.trading.entity.OrderStatus;
import com.spotlink.trading.entity.OrderStatusLog;
import com.spotlink.trading.mapper.OrderMapper;
import com.spotlink.trading.mapper.OrderStatusLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 合同：把一项共识变成一项义务。
 *
 * <p>订单说明双方有意交易。合同才是让他们负有义务的东西。订单状态机拒绝从
 * {@code CONFIRMED} 不经 {@code CONTRACTED} 直接进入 {@code DELIVERING}，
 * 所以这里没有任何东西是装饰性的——没有双方的签署，货物永远不会移动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 默认允许的过磅差异，百分比。 */
    private static final String DEFAULT_WEIGHT_TOLERANCE = "3.00";

    private final ContractMapper contractMapper;
    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 为一份已确认的订单拟定合同。
     *
     * <p>任何一方都可以发起。条款是从订单复制过来的，而不是引用——合同记录
     * 的是某一刻约定的内容，若订单日后被更正，它绝不能跟着变。
     */
    @Transactional
    public Contract draftForOrder(Long orderId, LoginUser user) {
        Order order = loadOrder(orderId, user.getEnterpriseId());

        if (!OrderStatus.CONFIRMED.equals(order.getStatus())) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态「%s」，只有已确认的订单才能起草合同"
                            .formatted(OrderStatus.text(order.getStatus())));
        }

        Contract existing = contractMapper.selectOne(Wrappers.<Contract>lambdaQuery()
                .eq(Contract::getOrderId, orderId));
        if (existing != null) {
            return existing;
        }

        Contract contract = new Contract();
        contract.setContractNo(nextNo("CT"));
        contract.setOrderId(order.getId());
        contract.setBuyerId(order.getBuyerId());
        contract.setSellerId(order.getSellerId());
        contract.setTitle("%s %s %s%s 购销合同".formatted(
                order.getCommodityName(),
                order.getQuantity().stripTrailingZeros().toPlainString(),
                order.getUnit(),
                order.getAmount() == null
                        ? ""
                        : "（金额 " + order.getAmount().stripTrailingZeros().toPlainString() + " 元）"));
        contract.setTerms(writeTerms(order));
        contract.setQuantity(order.getQuantity());
        contract.setUnit(order.getUnit());
        contract.setPrice(order.getPrice());
        contract.setAmount(order.getAmount());
        contract.setWeightTolerance(new java.math.BigDecimal(DEFAULT_WEIGHT_TOLERANCE));
        contract.setStatus(Contract.Status.PENDING_SIGN);
        contractMapper.insert(contract);

        order.setContractId(contract.getId());
        orderMapper.updateById(order);

        log.info("Contract {} drafted for order {}", contract.getContractNo(), order.getOrderNo());
        // 双方都要通知：一方现在有合同要签，另一方有合同要看。
        publishTaskChange("合同已起草", order);
        return contract;
    }

    /**
     * 记录一方的签署。
     *
     * <p>当第二个签名落下时，合同生效，订单进入 {@code CONTRACTED}——在同一个
     * 事务里完成，因此一笔订单绝不可能在其背后没有一份双方签妥的合同的情况下
     * 进入已签约。
     */
    @Transactional
    public Contract sign(Long contractId, LoginUser user) {
        Contract contract = loadParticipant(contractId, user.getEnterpriseId());

        if (Contract.Status.SIGNED.equals(contract.getStatus())) {
            throw BusinessException.of(ResultCode.CONTRACT_ALREADY_SIGNED);
        }
        if (Contract.Status.TERMINATED.equals(contract.getStatus())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "合同已解除，不能签署");
        }
        if (contract.hasSigned(user.getEnterpriseId())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "您已经签署过该合同");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (contract.isBuyer(user.getEnterpriseId())) {
            contract.setBuyerSignedAt(now);
            contract.setBuyerSignedBy(user.getUserId());
        } else {
            contract.setSellerSignedAt(now);
            contract.setSellerSignedBy(user.getUserId());
        }

        boolean bothSigned = contract.getBuyerSignedAt() != null
                && contract.getSellerSignedAt() != null;

        if (bothSigned) {
            contract.setStatus(Contract.Status.SIGNED);
            if (contractMapper.updateById(contract) == 0) {
                throw BusinessException.of(ResultCode.CONFLICT, "合同正在被其他操作修改，请重试");
            }
            advanceOrder(contract, user);
            publishTaskChange("合同已生效", contract);
            log.info("Contract {} signed by both parties; order {} is now contracted",
                    contract.getContractNo(), contract.getOrderId());
            return contract;
        }

        if (contractMapper.updateById(contract) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "合同正在被其他操作修改，请重试");
        }
        publishTaskChange("合同待对方签署", contract);
        log.info("Contract {} signed by enterprise {}; awaiting the other side",
                contract.getContractNo(), user.getEnterpriseId());
        return contract;
    }

    public Contract getByOrder(Long orderId, Long enterpriseId) {
        Contract contract = contractMapper.selectOne(Wrappers.<Contract>lambdaQuery()
                .eq(Contract::getOrderId, orderId));
        if (contract == null || !contract.involves(enterpriseId)) {
            throw BusinessException.of(ResultCode.CONTRACT_NOT_FOUND);
        }
        return contract;
    }

    public List<Contract> listMine(Long enterpriseId) {
        return contractMapper.selectList(Wrappers.<Contract>lambdaQuery()
                .and(w -> w.eq(Contract::getBuyerId, enterpriseId)
                        .or()
                        .eq(Contract::getSellerId, enterpriseId))
                .orderByDesc(Contract::getId));
    }

    // ------------------------------------------------------------------

    /**
     * 合同生效后把订单推进到 CONTRACTED。
     *
     * <p>走的是订单服务所用的同一张迁移表，因此这条合法迁移规则在整个代码库中
     * 只有一个定义。
     */
    private void advanceOrder(Contract contract, LoginUser user) {
        Order order = orderMapper.selectById(contract.getOrderId());
        if (order == null) {
            throw BusinessException.of(ResultCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.canTransition(order.getStatus(), OrderStatus.CONTRACTED)) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_INVALID,
                    "合同已生效，但订单状态「%s」无法进入已签约"
                            .formatted(OrderStatus.text(order.getStatus())));
        }

        String from = order.getStatus();
        order.setStatus(OrderStatus.CONTRACTED);
        if (orderMapper.updateById(order) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "订单正在被其他操作修改，请重试");
        }
        statusLogMapper.insert(OrderStatusLog.of(
                order.getId(), from, OrderStatus.CONTRACTED,
                user.getUserId(), user.getUsername(), "合同签署生效"));
    }

    /**
     * 构建条款文档。
     *
     * <p>以 JSON 存储而不是拆成列：条款一次写入、整体读出，从不按单个字段查询。
     */
    /**
     * 通知双方他们的待办发生了变化。
     *
     * <p>不携带任何任务数据——在这里重算会把第二份“什么算作待办”的定义放进
     * 事件里，而这两份副本会发生偏移。每个客户端改为通过 {@code TaskService}
     * 重新拉取。
     */
    private void publishTaskChange(String reason, Order order) {
        eventPublisher.publishEvent(
                new TaskChangedEvent(reason, order.getBuyerId(), order.getSellerId()));
    }

    /** 同上，供那两个持有合同却拿不到其订单的地方使用。 */
    private void publishTaskChange(String reason, Contract contract) {
        eventPublisher.publishEvent(
                new TaskChangedEvent(reason, contract.getBuyerId(), contract.getSellerId()));
    }

    private String writeTerms(Order order) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("commodityName", order.getCommodityName());
        terms.put("quantity", order.getQuantity().stripTrailingZeros().toPlainString());
        terms.put("unit", order.getUnit());
        terms.put("price", order.getPrice().stripTrailingZeros().toPlainString());
        terms.put("amount", order.getAmount().stripTrailingZeros().toPlainString());
        terms.put("deliveryMethod",
                "DELIVERED".equals(order.getDeliveryMethod()) ? "送到" : "自提");
        terms.put("paymentTerms", order.getPaymentTerms());
        terms.put("weightTolerance", DEFAULT_WEIGHT_TOLERANCE + "%");
        terms.put("settlementBasis",
                "结算重量以实际过磅重量为准，磅差在约定范围内按实际重量结算，"
                        + "超出范围时由双方协商处理，系统不自动结算。");
        terms.put("qualityDispute",
                "买方应在收货后 7 日内提出质量异议，逾期视为验收合格。");
        terms.put("disputeResolution", "争议由双方协商解决，协商不成提交平台所在地法院管辖。");
        try {
            return objectMapper.writeValueAsString(terms);
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.INTERNAL_ERROR, "合同条款生成失败");
        }
    }

    private Order loadOrder(Long orderId, Long enterpriseId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.involves(enterpriseId)) {
            throw BusinessException.of(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private Contract loadParticipant(Long contractId, Long enterpriseId) {
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null || !contract.involves(enterpriseId)) {
            throw BusinessException.of(ResultCode.CONTRACT_NOT_FOUND);
        }
        return contract;
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
