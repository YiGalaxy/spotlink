package com.spotlink.trading.service;

import com.spotlink.commodity.entity.CommodityCategory;
import com.spotlink.commodity.service.access.CommodityCategoryAccess;
import com.spotlink.inventory.entity.InventoryNote;
import com.spotlink.inventory.service.access.InventoryNoteAccess;
import com.spotlink.inventory.service.InventoryRules;
import com.spotlink.settlement.entity.FreezeRecord;
import com.spotlink.settlement.service.FreezeService;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.web.ResultCode;
import com.spotlink.trading.dto.ListingPublishRequest;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.entity.Order;
import com.spotlink.trading.entity.OrderStatus;
import com.spotlink.trading.mapper.ListingMapper;
import com.spotlink.trading.mapper.OrderMapper;
import com.spotlink.warehouse.entity.Warehouse;
import com.spotlink.warehouse.service.access.WarehouseAccess;
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
 * 挂牌的发布与撤回。
 *
 * <p>这里最重要的性质是挂牌对货物做了什么。它不取走货物，它是预留货物。卖方
 * 自始至终保留所有权，而这份预留会在挂牌关闭或过期时释放。这就是为什么冻结的
 * 创建与释放只发生在这两个方法里，别处没有。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ListingMapper listingMapper;
    private final OrderMapper orderMapper;
    private final InventoryNoteAccess inventoryNoteAccess;
    private final CommodityCategoryAccess categoryAccess;
    private final WarehouseAccess warehouseAccess;
    private final FreezeService freezeService;
    private final ObjectMapper objectMapper;

    /**
     * 发布一份要约。
     *
     * <p>SELL 挂牌的货物是在创建挂牌的同一个事务里被冻结的。若把两者拆开，
     * 就会出现一份背后空无一物的挂牌——一份出卖同时已被许诺给别处的货物的
     * 要约。
     */
    @Transactional
    public Listing publish(ListingPublishRequest request, LoginUser user) {
        Long enterpriseId = requireEnterprise(user);

        if (!Listing.Side.SELL.equals(request.side()) && !Listing.Side.BUY.equals(request.side())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "挂牌方向必须是 SELL 或 BUY");
        }
        InventoryRules.quantity(request.quantity());
        if (request.validUntil() == null || !request.validUntil().isAfter(OffsetDateTime.now())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "有效期必须晚于当前时间");
        }

        String priceType = normalisePriceType(request);
        String confirmMode = normaliseConfirmMode(request);
        InventoryNote source = Listing.Side.SELL.equals(request.side())
                ? sellSource(request.inventoryNoteId(), enterpriseId) : null;
        Long categoryId = source == null ? request.categoryId() : source.getCategoryId();
        CommodityCategory category = categoryId == null ? null : categoryAccess.selectById(categoryId);
        if (category == null) {
            throw BusinessException.of(ResultCode.CATEGORY_NOT_FOUND);
        }
        if (!Integer.valueOf(1).equals(category.getStatus()) || categoryAccess.hasChildren(categoryId)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "请选择启用的具体商品品类");
        }
        if (source == null && (request.commodityName() == null || request.commodityName().isBlank())) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "请填写商品名称");
        }
        Long warehouseId = source == null ? request.warehouseId() : source.getWarehouseId();
        if (source != null || warehouseId != null) {
            Warehouse warehouse = warehouseId == null ? null : warehouseAccess.selectById(warehouseId);
            if (warehouse == null || !Integer.valueOf(1).equals(warehouse.getStatus())) {
                throw BusinessException.of(ResultCode.NOT_FOUND, "交收仓库不存在或已停用");
            }
        }
        String delivery = request.deliveryMethod() == null
                ? Listing.DeliveryMethod.SELF_PICKUP : request.deliveryMethod();
        if (!Listing.DeliveryMethod.SELF_PICKUP.equals(delivery) && !Listing.DeliveryMethod.DELIVERED.equals(delivery)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "交收方式必须是自提或送到");
        }
        if (request.paymentTerms() != null && (request.paymentTerms().isBlank() || request.paymentTerms().length() > 32)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "付款条款须为 1 到 32 字");
        }

        Listing listing = new Listing();
        listing.setListingNo(nextNo("LS"));
        listing.setEnterpriseId(enterpriseId);
        listing.setSide(request.side());
        listing.setCategoryId(categoryId);
        listing.setCommodityName(source == null ? request.commodityName().trim() : source.getCommodityName());
        listing.setBrand(source == null ? request.brand() : source.getBrand());
        listing.setOrigin(source == null ? request.origin() : source.getOrigin());
        listing.setSpec(source == null ? writeSpec(request.spec()) : source.getSpec());
        listing.setQuantity(request.quantity());
        listing.setRemainingQuantity(request.quantity());
        listing.setUnit(source == null ? (request.unit() == null || request.unit().isBlank()
                ? category.getUnit() : request.unit()) : source.getUnit());
        listing.setPrice(Listing.PriceType.FIXED.equals(priceType) ? request.price() : null);
        listing.setPriceType(priceType);
        listing.setConfirmMode(confirmMode);
        listing.setWarehouseId(warehouseId);
        listing.setDeliveryMethod(delivery);
        listing.setPaymentTerms(request.paymentTerms() == null
                ? "MARGIN_THEN_BALANCE" : request.paymentTerms());
        listing.setValidUntil(request.validUntil());
        listing.setStatus(Listing.Status.OPEN);
        listing.setVersion(0);
        listing.setRemark(request.remark());

        if (Listing.Side.SELL.equals(request.side())) {
            listing.setFreezeId(freezeForListing(source, request.quantity(), enterpriseId));
        }

        listingMapper.insert(listing);

        // 冻结是在挂牌还没有 id 的时候创建的，因此它当时无法记录自己属于哪份
        // 挂牌。现在有了 id，就补上。
        //
        // 多这一次写入是值得的：没有它，一条冻结记录只能说明“有些货物被预留
        // 了”，无法回溯到它们究竟是为哪份要约而预留。这个关联是存在的——挂牌
        // 指向自己的冻结——但只有一个方向，于是反过来对账（这笔类资金的预留，
        // 到底对应哪份要约？）不扫全表就做不到。
        if (listing.getFreezeId() != null) {
            freezeService.attributeTo(listing.getFreezeId(), listing.getId());
        }
        log.info("Listing {} published by enterprise {}: {} {} of {}",
                listing.getListingNo(), enterpriseId, request.quantity().toPlainString(),
                listing.getUnit(), listing.getCommodityName());
        return listing;
    }

    /**
     * 撤回一份挂牌，释放它所预留的一切。
     *
     * <p>只有所有者可以撤回，且只在其仍处于开放状态时可以。一份已被部分摘走
     * 的挂牌仍然可以撤回——剩余部分被释放，已经达成的交易不受影响。
     *
     * <p><b>但当有一笔摘牌正在等待答复时除外。</b>那笔摘牌是向挂牌方提出的
     * 一个问题，而它背后的货物正是为这个答复而预留的。此时撤回会释放那笔预留，
     * 让等待中的订单永远无法答复，因此在这个问题了结之前，撤牌会被拒绝。拒绝
     * 是更安全的失败方式：挂牌方仍然可以拒绝摘牌，再按一次按钮，这份挂牌就
     * 可以撤了。
     */
    @Transactional
    public void close(Long listingId, Long enterpriseId) {
        Listing listing = loadOwned(listingId, enterpriseId);

        // 这一问放在“它是否还开放”之前，因为一份剩余部分被全部摘走的挂牌
        // 读起来是 FILLED 而不是开放——然而其所有者无法撤牌的原因并不是它
        // 已终结，而是有个问题正等着他回答。那才是告诉他下一步该做什么的
        // 答案；另一个答案只是说不行。
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
     * 让已过截止时间的挂牌过期。
     *
     * <p>由定时扫描驱动，而不是由读取驱动：<em>读取</em>一份已过期的挂牌不应
     * 该改变它，而一个因被浏览就顺带改动数据行的行情页是个陷阱。
     *
     * <p>仍有摘牌在等待答复的挂牌会被跳过，理由与拒绝撤牌相同。这能自行了结
     * 而不会死锁：订单扫描会处理那笔等待中的摘牌——若挂牌方始终不答复，就由
     * 过期处理——随后下一轮扫描就会让该挂牌过期。
     *
     * @return 有多少份挂牌被置为过期
     */
    @Transactional
    public int expireOverdue() {
        List<Listing> overdue = listingMapper.findExpiredOpen(OffsetDateTime.now());

        int expired = 0;
        for (Listing listing : overdue) {
            if (countAwaitingAcceptance(listing.getId()) > 0) {
                continue;
            }
            releaseListingFreeze(listing);
            listing.setStatus(Listing.Status.EXPIRED);
            if (listingMapper.updateById(listing) == 0) {
                throw BusinessException.of(ResultCode.CONFLICT, "挂牌已被其他操作修改，过期扫描稍后重试");
            }
            expired++;
        }
        if (expired > 0) {
            log.info("Expired {} listing(s)", expired);
        }
        return expired;
    }

    /** 行情大厅：来自所有企业的开放中挂牌，不含调用方自己的。 */
    public List<Listing> browse(Long categoryId, String side, String keyword) {
        return listingMapper.browseOpen(categoryId, side, keyword);
    }

    public List<Listing> listMine(Long enterpriseId) {
        return listingMapper.findOwned(enterpriseId);
    }

    public Listing get(Long id, Long enterpriseId) {
        Listing listing = listingMapper.selectById(id);
        if (listing == null) {
            throw BusinessException.of(ResultCode.LISTING_NOT_FOUND);
        }
        // 挂牌在开放期间是公开的，因此读取一份不属于自己的挂牌是允许的；
        // 对它采取动作则不允许，这一点在动作发生处强制执行。
        return listing;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 冻结 SELL 挂牌背后的货物。
     *
     * <p>库存单由卖方明确指定，而不是由平台从他碰巧持有的库存里挑一份。预留
     * 卖方没有指明的货物，会悄悄占用掉他可能另有安排的库存。
     */
    private InventoryNote sellSource(Long noteId, Long enterpriseId) {
        if (noteId == null) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "卖方挂牌必须指定电子库存单");
        }

        InventoryNote note = inventoryNoteAccess.selectById(noteId);
        if (note == null || !note.getEnterpriseId().equals(enterpriseId)) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_FOUND);
        }
        if (!InventoryNote.Status.isTradable(note.getStatus())) {
            throw BusinessException.of(ResultCode.INVENTORY_NOTE_NOT_AVAILABLE);
        }
        return note;
    }

    private Long freezeForListing(InventoryNote note, BigDecimal quantity, Long enterpriseId) {
        if (note.getAvailableQuantity().compareTo(quantity) < 0) {
            throw BusinessException.of(ResultCode.INVENTORY_QUANTITY_INSUFFICIENT,
                    "库存单可用数量 %s %s，少于挂牌数量 %s".formatted(
                            note.getAvailableQuantity().stripTrailingZeros().toPlainString(),
                            note.getUnit(),
                            quantity.stripTrailingZeros().toPlainString()));
        }

        FreezeRecord freeze = freezeService.freezeInventory(
                enterpriseId,
                note.getId(),
                quantity,
                FreezeRecord.BizType.LISTING,
                null,
                "挂牌冻结", note.getVersion());
        return freeze.getId();
    }

    /**
     * 释放挂牌的冻结，如果它有的话。
     *
     * <p>冻结不存在、数量冲突或已结清都必须中止事务，不能把释放失败记成撤牌成功。
     */
    private void releaseListingFreeze(Listing listing) {
        if (listing.getFreezeId() == null) {
            return;
        }
        freezeService.releaseInventory(listing.getEnterpriseId(), listing.getFreezeId());
    }

    /**
     * 这份挂牌还有多少笔摘牌未获答复。
     *
     * <p>每一笔都在这份挂牌的冻结上占着一份预留，因此这个计数就是横在撤牌与
     * 一笔永远无人能答复的订单之间的东西。
     */
    private long countAwaitingAcceptance(Long listingId) {
        return orderMapper.countPendingConfirmations(listingId);
    }

    /**
     * 解析所请求的确认方式。
     *
     * <p>这里和数据库里一样拒绝给 BUY 挂牌用 MANUAL。保证来自那条 CHECK
     * 约束；这里是解释，因为约束违例到达客户端时是一个 500，而一个提出了合理
     * 诉求的人应当被告知为什么这项功能不提供。
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
            // 由有无取值来推断，使该字段对客户端保持可选，同时数据库看到的
            // 仍然正好是两种形态之一。
            priceType = request.price() == null ? Listing.PriceType.NEGOTIABLE : Listing.PriceType.FIXED;
        }
        if (!Listing.PriceType.FIXED.equals(priceType)
                && !Listing.PriceType.NEGOTIABLE.equals(priceType)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "价格类型必须是 FIXED 或 NEGOTIABLE");
        }
        if (Listing.PriceType.FIXED.equals(priceType)) TradingNumbers.price(request.price());
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
            // “不是你的”和“不存在”以同样的方式报告，这样调用方就无法通过
            // 试探来摸出其它公司的挂牌 id。
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
