package com.bulk.trade.contract.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.contract.entity.Contract;
import com.bulk.trade.contract.mapper.ContractMapper;
import com.bulk.trade.trading.event.TaskChangedEvent;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.shared.web.ResultCode;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.bulk.trade.trading.entity.OrderStatusLog;
import com.bulk.trade.trading.mapper.OrderMapper;
import com.bulk.trade.trading.mapper.OrderStatusLogMapper;
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
 * Contracts: turning an agreement into an obligation.
 *
 * <p>An order says two parties intend to trade. A contract is what makes them
 * obliged to. The order state machine refuses to move from {@code CONFIRMED} to
 * {@code DELIVERING} without passing through {@code CONTRACTED}, so nothing
 * here is decorative — without a signature from both sides, goods never move.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Default weighing variance allowance, in percent. */
    private static final String DEFAULT_WEIGHT_TOLERANCE = "3.00";

    private final ContractMapper contractMapper;
    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Draws up a contract for a confirmed order.
     *
     * <p>Either party may trigger it. The terms are copied from the order, not
     * referenced — a contract records what was agreed at a moment, and must not
     * change if the order is later corrected.
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
        // Both sides: one now has a contract to sign, the other has one to watch.
        publishTaskChange("合同已起草", order);
        return contract;
    }

    /**
     * Records one party's signature.
     *
     * <p>When the second signature lands, the contract becomes effective and the
     * order advances to {@code CONTRACTED} — in the same transaction, so an
     * order can never be contracted without a fully signed contract behind it.
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
     * Moves the order to CONTRACTED once the contract is effective.
     *
     * <p>Routed through the same transition table the order service uses, so the
     * legal-move rule has exactly one definition in the codebase.
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
     * Builds the terms document.
     *
     * <p>Stored as JSON rather than columns: terms are written once, read whole,
     * and never queried by individual field.
     */
    /**
     * Tells both parties their pending work changed.
     *
     * <p>Carries no task data — recomputing it here would put a second copy of
     * "what counts as pending" in the event, and the two copies would drift.
     * Each client refetches through {@code TaskService} instead.
     */
    private void publishTaskChange(String reason, Order order) {
        eventPublisher.publishEvent(
                new TaskChangedEvent(reason, order.getBuyerId(), order.getSellerId()));
    }

    /** The same, for the two places that hold a contract but not its order. */
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
