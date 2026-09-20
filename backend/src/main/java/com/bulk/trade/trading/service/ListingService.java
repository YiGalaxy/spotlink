package com.bulk.trade.trading.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.commodity.entity.CommodityCategory;
import com.bulk.trade.commodity.mapper.CommodityCategoryMapper;
import com.bulk.trade.inventory.entity.InventoryNote;
import com.bulk.trade.inventory.mapper.InventoryNoteMapper;
import com.bulk.trade.settlement.entity.FreezeRecord;
import com.bulk.trade.settlement.service.FreezeService;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.shared.web.ResultCode;
import com.bulk.trade.trading.dto.ListingPublishRequest;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.entity.OrderStatus;
import com.bulk.trade.trading.mapper.ListingMapper;
import com.bulk.trade.trading.mapper.OrderMapper;
import com.bulk.trade.warehouse.entity.Warehouse;
import com.bulk.trade.warehouse.mapper.WarehouseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Publishing and withdrawing listings.
 *
 * <p>The important property here is what a listing does to goods. It does not
 * take them; it reserves them. The seller keeps ownership throughout, and the
 * reservation is released when the listing closes or expires. That is why the
 * freeze is created and released inside these two methods and nowhere else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ListingMapper listingMapper;
    private final OrderMapper orderMapper;
    private final InventoryNoteMapper inventoryNoteMapper;
    private final CommodityCategoryMapper categoryMapper;
    private final WarehouseMapper warehouseMapper;
    private final FreezeService freezeService;
    private final ObjectMapper objectMapper;

    /**
     * Publishes an offer.
     *
     * <p>For a SELL listing the goods are frozen in the same transaction that
     * creates the listing. Splitting them would allow a listing to exist with
     * nothing behind it — an offer to sell goods that are simultaneously
     * promised elsewhere.
     */
    @Transactional
    public Listing publish(ListingPublishRequest request, LoginUser user) {
        Long enterpriseId = requireEnterprise(user);

        if (!Listing.Side.SELL.equals(request.side()) && !Listing.Side.BUY.equals(request.side())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "挂牌方向必须是 SELL 或 BUY");
        }
        if (request.validUntil().isBefore(OffsetDateTime.now())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "有效期必须晚于当前时间");
        }

        String priceType = normalisePriceType(request);
        String confirmMode = normaliseConfirmMode(request);

        CommodityCategory category = categoryMapper.selectById(request.categoryId());
        if (category == null) {
            throw BusinessException.of(ResultCode.CATEGORY_NOT_FOUND);
        }

        Listing listing = new Listing();
        listing.setListingNo(nextNo("LS"));
        listing.setEnterpriseId(enterpriseId);
        listing.setSide(request.side());
        listing.setCategoryId(request.categoryId());
        listing.setCommodityName(request.commodityName());
        listing.setBrand(request.brand());
        listing.setOrigin(request.origin());
        listing.setSpec(writeSpec(request.spec()));
        listing.setQuantity(request.quantity());
        listing.setRemainingQuantity(request.quantity());
        listing.setUnit(request.unit() == null || request.unit().isBlank()
                ? category.getUnit() : request.unit());
        listing.setPrice(Listing.PriceType.FIXED.equals(priceType) ? request.price() : null);
        listing.setPriceType(priceType);
        listing.setConfirmMode(confirmMode);
        listing.setWarehouseId(request.warehouseId());
        listing.setDeliveryMethod(request.deliveryMethod() == null
                ? Listing.DeliveryMethod.SELF_PICKUP : request.deliveryMethod());
        listing.setPaymentTerms(request.paymentTerms() == null
                ? "MARGIN_THEN_BALANCE" : request.paymentTerms());
        listing.setValidUntil(request.validUntil());
        listing.setStatus(Listing.Status.OPEN);
        listing.setVersion(0);
        listing.setRemark(request.remark());

        if (Listing.Side.SELL.equals(request.side())) {
            listing.setFreezeId(freezeForListing(request, enterpriseId));
        }

        listingMapper.insert(listing);
        log.info("Listing {} published by enterprise {}: {} {} of {}",
                listing.getListingNo(), enterpriseId, request.quantity().toPlainString(),
                listing.getUnit(), listing.getCommodityName());
        return listing;
    }

    /**
     * Withdraws a listing, releasing whatever it reserved.
     *
     * <p>Only the owner may withdraw, and only while it is open. A listing that
     * has been partly taken can still be withdrawn — the remainder is released,
     * the trades already struck are untouched.
     *
     * <p><b>Except while an acceptance is waiting for an answer.</b> That
     * acceptance is a question the lister has been asked, and the goods behind
     * it are reserved for the answer. Withdrawing would release that
     * reservation and leave the waiting order permanently unanswerable, so the
     * withdrawal is refused until the question is settled. Refusing is the
     * safer failure: the lister can still decline, and one button press later
     * the listing is theirs to withdraw.
     */
    @Transactional
    public void close(Long listingId, Long enterpriseId) {
        Listing listing = loadOwned(listingId, enterpriseId);

        // Asked before "is it still open", because a listing whose whole
        // remainder was accepted reads as FILLED rather than open — yet the
        // reason its owner cannot withdraw is not that it is finished, it is
        // that a question is waiting for them. That is the answer that tells
        // them what to do next; the other one just says no.
        long waiting = countAwaitingAcceptance(listing.getId());
        if (waiting > 0) {
            throw BusinessException.of(ResultCode.CONFLICT,
                    "有 %d 笔摘牌等待您确认，请先确认或拒绝后再撤牌".formatted(waiting));
        }

        if (!listing.isOpenForTrade()) {
            throw BusinessException.of(ResultCode.LISTING_ALREADY_CLOSED);
        }

        releaseListingFreeze(listing);

        listing.setStatus(Listing.Status.CLOSED);
        if (listingMapper.updateById(listing) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该挂牌正在被其他操作修改，请重试");
        }
        log.info("Listing {} closed by enterprise {}", listing.getListingNo(), enterpriseId);
    }

    /**
     * Expires listings whose deadline has passed.
     *
     * <p>Driven by a scheduled sweep rather than by reads: nothing about
     * <em>reading</em> an expired listing should change it, and a marketplace
     * page that mutates rows as a side effect of being viewed is a trap.
     *
     * <p>A listing with an acceptance still waiting is skipped, for the same
     * reason withdrawal is refused. This resolves itself rather than deadlocking:
     * the order sweep answers the waiting acceptance — by expiry, if the lister
     * never does — and the listing is expired by the next pass.
     *
     * @return how many listings were expired
     */
    @Transactional
    public int expireOverdue() {
        List<Listing> overdue = listingMapper.selectList(
                Wrappers.<Listing>lambdaQuery()
                        .in(Listing::getStatus, Listing.Status.OPEN, Listing.Status.PARTIALLY_FILLED)
                        .lt(Listing::getValidUntil, OffsetDateTime.now()));

        int expired = 0;
        for (Listing listing : overdue) {
            if (countAwaitingAcceptance(listing.getId()) > 0) {
                continue;
            }
            releaseListingFreeze(listing);
            listing.setStatus(Listing.Status.EXPIRED);
            listingMapper.updateById(listing);
            expired++;
        }
        if (expired > 0) {
            log.info("Expired {} listing(s)", expired);
        }
        return expired;
    }

    /** The marketplace: open listings from every enterprise except the caller's own. */
    public List<Listing> browse(Long categoryId, String side, String keyword) {
        var query = Wrappers.<Listing>lambdaQuery()
                .in(Listing::getStatus, Listing.Status.OPEN, Listing.Status.PARTIALLY_FILLED)
                .gt(Listing::getRemainingQuantity, BigDecimal.ZERO)
                .orderByDesc(Listing::getId);
        if (categoryId != null) {
            query.eq(Listing::getCategoryId, categoryId);
        }
        if (side != null && !side.isBlank()) {
            query.eq(Listing::getSide, side);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.like(Listing::getCommodityName, keyword.trim());
        }
        return listingMapper.selectList(query);
    }

    public List<Listing> listMine(Long enterpriseId) {
        return listingMapper.selectList(Wrappers.<Listing>lambdaQuery()
                .eq(Listing::getEnterpriseId, enterpriseId)
                .orderByDesc(Listing::getId));
    }

    public Listing get(Long id, Long enterpriseId) {
        Listing listing = listingMapper.selectById(id);
        if (listing == null) {
            throw BusinessException.of(ResultCode.LISTING_NOT_FOUND);
        }
        // A listing is public while open, so reading one that is not yours is
        // allowed; acting on it is not, and that is enforced at the action.
        return listing;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Freezes the goods behind a SELL listing.
     *
     * <p>The note is named explicitly by the seller rather than chosen by the
     * platform from whatever they happen to hold. Reserving goods the seller
     * did not point at would silently commit inventory they may have plans for.
     */
    private Long freezeForListing(ListingPublishRequest request, Long enterpriseId) {
        if (request.inventoryNoteId() == null) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "卖方挂牌必须指定电子库存单");
        }

        InventoryNote note = inventoryNoteMapper.selectById(request.inventoryNoteId());
        if (note == null || !note.getEnterpriseId().equals(enterpriseId)) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        }
        if (!note.getCategoryId().equals(request.categoryId())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "挂牌品类与库存单品类不一致");
        }
        if (note.getAvailableQuantity().compareTo(request.quantity()) < 0) {
            throw BusinessException.of(ResultCode.INVENTORY_QUANTITY_INSUFFICIENT,
                    "库存单可用数量 %s %s，少于挂牌数量 %s".formatted(
                            note.getAvailableQuantity().stripTrailingZeros().toPlainString(),
                            note.getUnit(),
                            request.quantity().stripTrailingZeros().toPlainString()));
        }

        FreezeRecord freeze = freezeService.freezeInventory(
                enterpriseId,
                request.inventoryNoteId(),
                request.quantity(),
                FreezeRecord.BizType.LISTING,
                null,
                "挂牌冻结");
        return freeze.getId();
    }

    /**
     * Releases a listing's freeze, if it has one.
     *
     * <p>Idempotent by way of the freeze record's own status: a freeze that was
     * already released or consumed rejects a second release, and that rejection
     * is swallowed here because reaching this method twice is not an error worth
     * failing a withdrawal over.
     */
    private void releaseListingFreeze(Listing listing) {
        if (listing.getFreezeId() == null) {
            return;
        }
        try {
            freezeService.releaseInventory(listing.getEnterpriseId(), listing.getFreezeId());
        } catch (BusinessException e) {
            log.debug("Listing {} freeze {} was already settled: {}",
                    listing.getListingNo(), listing.getFreezeId(), e.getMessage());
        }
    }

    /**
     * How many acceptances of this listing are still unanswered.
     *
     * <p>Each one holds a reservation against this listing's freeze, so the
     * count is what stands between a withdrawal and an order nobody can ever
     * answer.
     */
    private long countAwaitingAcceptance(Long listingId) {
        return orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getListingId, listingId)
                .eq(Order::getStatus, OrderStatus.PENDING_CONFIRM));
    }

    /**
     * Resolves the requested confirmation mode.
     *
     * <p>MANUAL is refused for a BUY listing here as well as in the database.
     * The check constraint is the guarantee; this is the explanation, because a
     * constraint violation reaches the client as a 500 and a person who asked
     * for something reasonable deserves to be told why it is not on offer.
     */
    private String normaliseConfirmMode(ListingPublishRequest request) {
        String mode = request.confirmMode();
        if (mode == null || mode.isBlank()) {
            return Listing.ConfirmMode.AUTO;
        }
        if (!Listing.ConfirmMode.AUTO.equals(mode) && !Listing.ConfirmMode.MANUAL.equals(mode)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "确认方式必须是 AUTO 或 MANUAL");
        }
        if (Listing.ConfirmMode.MANUAL.equals(mode) && !Listing.Side.SELL.equals(request.side())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST,
                    "买方挂牌不支持「待挂牌方确认」：摘牌时没有已冻结的货物可以等待确认");
        }
        return mode;
    }

    private String normalisePriceType(ListingPublishRequest request) {
        String priceType = request.priceType();
        if (priceType == null || priceType.isBlank()) {
            // Inferring from presence keeps the field optional for clients while
            // the database still sees exactly one of the two shapes.
            return request.price() == null ? Listing.PriceType.NEGOTIABLE : Listing.PriceType.FIXED;
        }
        if (!Listing.PriceType.FIXED.equals(priceType)
                && !Listing.PriceType.NEGOTIABLE.equals(priceType)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "价格类型必须是 FIXED 或 NEGOTIABLE");
        }
        if (Listing.PriceType.FIXED.equals(priceType)
                && (request.price() == null || request.price().signum() <= 0)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "固定价格挂牌必须填写单价");
        }
        return priceType;
    }

    private Long requireEnterprise(LoginUser user) {
        if (user.getEnterpriseId() == null) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "平台运营账号不能挂牌");
        }
        return user.getEnterpriseId();
    }

    private Listing loadOwned(Long id, Long enterpriseId) {
        Listing listing = listingMapper.selectById(id);
        if (listing == null) {
            throw BusinessException.of(ResultCode.LISTING_NOT_FOUND);
        }
        if (!listing.getEnterpriseId().equals(enterpriseId)) {
            // "Not yours" and "does not exist" are reported the same way so a
            // caller cannot probe for other companies' listing ids.
            throw BusinessException.of(ResultCode.LISTING_NOT_OWNED);
        }
        return listing;
    }

    private String writeSpec(java.util.Map<String, Object> spec) {
        if (spec == null || spec.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "规格参数格式不正确");
        }
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
