package com.bulk.trade.trading.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.inventory.entity.InventoryNote;
import com.bulk.trade.inventory.mapper.InventoryNoteMapper;
import com.bulk.trade.settlement.service.FreezeService;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.shared.web.ResultCode;
import com.bulk.trade.trading.config.TradingProperties;
import com.bulk.trade.trading.dto.OrderAcceptRequest;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.bulk.trade.trading.entity.OrderStatusLog;
import com.bulk.trade.trading.event.OrderTradedEvent;
import com.bulk.trade.trading.event.TaskChangedEvent;
import com.bulk.trade.trading.mapper.ListingMapper;
import com.bulk.trade.trading.mapper.OrderMapper;
import com.bulk.trade.trading.mapper.OrderStatusLogMapper;
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
 * Accepting listings and moving orders through their lifecycle.
 *
 * <p><b>What acceptance does depends on the listing, and this class is where
 * the two conventions meet.</b> Under {@link Listing.ConfirmMode#AUTO} the
 * listing is an offer and acceptance is the contract: everything a trade needs
 * — reserving the goods, moving title, recording the order — happens in one
 * database transaction, because a half-completed acceptance would be the worst
 * possible state, goods that left one party without arriving at the other.
 * Under {@link Listing.ConfirmMode#MANUAL} acceptance only reserves: goods stay
 * put until the lister answers, and the transaction that moves them is the
 * answer, not the acceptance.
 *
 * <p>Both paths exist because both are real market conventions, and a platform
 * that quietly picks one and calls it "the rules" cannot explain itself when a
 * party says they never agreed. The mode is set when the listing is published,
 * where the party it protects can see it.
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
    private final InventoryNoteMapper inventoryNoteMapper;
    private final FreezeService freezeService;
    private final ApplicationEventPublisher eventPublisher;
    private final TradingProperties properties;

    // ------------------------------------------------------------------
    // Acceptance
    // ------------------------------------------------------------------

    /**
     * Accepts a listing, producing an order.
     *
     * <p><b>Simplification worth naming:</b> under AUTO, title moves here at
     * acceptance rather than at contract signature. The buyer immediately
     * receives an inventory note of their own for the accepted quantity, and
     * the seller's note is reduced. On a real platform title would pass when
     * the contract takes effect, with acceptance merely reserving. Doing it
     * here keeps the goods in exactly one place at every moment — which is the
     * property that makes the rest of the flow checkable — at the cost of a gap
     * between "accepted" and "contracted" that a real deployment would close.
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

        Long buyerId = Listing.Side.SELL.equals(listing.getSide())
                ? enterpriseId : listing.getEnterpriseId();
        Long sellerId = Listing.Side.SELL.equals(listing.getSide())
                ? listing.getEnterpriseId() : enterpriseId;

        // The one place the two conventions diverge, decided once. Everything
        // after this point is identical for both.
        boolean awaitsLister = listing.awaitsListerConfirm();
        Long goodsFreezeId = awaitsLister
                ? null
                : transferGoods(listing, quantity, buyerId, sellerId);

        Order order = new Order();
        order.setOrderNo(nextNo("OR"));
        order.setListingId(listing.getId());
        order.setBuyerId(buyerId);
        order.setSellerId(sellerId);
        order.setCategoryId(listing.getCategoryId());
        order.setCommodityName(listing.getCommodityName());
        order.setSpec(listing.getSpec());
        order.setQuantity(quantity);
        order.setUnit(listing.getUnit());
        order.setPrice(price);
        // Stored, not derived: this is the figure the two parties agreed to.
        order.setAmount(price.multiply(quantity));
        order.setWarehouseId(listing.getWarehouseId());
        order.setDeliveryMethod(listing.getDeliveryMethod());
        order.setPaymentTerms(listing.getPaymentTerms());
        order.setGoodsFreezeId(goodsFreezeId);
        order.setStatus(awaitsLister ? OrderStatus.PENDING_CONFIRM : OrderStatus.CONFIRMED);
        order.setConfirmedAt(awaitsLister ? null : OffsetDateTime.now());
        order.setConfirmDeadline(awaitsLister ? answerDeadlineFor(listing) : null);
        order.setVersion(0);
        order.setRemark(request.remark());
        orderMapper.insert(order);

        statusLogMapper.insert(OrderStatusLog.of(
                order.getId(), null, order.getStatus(),
                user.getUserId(), user.getUsername(),
                awaitsLister ? "摘牌，待挂牌方确认" : "摘牌成交"));

        reduceListing(listing, quantity);

        // Announced only once a trade actually exists. An unanswered acceptance
        // is a question, not a price, and putting it on the market chart would
        // print a number for a deal that may never happen.
        if (!awaitsLister) {
            publishTraded(order);
        }
        // Either way somebody's list changed: under MANUAL the lister gained a
        // question to answer, and under AUTO the buyer gained a contract to
        // draft.
        publishTaskChange(awaitsLister ? "摘牌待确认" : "摘牌成交", order);

        log.info("Order {} created: {} {} of {} at {} (buyer={}, seller={}, {})",
                order.getOrderNo(), quantity.toPlainString(), listing.getUnit(),
                listing.getCommodityName(), price.toPlainString(), buyerId, sellerId,
                awaitsLister ? "awaiting lister confirmation" : "closed at acceptance");
        return order;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * The lister agrees to an acceptance that was waiting for them.
     *
     * <p><b>Only the lister.</b> The counterparty already said yes by
     * accepting; letting them also say it on the lister's behalf would turn the
     * confirmation step into decoration and put goods on the market that their
     * owner never agreed to sell.
     *
     * <p>This is where the goods move under MANUAL, which is the whole reason
     * the state exists: an acceptance that has not been answered has not bought
     * anything yet.
     */
    @Transactional
    public Order confirm(Long orderId, LoginUser user) {
        Order order = loadParticipant(orderId, user.getEnterpriseId());
        Listing listing = requireLister(order, user, "只有挂牌方可以确认这笔成交");

        transition(order, OrderStatus.CONFIRMED, user, "挂牌方确认成交");

        // The acceptance reserved the goods; answering it is what moves them.
        order.setGoodsFreezeId(transferGoods(
                listing, order.getQuantity(), order.getBuyerId(), order.getSellerId()));
        // Written here rather than left to the caller: the reservation the
        // listing points at may have just moved to a new row, and unlike the
        // accept path there is no later step that would persist it.
        persistListingReservation(listing);
        order.setConfirmedAt(OffsetDateTime.now());
        order.setConfirmDeadline(null);
        orderMapper.updateById(order);

        publishTraded(order);
        publishTaskChange("摘牌已确认", order);

        log.info("Order {} confirmed by lister {}", order.getOrderNo(), user.getEnterpriseId());
        return order;
    }

    /**
     * The lister declines an acceptance.
     *
     * <p>A separate act from cancellation, not a flag on it. Refusing happens
     * before anything has moved — no goods, no money, no contract — while
     * cancelling unwinds a deal that already exists. They read the same in a
     * status column and mean different things to the parties, which is exactly
     * the kind of difference an audit trail exists to preserve.
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
        orderMapper.updateById(order);

        publishTaskChange("摘牌被拒绝", order);
        log.info("Order {} rejected by lister {}", order.getOrderNo(), user.getEnterpriseId());
        return order;
    }

    /**
     * Cancels an order and puts everything back.
     *
     * <p>Goods return to the seller's listing if it is still open, otherwise to
     * the seller's available pool. Either way nothing is left reserved for a
     * deal that no longer exists.
     */
    @Transactional
    public Order cancel(Long orderId, String reason, LoginUser user) {
        Order order = loadParticipant(orderId, user.getEnterpriseId());

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
        orderMapper.updateById(order);

        publishTaskChange("订单已取消", order);
        log.info("Order {} cancelled by enterprise {}", order.getOrderNo(), user.getEnterpriseId());
        return order;
    }

    /**
     * Answers the acceptances their lister never answered.
     *
     * <p>Silence is not agreement. An acceptance past its deadline is declined,
     * and the goods go back on offer — the alternative is an offer frozen
     * indefinitely by a question nobody replied to, which is a worse failure
     * than a deal that simply lapsed.
     *
     * @return how many acceptances lapsed
     */
    @Transactional
    public int expireOverdueConfirmations() {
        List<Order> lapsed = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, OrderStatus.PENDING_CONFIRM)
                .isNotNull(Order::getConfirmDeadline)
                .lt(Order::getConfirmDeadline, OffsetDateTime.now()));

        for (Order order : lapsed) {
            restoreGoods(order);
            transition(order, OrderStatus.CANCELLED, null, "system",
                    "挂牌方未在期限内确认，摘牌自动失效");
            order.setCancelledAt(OffsetDateTime.now());
            order.setCancelReason("挂牌方未在期限内确认");
            order.setConfirmDeadline(null);
            orderMapper.updateById(order);
            publishTaskChange("摘牌已逾期失效", order);
        }
        if (!lapsed.isEmpty()) {
            log.info("Lapsed {} unanswered acceptance(s)", lapsed.size());
        }
        return lapsed.size();
    }

    /**
     * The seller releases the goods.
     *
     * <p><b>Seller only, and this is a rule rather than a preference.</b>
     * Delivery starts when the goods move, and only their owner can move them.
     * A buyer able to press this would be a buyer announcing that somebody else
     * has shipped — and if both parties can press it, the state it sets means
     * nothing: an order marked 交收中 would no longer tell either side whether
     * anything had actually left the warehouse.
     *
     * <p>{@code allowedActions} hides the button from the buyer; this is what
     * refuses them if they call the endpoint anyway.
     */
    @Transactional
    public Order startDelivery(Long orderId, LoginUser user) {
        Order order = loadParticipant(orderId, user.getEnterpriseId());
        requireSeller(order, user, "只有卖方可以发起交收");
        transition(order, OrderStatus.DELIVERING, user,
                "DELIVERED".equals(order.getDeliveryMethod()) ? "卖方发货" : "卖方放货");
        orderMapper.updateById(order);
        publishTaskChange("卖方可发起交收", order);
        return order;
    }

    /**
     * The buyer confirms receipt.
     *
     * <p>Buyer only, for the mirror-image reason: completion means the goods
     * arrived and were accepted, which is a statement about what the receiver
     * got. A seller confirming their own delivery is a party marking their own
     * homework, and the state would stop distinguishing "sent" from "arrived".
     */
    @Transactional
    public Order complete(Long orderId, LoginUser user) {
        Order order = loadParticipant(orderId, user.getEnterpriseId());
        requireBuyer(order, user, "只有买方可以确认收货");
        transition(order, OrderStatus.COMPLETED, user,
                "DELIVERED".equals(order.getDeliveryMethod()) ? "买方收货" : "买方提货");
        orderMapper.updateById(order);
        publishTaskChange("交收已完成", order);
        return order;
    }

    /** Refuses a caller who is not the selling party. */
    private void requireSeller(Order order, LoginUser user, String message) {
        if (!order.getSellerId().equals(user.getEnterpriseId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, message);
        }
    }

    /** Refuses a caller who is not the buying party. */
    private void requireBuyer(Order order, LoginUser user, String message) {
        if (!order.getBuyerId().equals(user.getEnterpriseId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, message);
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Orders where the caller is either party. */
    public List<Order> listMine(Long enterpriseId, String status) {
        var query = Wrappers.<Order>lambdaQuery()
                .and(w -> w.eq(Order::getBuyerId, enterpriseId)
                        .or()
                        .eq(Order::getSellerId, enterpriseId))
                .orderByDesc(Order::getId);
        if (status != null && !status.isBlank()) {
            query.eq(Order::getStatus, status);
        }
        return orderMapper.selectList(query);
    }

    public Order get(Long orderId, Long enterpriseId) {
        return loadParticipant(orderId, enterpriseId);
    }

    public List<OrderStatusLog> history(Long orderId, Long enterpriseId) {
        loadParticipant(orderId, enterpriseId);
        return statusLogMapper.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, orderId)
                .orderByAsc(OrderStatusLog::getId));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Moves title for the accepted quantity.
     *
     * <p>Two effects that must both happen or neither: the seller's note loses
     * the goods, and the buyer gains a note for the same amount in the same
     * warehouse. They are in one transaction because a trade that took goods
     * from one party without delivering them to the other is not a trade, it is
     * a loss.
     *
     * @return the seller's freeze that was consumed, for the audit trail
     */
    private Long transferGoods(Listing listing, BigDecimal quantity, Long buyerId, Long sellerId) {
        Long goodsFreezeId = listing.getFreezeId();
        Long sellerNoteId = null;

        if (goodsFreezeId != null) {
            // Spend the reservation made when the listing was published. What is
            // not taken stays frozen and stays on offer.
            var freezeBefore = freezeService.findFrozen(sellerId, goodsFreezeId);
            sellerNoteId = freezeBefore.getEntityId();

            // The remainder is a NEW record — partial consumption closes the
            // original rather than rewriting it — so the listing has to be
            // pointed at the new one before this method returns. Without this
            // the listing keeps referring to a settled reservation, and from
            // then on it can neither be accepted again nor withdrawn: both
            // paths load the freeze by that id and are refused.
            Long remainderId = freezeService.consumeInventoryPartial(sellerId, goodsFreezeId, quantity);
            listing.setFreezeId(remainderId);
        } else {
            // A BUY listing: the goods come from the accepting seller's own
            // stock, so find a note that can cover it.
            InventoryNote source = findSellableNote(sellerId, listing.getCategoryId(), quantity);
            if (source == null) {
                throw BusinessException.of(ResultCode.INVENTORY_QUANTITY_INSUFFICIENT,
                        "卖方可用库存不足");
            }
            sellerNoteId = source.getId();
            InventoryNote note = inventoryNoteMapper.selectById(source.getId());
            note.setTotalQuantity(note.getTotalQuantity().subtract(quantity));
            note.setAvailableQuantity(note.getAvailableQuantity().subtract(quantity));
            if (note.getTotalQuantity().signum() == 0) {
                note.setStatus(InventoryNote.Status.DELIVERED);
            }
            if (inventoryNoteMapper.updateById(note) == 0) {
                throw BusinessException.of(ResultCode.CONFLICT, "库存正在被其他操作修改，请重试");
            }
        }

        createBuyerNote(listing, sellerNoteId, buyerId, quantity);
        return goodsFreezeId;
    }

    /** Gives the buyer their own note for what they just bought. */
    private void createBuyerNote(Listing listing, Long sourceNoteId, Long buyerId, BigDecimal quantity) {
        InventoryNote source = sourceNoteId == null ? null : inventoryNoteMapper.selectById(sourceNoteId);

        InventoryNote note = new InventoryNote();
        note.setNoteNo(nextNo("IN"));
        note.setEnterpriseId(buyerId);
        note.setCategoryId(listing.getCategoryId());
        note.setWarehouseId(listing.getWarehouseId() == null ? 2001L : listing.getWarehouseId());
        note.setCommodityName(listing.getCommodityName());
        note.setBrand(listing.getBrand());
        note.setOrigin(listing.getOrigin());
        note.setSpec(listing.getSpec());
        note.setTotalQuantity(quantity);
        note.setAvailableQuantity(quantity);
        note.setFrozenQuantity(BigDecimal.ZERO);
        note.setUnit(listing.getUnit());
        note.setStatus(InventoryNote.Status.IN_STOCK);
        note.setVersion(0);
        note.setRemark(source == null
                ? "摘牌成交自动生成"
                : "摘牌成交自动生成，来源库存单 " + source.getNoteNo());
        inventoryNoteMapper.insert(note);
    }

    private InventoryNote findSellableNote(Long enterpriseId, Long categoryId, BigDecimal quantity) {
        return inventoryNoteMapper.selectList(Wrappers.<InventoryNote>lambdaQuery()
                        .eq(InventoryNote::getEnterpriseId, enterpriseId)
                        .eq(InventoryNote::getCategoryId, categoryId)
                        .in(InventoryNote::getStatus,
                                InventoryNote.Status.IN_STOCK,
                                InventoryNote.Status.PARTIALLY_FROZEN)
                        .ge(InventoryNote::getAvailableQuantity, quantity)
                        .orderByAsc(InventoryNote::getId)
                        .last("limit 1"))
                .stream().findFirst().orElse(null);
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
     * Puts goods back after a cancellation.
     *
     * <p>If the listing is still open the goods are re-reserved against it, so
     * the offer that produced this order stays valid for the next buyer. If it
     * is not, they simply return to the seller.
     */
    private void restoreGoods(Order order) {
        Listing listing = order.getListingId() == null
                ? null : listingMapper.selectById(order.getListingId());

        if (order.getGoodsFreezeId() == null) {
            // An unconfirmed acceptance: nothing ever moved, so there is
            // nothing to unfreeze and no note to recreate. Only the offer's
            // remaining quantity has to come back, or the listing would look
            // like it sold something it never sold.
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
                    com.bulk.trade.settlement.entity.FreezeRecord.BizType.LISTING,
                    listing.getId(),
                    "订单取消，货权归还挂牌");
            returnQuantityToListing(listing, order.getQuantity());
            return;
        }

        // No listing to go back to, so return the goods outright. A fresh
        // available balance is created on a new note because the original was
        // already partially consumed.
        createCancellationReturn(order);
    }

    /**
     * Puts quantity back on an offer and restates what the offer is.
     *
     * <p>The status follows from the arithmetic rather than being asserted:
     * only when the full original quantity is back on the table is the listing
     * once again simply an open offer.
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
     * Whether a listing can still take goods back.
     *
     * <p>Looser than {@link Listing#isOpenForTrade()} on purpose: a listing
     * whose remainder was fully taken reads as FILLED, yet a lapsed acceptance
     * has to be able to reopen it. Only a listing the owner closed, or one that
     * ran out of time, is genuinely past accepting anything.
     */
    private boolean canReopen(Listing listing) {
        return listing != null
                && !Listing.Status.CLOSED.equals(listing.getStatus())
                && !Listing.Status.EXPIRED.equals(listing.getStatus());
    }

    /** Re-creates the seller's holding when there is no listing left to restore to. */
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
        inventoryNoteMapper.insert(note);
    }

    private Long sellerNoteIdOf(Listing listing) {
        if (listing.getFreezeId() == null) {
            // Reachable only if a listing is open with nothing reserved behind
            // it, which the platform does not produce — the freeze is released
            // only when the remainder is gone, and that is the point at which
            // the listing stops being open. Said plainly rather than left to
            // become a null pointer three frames down.
            throw BusinessException.of(ResultCode.CONFLICT,
                    "该挂牌没有可归还的冻结货物，请刷新后重试");
        }
        // The freeze that backed the listing points at the note the goods sit on.
        var freeze = freezeService.findFrozen(listing.getEnterpriseId(), listing.getFreezeId());
        return freeze.getEntityId();
    }

    /** Writes the listing back, so a changed reservation pointer is not lost. */
    private void persistListingReservation(Listing listing) {
        if (listingMapper.updateById(listing) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该挂牌正在被其他操作修改，请重试");
        }
    }

    /**
     * Moves an order to a new status, refusing transitions the table does not
     * allow. Routing every change through here is what makes the table the
     * single description of the lifecycle rather than documentation of it.
     */
    private void transition(Order order, String to, LoginUser user, String reason) {
        transition(order, to, user.getUserId(), user.getUsername(), reason);
    }

    /**
     * The same move, attributed to whoever made it.
     *
     * <p>Split out for the scheduled sweep, which moves orders on nobody's
     * behalf. Attributing its work to a real user would be a lie in the one
     * record that exists to settle disputes, so it signs as {@code system}.
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
     * Loads the order's listing and checks the caller owns it.
     *
     * <p>Ownership is read from the listing rather than inferred from the
     * order's buyer/seller columns. Which of those two the lister is depends on
     * the listing's direction, and inferring it is the kind of shortcut that is
     * right until the day someone enables a second direction and silently
     * checks the wrong party.
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
     * How long the lister has to answer.
     *
     * <p>Capped at the listing's own expiry: an acceptance that outlives the
     * offer it accepted would be a question about something no longer on the
     * table, and the expiry sweep would have released the goods behind it.
     */
    private OffsetDateTime answerDeadlineFor(Listing listing) {
        OffsetDateTime byWindow = OffsetDateTime.now().plus(properties.effectiveConfirmWindow());
        OffsetDateTime validUntil = listing.getValidUntil();
        return validUntil != null && validUntil.isBefore(byWindow) ? validUntil : byWindow;
    }

    /**
     * Tells the named enterprises that their pending work changed.
     *
     * <p>Only names them — the event carries no task data. Recomputing the task
     * here would put a second copy of "what counts as pending" into the event,
     * and the two copies would eventually disagree; the listener refetches
     * through {@link TaskService} instead, so that question has one answer.
     *
     * <p>Both parties are told even when only one of them gained work: a move
     * by one side changes what the other sees, and a stale screen is the
     * complaint this exists to fix.
     */
    private void publishTaskChange(String reason, Order order) {
        eventPublisher.publishEvent(
                new TaskChangedEvent(reason, order.getBuyerId(), order.getSellerId()));
    }

    /** Announces a completed trade. The trading module does not know a market
     * feed exists; whoever cares subscribes. */
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
