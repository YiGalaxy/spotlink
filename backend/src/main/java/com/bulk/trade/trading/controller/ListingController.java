package com.bulk.trade.trading.controller;

import com.bulk.trade.inventory.service.InventoryViewAssembler;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.shared.web.ApiResponse;
import com.bulk.trade.trading.dto.ListingPublishRequest;
import com.bulk.trade.trading.dto.ListingView;
import com.bulk.trade.trading.dto.OrderAcceptRequest;
import com.bulk.trade.trading.dto.OrderView;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.service.ListingService;
import com.bulk.trade.trading.service.OrderService;
import com.bulk.trade.trading.service.TradingViewAssembler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Listings and the acceptance that turns one into an order.
 *
 * <p>Sitting together because they are two halves of one act: publishing an
 * offer, and taking it.
 */
@Tag(name = "挂牌交易", description = "挂牌、摘牌与成交")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;
    private final OrderService orderService;
    private final TradingViewAssembler viewAssembler;

    @Operation(summary = "挂单大厅",
            description = "各企业当前有效的挂牌。挂牌是公开信息，任何已认证企业都能浏览。")
    @GetMapping("/listings/market")
    public ApiResponse<List<ListingView>> market(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String side,
            @RequestParam(required = false) String keyword) {
        List<Listing> listings = listingService.browse(categoryId, side, keyword);
        return ApiResponse.success(viewAssembler.toListingViews(
                listings, SecurityUtils.currentEnterpriseIdOrNull()));
    }

    @Operation(summary = "我的挂牌")
    @GetMapping("/listings/mine")
    public ApiResponse<List<ListingView>> mine() {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        return ApiResponse.success(viewAssembler.toListingViews(
                listingService.listMine(enterpriseId), enterpriseId));
    }

    @Operation(summary = "发布挂牌",
            description = "卖方挂牌会立即冻结对应库存。发布挂牌即是发出要约，有效期届满自动失效并解冻。")
    @PostMapping("/listings")
    public ApiResponse<ListingView> publish(@Valid @RequestBody ListingPublishRequest request) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        return ApiResponse.success(viewAssembler.toListingViews(
                List.of(listingService.publish(request, SecurityUtils.currentUser())),
                enterpriseId).get(0));
    }

    @Operation(summary = "撤牌", description = "撤回尚未成交的部分并解冻，已成交的不受影响")
    @PostMapping("/listings/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id) {
        listingService.close(id, SecurityUtils.currentEnterpriseId());
        return ApiResponse.success();
    }

    @Operation(summary = "摘牌",
            description = "接受对方的挂牌，即作出承诺。货权当场转移：卖方库存减少，买方获得等量库存单。")
    @PostMapping("/listings/{id}/accept")
    public ApiResponse<OrderView> accept(@PathVariable Long id,
                                         @Valid @RequestBody OrderAcceptRequest request) {
        Long enterpriseId = SecurityUtils.currentEnterpriseId();
        Order order = orderService.accept(id, request, SecurityUtils.currentUser());
        return ApiResponse.success(viewAssembler.toOrderViews(List.of(order), enterpriseId).get(0));
    }
}
