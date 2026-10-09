package com.spotlink.trading.service;

import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.inventory.service.access.InventoryNoteAccess;
import com.spotlink.settlement.service.FreezeService;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.web.ResultCode;
import com.spotlink.trading.config.TradingProperties;
import com.spotlink.trading.dto.OrderAcceptRequest;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.entity.Order;
import com.spotlink.trading.entity.OrderStatus;
import com.spotlink.trading.entity.OrderStatusLog;
import com.spotlink.trading.event.OrderTradedEvent;
import com.spotlink.trading.event.TaskChangedEvent;
import com.spotlink.trading.mapper.ListingMapper;
import com.spotlink.trading.mapper.OrderMapper;
import com.spotlink.trading.mapper.OrderStatusLogMapper;
import com.spotlink.trading.mapper.GoodsTransferMapper;
import com.spotlink.trading.entity.GoodsTransfer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 摘牌，以及推动订单走完其生命周期。
 *
 * <p><b>摘牌做什么取决于挂牌，而本类正是两种惯例交汇之处。</b>在
 * {@link Listing.ConfirmMode#AUTO} 下，挂牌就是要约，摘牌就是合同：一笔交易
 * 所需的一切——预留货物、转移所有权、记录订单——都在同一个数据库事务里完成，
 * 因为一个半途而废的摘牌会是最糟的状态：货物离开了一方却没有到达另一方。
 * 在 {@link Listing.ConfirmMode#MANUAL} 下，摘牌只是预留：货物原地不动，直到
 * 挂牌方答复；而真正移动货物的事务是那次答复，不是摘牌。
 *
 * <p>两条路径都存在，因为两者都是真实的市场惯例；而一个悄悄选了其中一条并
 * 称之为“规则”的平台，在有人站出来说自己从未同意时，是解释不清的。模式在
 * 挂牌发布时设定，就在它所保护的那一方看得见的地方。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final ListingMapper listingMapper;
    private final InventoryNoteAccess inventoryNoteAccess;
    private final FreezeService freezeService;
    private final ApplicationEventPublisher eventPublisher;
    private final TradingProperties properties;
    private final InventoryMatchService inventoryMatchService;
    private final GoodsTransferMapper transferMapper;

    // ------------------------------------------------------------------
    // 摘牌
    // ------------------------------------------------------------------

    /**
     * 摘牌，产生一笔订单。
     *
     * <p>AUTO 在摘牌时原子转移货权，买方等量库存在收货前受限。真实源/目标
     * 库存及冻结写入过户台账，既保持数量守恒，也为交收与双方取消提供依据。
     */
    @Transactional
    public Order accept(Long listingId, OrderAcceptRequest request, LoginUser user) {
        Long enterpriseId = requireEnterprise(user);

        Listing listing = listingMapper.selectById(listingId);
        if (listing == null) {
            throw BusinessException.of(ResultCode.LISTING_NOT_FOUND);
        }
        if (listing.getEnterpriseId().equals(enterpriseId)) {
            throw BusinessException.of(ResultCode.LISTING_NOT_OWNED,
                    "不能摘自己的挂牌");
        }
        if (!listing.isOpenForTrade()) {
            throw BusinessException.of(ResultCode.LISTING_ALREADY_CLOSED);
        }
        if (listing.isExpired(OffsetDateTime.now())) {
            throw BusinessException.of(ResultCode.LISTING_EXPIRED);
        }

        BigDecimal quantity = request.quantity();
        com.spotlink.inventory.service.InventoryRules.quantity(quantity);
        if (quantity.compareTo(listing.getRemainingQuantity()) > 0) {
            throw BusinessException.of(ResultCode.LISTING_QUANTITY_EXCEEDED,
                    "挂牌剩余 %s %s，少于摘牌数量 %s".formatted(
                            listing.getRemainingQuantity().stripTrailingZeros().toPlainString(),
                            listing.getUnit(),
                            quantity.stripTrailingZeros().toPlainString()));
        }

        BigDecimal price = listing.getPrice();
        if (price == null) {
            throw BusinessException.of(ResultCode.BAD_REQUEST,
                    "该挂牌为面议价格，请先与挂牌方协商后再走协议交易");
        }
        BigDecimal amount = TradingNumbers.amount(quantity, price);

        Long buyerId = Listing.Side.SELL.equals(listing.getSide())
                ? enterpriseId : listing.getEnterpriseId();
        Long sellerId = Listing.Side.SELL.equals(listing.getSide())
                ? listing.getEnterpriseId() : enterpriseId;

        // 两种惯例唯一分道扬镳的地方，只在此处判定一次。此后的每一步对两者
        // 完全相同。
        boolean awaitsLister = listing.awaitsListerConfirm();
        InventoryNote selected = Listing.Side.BUY.equals(listing.getSide())
                ? inventoryMatchService.requireSource(listing, request.inventoryNoteId(), quantity, sellerId) : null;
        TransferResult transfer = awaitsLister
                ? null
                : transferGoods(listing, quantity, buyerId, sellerId, selected);

        Order order = new Order();
        order.setOrderNo(nextNo("OR"));
        order.setListingId(listing.getId());
        order.setBuyerId(buyerId);
        order.setSellerId(sellerId);
        order.setCategoryId(listing.getCategoryId());
        order.setCommodityName(selected == null ? listing.getCommodityName() : selected.getCommodityName());
        order.setSpec(selected == null ? listing.getSpec() : selected.getSpec());
        order.setQuantity(quantity);
        order.setUnit(listing.getUnit());
        order.setPrice(price);
        // 存储而非推导：这是双方约定下来的那个数额。
        order.setAmount(amount);
        order.setWarehouseId(selected == null ? listing.getWarehouseId() : selected.getWarehouseId());
        order.setDeliveryMethod(listing.getDeliveryMethod());
        order.setPaymentTerms(listing.getPaymentTerms());
        order.setGoodsFreezeId(transfer == null ? null : transfer.sourceFreezeId());
        order.setStatus(awaitsLister ? OrderStatus.PENDING_CONFIRM : OrderStatus.CONFIRMED);
        order.setConfirmedAt(awaitsLister ? null : OffsetDateTime.now());
        order.setConfirmDeadline(awaitsLister ? answerDeadlineFor(listing) : null);
        order.setVersion(0);
        order.setRemark(request.remark());
        orderMapper.insert(order);
        if (transfer != null) {
            persistTransfer(order, transfer);
            if (selected != null) freezeService.attributeTo(transfer.sourceFreezeId(), order.getId());
        }

        statusLogMapper.insert(OrderStatusLog.of(
                order.getId(), null, order.getStatus(),
                user.getUserId(), user.getUsername(),
                awaitsLister ? "摘牌，待挂牌方确认" : "摘牌成交"));

        reduceListing(listing, quantity);

        // 只有交易真正存在之后才对外公布。一笔未获答复的摘牌是一个问题，
        // 不是一个价格；把它画到行情图上，就是为一个可能永远不会发生的交易
        // 印出一个数字。
        if (!awaitsLister) {
            publishTraded(order);
        }
        // 无论哪种情形，某一方的列表都变了：MANUAL 下挂牌方多了一个要答复的
        // 问题，AUTO 下买方多了一份要起草的合同。
        publishTaskChange(awaitsLister ? "摘牌待确认" : "摘牌成交", order);

        log.info("Order {} created: {} {} of {} at {} (buyer={}, seller={}, {})",
                order.getOrderNo(), quantity.toPlainString(), listing.getUnit(),
                listing.getCommodityName(), price.toPlainString(), buyerId, sellerId,
                awaitsLister ? "awaiting lister confirmation" : "closed at acceptance");
        return order;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 挂牌方同意一笔等待他答复的摘牌。
     *
     * <p><b>只有挂牌方可以。</b>对手方通过摘牌已经说了同意；若再允许他代替
     * 挂牌方表态，确认环节就沦为摆设，而所有者从未同意出售的货物会流入市场。
     *
     * <p>MANUAL 下货物正是在这里移动的，而这正是该状态存在的全部理由：一笔
     * 未获答复的摘牌，什么都还没有买下。
     */
    @Transactional
    public Order confirm(Long orderId, LoginUser user) {
        Order order = loadParticipant(orderId, user.getEnterpriseId());
        Listing listing = requireLister(order, user, "只有挂牌方可以确认这笔成交");

        transition(order, OrderStatus.CONFIRMED, user, "挂牌方确认成交");

        // 摘牌预留了货物；答复它才是移动货物的动作。
        TransferResult transfer = transferGoods(listing, order.getQuantity(), order.getBuyerId(), order.getSellerId(), null);
        order.setGoodsFreezeId(transfer.sourceFreezeId());
        persistTransfer(order, transfer);
        // 在这里写入，而不是留给调用方：挂牌所指向的那笔预留可能刚刚转移到
        // 了新的一行，而与摘牌路径不同，这里没有后续步骤会把它持久化。
        persistListingReservation(listing);
        order.setConfirmedAt(OffsetDateTime.now());
        order.setConfirmDeadline(null);
        persistOrder(order);

        publishTraded(order);
        publishTaskChange("摘牌已确认", order);

        log.info("Order {} confirmed by lister {}", order.getOrderNo(), user.getEnterpriseId());
        return order;
    }

    /**
     * 挂牌方拒绝一笔摘牌。
     *
     * <p>这是与取消各自独立的一个动作，而不是取消上的一个标志位。拒绝发生在
     * 任何东西移动之前——没有货物、没有资金、没有合同——而取消则是拆解一笔
     * 已经存在的交易。两者在一个状态列里读起来一样，对当事方却含义不同，而
     * 这正是审计轨迹之所以存在要保住的那类差别。
     */
    @Transactional
    public Order reject(Long orderId, String reason, LoginUser user) {
        Order order = loadParticipant(orderId, user.getEnterpriseId());
        requireLister(order, user, "只有挂牌方可以拒绝这笔成交");

        if (!OrderStatus.PENDING_CONFIRM.equals(order.getStatus())) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态「%s」不能拒绝".formatted(OrderStatus.text(order.getStatus())));
        }

        restoreGoods(order);
        transition(order, OrderStatus.CANCELLED, user,
                reason == null || reason.isBlank() ? "挂牌方拒绝摘牌" : reason);
        order.setCancelledAt(OffsetDateTime.now());
        order.setCancelReason(reason);
        order.setConfirmDeadline(null);
        persistOrder(order);

        publishTaskChange("摘牌被拒绝", order);
        log.info("Order {} rejected by lister {}", order.getOrderNo(), user.getEnterpriseId());
        return order;
    }

    /**
     * 取消一笔订单，并把一切放回原处。
     *
     * <p>若卖方的挂牌仍然开放，货物退回该挂牌；否则退回卖方的可用库存池。
     * 无论哪种情形，都不会有任何东西继续为一笔已不存在的交易被预留。
     */
    @Transactional
    public Order cancel(Long orderId, String reason, LoginUser user) {
        Order order = loadParticipant(orderId, user.getEnterpriseId());

        if (!OrderStatus.PENDING_CONFIRM.equals(order.getStatus())) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_INVALID, "已成交订单须经双方协商取消");
        }

        if (!OrderStatus.canTransition(order.getStatus(), OrderStatus.CANCELLED)) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_INVALID,
                    "订单当前状态「%s」不能取消".formatted(OrderStatus.text(order.getStatus())));
        }

        restoreGoods(order);

        transition(order, OrderStatus.CANCELLED, user,
                reason == null || reason.isBlank() ? "取消订单" : reason);
        order.setCancelledAt(OffsetDateTime.now());
        order.setCancelReason(reason);
        order.setConfirmDeadline(null);
        persistOrder(order);

        publishTaskChange("订单已取消", order);
        log.info("Order {} cancelled by enterprise {}", order.getOrderNo(), user.getEnterpriseId());
        return order;
    }

    /**
     * 处理挂牌方从未答复的那些摘牌。
     *
     * <p>沉默不等于同意。过了截止时间的摘牌视为被拒绝，货物重新回到待售状态
     * ——另一条路是一份要约被一个无人回复的问题永久冻结，那比一笔干脆失效的
     * 交易更糟。
     *
     * @return 有多少笔摘牌失效
     */
    @Transactional
    public int expireOverdueConfirmations() {
        List<Order> lapsed = orderMapper.findOverdueConfirmations(OffsetDateTime.now());

        for (Order order : lapsed) {
            restoreGoods(order);
            transition(order, OrderStatus.CANCELLED, null, "system",
                    "挂牌方未在期限内确认，摘牌自动失效");
            order.setCancelledAt(OffsetDateTime.now());
            order.setCancelReason("挂牌方未在期限内确认");
            order.setConfirmDeadline(null);
            persistOrder(order);
            publishTaskChange("摘牌已逾期失效", order);
        }
        if (!lapsed.isEmpty()) {
            log.info("Lapsed {} unanswered acceptance(s)", lapsed.size());
        }
        return lapsed.size();
    }

    /**
     * 卖方放货。
     *
     * <p><b>仅限卖方，而这是一条规则，不是一种偏好。</b>交收始于货物移动，
     * 而只有其所有者才能移动它。一个能按下这个按钮的买方，就是一个在宣布
     * 别人已经发货的买方——而如果双方都能按，它设置的状态就毫无意义：一笔
     * 标记为交收中的订单，将不再能告诉任何一方到底有没有东西真的离开了仓库。
     *
     * <p>{@code allowedActions} 负责对买方隐藏这个按钮；而这里负责在他仍然
     * 调用接口时予以拒绝。
     */
    @Transactional
    public Order startDelivery(Long orderId, LoginUser user) {
        Order order = loadParticipant(orderId, user.getEnterpriseId());
        requireSeller(order, user, "只有卖方可以发起交收");
        transition(order, OrderStatus.DELIVERING, user,
                "DELIVERED".equals(order.getDeliveryMethod()) ? "卖方发货" : "卖方放货");
        persistOrder(order);
        publishTaskChange("卖方可发起交收", order);
        return order;
    }

    /**
     * 买方确认收货。
     *
     * <p>仅限买方，理由恰好对称：完成意味着货物送到并被接受，这是关于收货方
     * 拿到了什么的陈述。卖方确认自己完成的交收，就是当事人自己给自己批改
     * 作业，而这个状态也就不再能区分“已发出”与“已到达”。
     */
    @Transactional
    public Order complete(Long orderId, LoginUser user) {
        Order order = loadParticipant(orderId, user.getEnterpriseId());
        requireBuyer(order, user, "只有买方可以确认收货");
        transition(order, OrderStatus.COMPLETED, user,
                "DELIVERED".equals(order.getDeliveryMethod()) ? "买方收货" : "买方提货");
        GoodsTransfer transfer = transferMapper.findByOrder(order.getId());
        if (transfer != null) {
            if (!GoodsTransfer.TRANSFERRED.equals(transfer.getStatus())) {
                throw BusinessException.of(ResultCode.CONFLICT, "成交货物状态已变化，请刷新后重试");
            }
            freezeService.releaseInventory(order.getBuyerId(), transfer.getTargetFreezeId());
            transfer.setStatus(GoodsTransfer.DELIVERED);
            if (transferMapper.updateById(transfer) == 0) {
                throw BusinessException.of(ResultCode.CONFLICT, "交收记录已变化，请刷新后重试");
            }
        }
        persistOrder(order);
        publishTaskChange("交收已完成", order);
        return order;
    }

    /** 拒绝一个不是卖方一方的调用方。 */
    private void requireSeller(Order order, LoginUser user, String message) {
        if (!order.getSellerId().equals(user.getEnterpriseId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, message);
        }
    }

    /** 拒绝一个不是买方一方的调用方。 */
    private void requireBuyer(Order order, LoginUser user, String message) {
        if (!order.getBuyerId().equals(user.getEnterpriseId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, message);
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 调用方为当事方之一的所有订单。 */
    public List<Order> listMine(Long enterpriseId, String status) {
        return orderMapper.findParticipantOrders(enterpriseId, status);
    }

    public Order get(Long orderId, Long enterpriseId) {
        return loadParticipant(orderId, enterpriseId);
    }

    public List<OrderStatusLog> history(Long orderId, Long enterpriseId) {
        loadParticipant(orderId, enterpriseId);
        return statusLogMapper.findByOrderId(orderId);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 为所摘数量转移所有权。
     *
     * <p>两个效果必须同时发生，否则都不发生：卖方的库存单减少这些货物，买方
     * 在同一仓库获得一张等量的库存单。它们同处一个事务，因为一笔从一方拿走
     * 货物却没有交付给另一方的交易不是交易，而是损失。
     *
     * @return 被消耗掉的卖方冻结记录，供审计轨迹使用
     */
    private TransferResult transferGoods(Listing listing, BigDecimal quantity, Long buyerId, Long sellerId, InventoryNote selected) {
        Long goodsFreezeId = listing.getFreezeId();
        Long sellerNoteId = null;

        if (goodsFreezeId != null) {
            // 花掉发布挂牌时做出的那笔预留。没被摘走的部分继续保持冻结，
            // 也继续保持待售。
            var freezeBefore = freezeService.findFrozen(sellerId, goodsFreezeId);
            sellerNoteId = freezeBefore.getEntityId();

            // 剩余部分是一条新记录——部分消耗是关闭原记录而不是改写它——
            // 所以必须在本方法返回之前把挂牌指向新记录。否则挂牌会一直指向
            // 一笔已结清的预留，从此既不能被再次摘牌也不能撤牌：两条路径都
            // 按那个 id 加载冻结记录，都会被拒绝。
            Long remainderId = freezeService.consumeInventoryPartial(sellerId, goodsFreezeId, quantity);
            listing.setFreezeId(remainderId);
        } else {
            // BUY 挂牌：只使用摘牌方明确选择且已按采购条件核验的自有库存。
            InventoryNote source = selected;
            if (source == null) {
                throw BusinessException.of(ResultCode.INVENTORY_QUANTITY_INSUFFICIENT,
                        "卖方可用库存不足");
            }
            sellerNoteId = source.getId();
            goodsFreezeId = freezeService.freezeInventory(sellerId, sellerNoteId, quantity,
                    com.spotlink.settlement.entity.FreezeRecord.BizType.ORDER, null, "采购挂牌摘牌", source.getVersion()).getId();
            freezeService.consumeInventory(sellerId, goodsFreezeId);
        }

        InventoryNote target = createBuyerNote(listing, sellerNoteId, buyerId, quantity);
        Long targetFreezeId = freezeService.freezeInventory(buyerId, target.getId(), quantity,
                com.spotlink.settlement.entity.FreezeRecord.BizType.ORDER, null, "成交货物待交收").getId();
        target = inventoryNoteAccess.selectById(target.getId());
        target.setStatus(InventoryNote.Status.PENDING_DELIVERY);
        if (inventoryNoteAccess.updateById(target) == 0) throw BusinessException.of(ResultCode.CONFLICT, "买方成交库存正在被修改，请重试");
        return new TransferResult(sellerNoteId, target.getId(), goodsFreezeId, targetFreezeId);
    }

    /** 给买方一张属于自己的、对应其刚买下货物的库存单。 */
    private InventoryNote createBuyerNote(Listing listing, Long sourceNoteId, Long buyerId, BigDecimal quantity) {
        InventoryNote source = sourceNoteId == null ? null : inventoryNoteAccess.selectById(sourceNoteId);

        InventoryNote note = new InventoryNote();
        note.setNoteNo(nextNo("IN"));
        note.setEnterpriseId(buyerId);
        if (source == null) throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        note.setCategoryId(source.getCategoryId());
        note.setWarehouseId(source.getWarehouseId());
        note.setCommodityName(source.getCommodityName());
        note.setBrand(source.getBrand());
        note.setOrigin(source.getOrigin());
        note.setSpec(source.getSpec());
        note.setTotalQuantity(quantity);
        note.setAvailableQuantity(quantity);
        note.setFrozenQuantity(BigDecimal.ZERO);
        note.setUnit(source.getUnit());
        note.setStatus(InventoryNote.Status.IN_STOCK);
        note.setVersion(0);
        note.setRemark(source == null
                ? "摘牌成交自动生成"
                : "摘牌成交自动生成，来源库存单 " + source.getNoteNo());
        inventoryNoteAccess.insert(note);
        return note;
    }

    private record TransferResult(Long sourceNoteId, Long targetNoteId, Long sourceFreezeId, Long targetFreezeId) { }

    private void persistTransfer(Order order, TransferResult result) {
        GoodsTransfer transfer = new GoodsTransfer();
        transfer.setOrderId(order.getId());
        transfer.setSellerId(order.getSellerId());
        transfer.setBuyerId(order.getBuyerId());
        transfer.setSourceNoteId(result.sourceNoteId());
        transfer.setTargetNoteId(result.targetNoteId());
        transfer.setSourceFreezeId(result.sourceFreezeId());
        transfer.setTargetFreezeId(result.targetFreezeId());
        transfer.setQuantity(order.getQuantity());
        transfer.setUnit(order.getUnit());
        transfer.setStatus(GoodsTransfer.TRANSFERRED);
        transfer.setVersion(0);
        transferMapper.insert(transfer);
        freezeService.attributeTo(result.targetFreezeId(), order.getId());
    }

    private void reduceListing(Listing listing, BigDecimal quantity) {
        BigDecimal remaining = listing.getRemainingQuantity().subtract(quantity);
        listing.setRemainingQuantity(remaining);
        listing.setStatus(remaining.signum() == 0
                ? Listing.Status.FILLED
                : Listing.Status.PARTIALLY_FILLED);

        if (listingMapper.updateById(listing) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该挂牌正在被其他操作修改，请重试");
        }
    }

    /**
     * 取消之后把货物放回去。
     *
     * <p>若挂牌仍然开放，货物会重新针对它预留，因此产生本订单的那份要约对
     * 下一位买方依然有效。若已不开放，货物就径直退回卖方。
     */
    private void restoreGoods(Order order) {
        Listing listing = order.getListingId() == null
                ? null : listingMapper.selectById(order.getListingId());

        if (order.getGoodsFreezeId() == null) {
            // 一笔未获确认的摘牌：从来没有什么移动过，因此没有什么需要解冻，
            // 也没有库存单需要重建。只有那份要约的剩余数量需要还回去，否则
            // 挂牌会看起来像卖掉了一些它从未卖掉的东西。
            if (canReopen(listing)) {
                returnQuantityToListing(listing, order.getQuantity());
            }
            return;
        }

        if (listing != null && listing.isOpenForTrade()) {
            freezeService.freezeInventory(
                    order.getSellerId(),
                    sellerNoteIdOf(listing),
                    order.getQuantity(),
                    com.spotlink.settlement.entity.FreezeRecord.BizType.LISTING,
                    listing.getId(),
                    "订单取消，货权归还挂牌");
            returnQuantityToListing(listing, order.getQuantity());
            return;
        }

        // 没有可退回的挂牌，于是直接把货物退回去。因为原来的库存单已被部分
        // 消耗，所以在一张新库存单上重建可用余量。
        createCancellationReturn(order);
    }

    /**
     * 把数量还回一份要约，并重新陈述这份要约是什么。
     *
     * <p>状态由算术推出，而不是被断言：只有当原始全数都回到台面上时，这份
     * 挂牌才重新变回一份单纯开放的要约。
     */
    private void returnQuantityToListing(Listing listing, BigDecimal quantity) {
        BigDecimal restored = listing.getRemainingQuantity().add(quantity);
        listing.setRemainingQuantity(restored);
        listing.setStatus(restored.compareTo(listing.getQuantity()) == 0
                ? Listing.Status.OPEN
                : Listing.Status.PARTIALLY_FILLED);

        if (listingMapper.updateById(listing) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该挂牌正在被其他操作修改，请重试");
        }
    }

    /**
     * 一份挂牌是否还能收回货物。
     *
     * <p>有意比 {@link Listing#isOpenForTrade()} 更宽松：剩余部分被全部摘走的
     * 挂牌读起来是 FILLED，然而一笔失效的摘牌必须能把它重新打开。只有被所有者
     * 关闭的挂牌，或者时间耗尽的那种，才真正无法再接受任何东西。
     */
    private boolean canReopen(Listing listing) {
        return listing != null
                && !Listing.Status.CLOSED.equals(listing.getStatus())
                && !Listing.Status.EXPIRED.equals(listing.getStatus());
    }

    /** 当没有任何挂牌可供退回时，重建卖方的持仓。 */
    private void createCancellationReturn(Order order) {
        InventoryNote note = new InventoryNote();
        note.setNoteNo(nextNo("IN"));
        note.setEnterpriseId(order.getSellerId());
        note.setCategoryId(order.getCategoryId());
        note.setWarehouseId(order.getWarehouseId() == null ? 2001L : order.getWarehouseId());
        note.setCommodityName(order.getCommodityName());
        note.setSpec(order.getSpec());
        note.setTotalQuantity(order.getQuantity());
        note.setAvailableQuantity(order.getQuantity());
        note.setFrozenQuantity(BigDecimal.ZERO);
        note.setUnit(order.getUnit());
        note.setStatus(InventoryNote.Status.IN_STOCK);
        note.setVersion(0);
        note.setRemark("订单 " + order.getOrderNo() + " 取消，货物退回");
        inventoryNoteAccess.insert(note);
    }

    private Long sellerNoteIdOf(Listing listing) {
        if (listing.getFreezeId() == null) {
            // 只有在挂牌开放却背后没有任何预留时才会走到这里，而平台不会
            // 产生这种状态——只有当剩余量归零时冻结才会释放，而那一刻正是
            // 挂牌不再开放之时。这里明说，而不是留到三层调用之后再变成一个
            // 空指针。
            throw BusinessException.of(ResultCode.CONFLICT,
                    "该挂牌没有可归还的冻结货物，请刷新后重试");
        }
        // 支撑该挂牌的冻结记录指向货物所依托的那张库存单。
        var freeze = freezeService.findFrozen(listing.getEnterpriseId(), listing.getFreezeId());
        return freeze.getEntityId();
    }

    /** 把挂牌写回，以免变更后的预留指针丢失。 */
    private void persistListingReservation(Listing listing) {
        if (listingMapper.updateById(listing) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该挂牌正在被其他操作修改，请重试");
        }
    }

    /**
     * 把订单迁移到新状态，拒绝状态表不允许的迁移。让每一次状态变更都经由这
     * 里，正是这张表成为生命周期唯一描述而非其文档的原因。
     */
    private void transition(Order order, String to, LoginUser user, String reason) {
        transition(order, to, user.getUserId(), user.getUsername(), reason);
    }

    /**
     * 同一个动作，但归属到实际做出它的人名下。
     *
     * <p>单独拆出来是为了定时扫描，它不代表任何人的意志迁移订单。把它的操作
     * 归到某个真实用户名下，会是在那份专为裁决争议而存在的记录里撒谎，所以
     * 它署名 {@code system}。
     */
    private void transition(Order order, String to,
                            Long operatorId, String operatorName, String reason) {
        String from = order.getStatus();
        if (!OrderStatus.canTransition(from, to)) {
            throw BusinessException.of(ResultCode.ORDER_STATUS_INVALID,
                    "订单状态不能从「%s」变为「%s」"
                            .formatted(OrderStatus.text(from), OrderStatus.text(to)));
        }

        order.setStatus(to);
        statusLogMapper.insert(OrderStatusLog.of(
                order.getId(), from, to, operatorId, operatorName, reason));
    }

    /**
     * 加载订单的挂牌，并检查调用方拥有它。
     *
     * <p>所有权从挂牌读出，而不是从订单的买方/卖方列推断。挂牌方是这两者中
     * 的哪一个取决于挂牌方向，而靠推断就是把那种“一直正确，直到某天有人启用
     * 了第二个方向，于是悄悄检查了错误的一方”的捷径。
     */
    private Listing requireLister(Order order, LoginUser user, String message) {
        Listing listing = order.getListingId() == null
                ? null : listingMapper.selectById(order.getListingId());
        if (listing == null) {
            throw BusinessException.of(ResultCode.LISTING_NOT_FOUND);
        }
        if (!listing.getEnterpriseId().equals(user.getEnterpriseId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, message);
        }
        return listing;
    }

    /**
     * 挂牌方有多长时间可以答复。
     *
     * <p>以挂牌自身的失效时间为上限：一笔活得比它所摘的要约还久的摘牌，
     * 就成了一个关于某件已不在台面上的东西的问题，而到期扫描早就会把其背后
     * 的货物释放掉了。
     */
    private OffsetDateTime answerDeadlineFor(Listing listing) {
        OffsetDateTime byWindow = OffsetDateTime.now().plus(properties.effectiveConfirmWindow());
        OffsetDateTime validUntil = listing.getValidUntil();
        return validUntil != null && validUntil.isBefore(byWindow) ? validUntil : byWindow;
    }

    /**
     * 通知这些具名企业：他们的待办发生了变化。
     *
     * <p>只是点名而已——事件不携带任何任务数据。在这里重算任务，会把第二份
     * “什么算作待办”的定义塞进事件里，而这两份定义终将产生分歧；监听方改为
     * 通过 {@link TaskService} 重新拉取，于是那个问题只有一个答案。
     *
     * <p>即便只有一方新增了工作，也通知双方：一方的动作会改变另一方看到的
     * 内容，而一个过期的界面正是这套机制存在要解决的抱怨。
     */
    private void publishTaskChange(String reason, Order order) {
        eventPublisher.publishEvent(
                new TaskChangedEvent(reason, order.getBuyerId(), order.getSellerId()));
    }

    /** 公布一笔已完成的交易。交易模块不知道行情推送的存在；谁关心谁订阅。 */
    private void publishTraded(Order order) {
        eventPublisher.publishEvent(new OrderTradedEvent(
                order.getCategoryId(), order.getCommodityName(),
                order.getPrice(), order.getQuantity(), order.getUnit(),
                order.getBuyerId(), order.getSellerId(), order.getOrderNo()));
    }

    private Order loadParticipant(Long orderId, Long enterpriseId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !order.involves(enterpriseId)) {
            throw BusinessException.of(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private void persistOrder(Order order) {
        if (orderMapper.updateById(order) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "订单正在被其他操作修改，请刷新后重试");
        }
    }

    private Long requireEnterprise(LoginUser user) {
        if (user.getEnterpriseId() == null) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "平台运营账号不能下单");
        }
        return user.getEnterpriseId();
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
