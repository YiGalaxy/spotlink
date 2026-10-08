package com.spotlink.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.advisor.service.AdvisorRequestGuard;
import com.spotlink.advisor.service.ConversationService;
import com.spotlink.advisor.tool.ListingAdvisorTools;
import com.spotlink.advisor.tool.ProcurementAdvisorTools;
import com.spotlink.advisor.tool.ToolCallRecorder;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.mapper.ListingMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class AdvisorProcurementTest {
    @Autowired ProcurementAdvisorTools procurement;
    @Autowired ListingAdvisorTools freight;
    @Autowired ListingMapper listings;
    @Autowired ObjectMapper json;
    @Autowired ConversationService conversations;
    @Autowired AdvisorRequestGuard guard;
    private String commodity;

    @BeforeEach void begin() { commodity = "顾问测试" + System.nanoTime(); ToolCallRecorder.begin(); }
    @AfterEach void end() { ToolCallRecorder.drain(); SecurityContextHolder.clearContext(); }

    @Test void priceSortingAndAvailabilityAreRealDatabaseFilters() throws Exception {
        Listing high = insert("100", "20", false);
        Listing low = insert("80", "60", false);
        insert(null, "90", false);
        Listing expired = insert("1", "100", true);
        var result = json.readTree(procurement.findPurchaseOptions(commodity, null, null, null, "吨", null, "PRICE_ASC"));
        assertThat(result.get("符合条件总数").asInt()).isEqualTo(3);
        assertThat(result.get("挂牌").get(0).get("挂牌号").asText()).isEqualTo(low.getListingNo());
        assertThat(result.toString()).doesNotContain(expired.getListingNo());
        assertThat(result.get("挂牌").get(2).get("单价").asText()).isEqualTo("面议");
        assertThat(ToolCallRecorder.products().get(0).id()).isEqualTo(low.getId());
        assertThat(ToolCallRecorder.products().get(0).url()).isEqualTo("/trading?listing=" + low.getId());
        assertThat(result.toString()).contains(high.getListingNo());
    }

    @Test void quantitiesPricesAndInvalidSortDoNotBecomeSql() throws Exception {
        insert("100", "20", false);
        Listing match = insert("80", "60", false);
        var result = json.readTree(procurement.findPurchaseOptions(commodity, null, new BigDecimal("50"), new BigDecimal("90"), "吨", null, "QUANTITY_DESC"));
        assertThat(result.get("挂牌")).hasSize(1);
        assertThat(result.get("挂牌").get(0).get("挂牌号").asText()).isEqualTo(match.getListingNo());
        assertThat(procurement.findPurchaseOptions("' OR 1=1 --", null, null, null, null, null, "LATEST")).contains("没有符合条件");
        assertThat(procurement.findPurchaseOptions("%", null, null, null, null, null, "LATEST")).contains("没有符合条件");
        assertThat(procurement.findPurchaseOptions(commodity, null, null, null, null, null, "price; DROP TABLE t_listing")).contains("排序方式只能");
        assertThat(procurement.findPurchaseOptions(commodity, null, BigDecimal.ONE, null, null, null, null)).contains("补充");
    }

    @Test void freightRequiresSpecificListingAndTransparentUserParameters() {
        Listing item = insert("100", "20", false);
        String estimate = freight.estimateDeliveryCost(item.getListingNo(), "杭州", new BigDecimal("10"), new BigDecimal("80"));
        assertThat(estimate).contains("估算运费：800 元", "不是平台运费报价", "自提");
        assertThat(freight.estimateDeliveryCost(item.getListingNo(), "杭州", null, null)).contains("不能给出实际运费");
        insert("120", "50", false);
        assertThat(freight.estimateDeliveryCost(commodity, "杭州", BigDecimal.ONE, BigDecimal.TEN)).contains("指定挂牌编号");
        assertThat(freight.estimateDeliveryCost(item.getListingNo(), "杭州", new BigDecimal("30"), BigDecimal.TEN)).contains("大于该挂牌剩余量");
    }

    @Test void contextIsOwnerAndEnterpriseBoundAndCanBeCleared() {
        long userId = System.nanoTime();
        authenticate(userId, 999001L);
        var conversation = conversations.create(userId, 999001L, "采购");
        conversations.updateContext(conversation.id(), userId, "电解铜20吨，到杭州");
        assertThat(conversations.get(conversation.id(), userId).contextNote()).contains("杭州");
        assertThatThrownBy(() -> conversations.updateContext(conversation.id(), userId + 1, "改需求")).isInstanceOf(BusinessException.class);
        authenticate(userId, 999002L);
        assertThat(conversations.listMine(userId)).isEmpty();
        assertThatThrownBy(() -> conversations.get(conversation.id(), userId)).isInstanceOf(BusinessException.class);
        authenticate(userId, 999001L);
        conversations.updateContext(conversation.id(), userId, "");
        assertThat(conversations.get(conversation.id(), userId).contextNote()).isEmpty();
        conversations.delete(conversation.id(), userId);
        assertThatThrownBy(() -> conversations.get(conversation.id(), userId)).isInstanceOf(BusinessException.class);
    }

    @Test void redisRateAndConcurrencyAreAtomicAndReleaseLocks() {
        long userId = System.nanoTime();
        try (var lease = guard.acquire(userId)) {
            assertThatThrownBy(() -> guard.acquire(userId)).isInstanceOf(BusinessException.class);
        }
        for (int i = 1; i < 10; i++) try (var lease = guard.acquire(userId)) { }
        assertThatThrownBy(() -> guard.acquire(userId)).isInstanceOf(BusinessException.class);
        List<AdvisorRequestGuard.Lease> leases = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) leases.add(guard.acquire(userId + 100 + i));
            assertThatThrownBy(() -> guard.acquire(userId + 200)).isInstanceOf(BusinessException.class);
        } finally { leases.forEach(AdvisorRequestGuard.Lease::close); }
        try (var lease = guard.acquire(userId + 200)) { }
    }

    private Listing insert(String price, String quantity, boolean expired) {
        Listing item = new Listing();
        item.setListingNo("AT" + System.nanoTime()); item.setEnterpriseId(999003L); item.setSide("SELL");
        item.setCategoryId(1002L); item.setWarehouseId(2001L); item.setCommodityName(commodity); item.setSpec("{}");
        item.setQuantity(new BigDecimal(quantity)); item.setRemainingQuantity(new BigDecimal(quantity)); item.setUnit("吨");
        item.setPrice(price == null ? null : new BigDecimal(price)); item.setPriceType(price == null ? "NEGOTIABLE" : "FIXED");
        item.setConfirmMode("AUTO"); item.setDeliveryMethod("SELF_PICKUP"); item.setStatus("OPEN"); item.setVersion(0);
        item.setValidUntil(OffsetDateTime.now().plusDays(expired ? -1 : 1)); listings.insert(item); return item;
    }

    private void authenticate(long userId, long enterpriseId) {
        var user = LoginUser.builder().userId(userId).enterpriseId(enterpriseId).username("advisor-test").permissions(Set.of()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
