package com.bulk.trade.trading;

import com.bulk.trade.inventory.entity.InventoryNote;
import com.bulk.trade.inventory.mapper.InventoryNoteMapper;
import com.bulk.trade.settlement.entity.FreezeRecord;
import com.bulk.trade.settlement.mapper.FreezeRecordMapper;
import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.trading.dto.ListingPublishRequest;
import com.bulk.trade.trading.dto.OrderAcceptRequest;
import com.bulk.trade.trading.entity.Listing;
import com.bulk.trade.trading.entity.Order;
import com.bulk.trade.trading.mapper.ListingMapper;
import com.bulk.trade.trading.mapper.OrderMapper;
import com.bulk.trade.trading.service.ListingService;
import com.bulk.trade.trading.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A listing taken in parts, against a real database.
 *
 * <p><b>This is the case that was broken and nothing noticed.</b> Partial
 * consumption closes the original freeze and opens a new one for the remainder,
 * and the listing kept pointing at the closed one. From that moment the listing
 * could not be accepted again — the freeze it named had already been settled —
 * and could not be withdrawn either, because withdrawal loads the same freeze.
 * The goods stayed frozen with no way to release them.
 *
 * <p>Nothing failed loudly. The first acceptance worked, which is the case
 * anyone would try, and everything after it needed a second partial sale to
 * show up. So the test walks the whole sequence: sell part, sell more, withdraw
 * the rest, and check the note's three quantities at every step.
 *
 * <p>Requires the docker compose stack from the README to be running.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("挂牌分次成交")
class PartialFillTest {

    private static final Long SELLER = 999_000_301L;
    private static final Long BUYER = 999_000_302L;
    private static final Long CATEGORY = 1002L;
    private static final Long WAREHOUSE = 2001L;

    @Autowired
    private ListingService listingService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ListingMapper listingMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private InventoryNoteMapper noteMapper;

    @Autowired
    private FreezeRecordMapper freezeMapper;

    private Long noteId;
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> listingIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        InventoryNote note = new InventoryNote();
        note.setNoteNo("TEST" + System.nanoTime());
        note.setEnterpriseId(SELLER);
        note.setCategoryId(CATEGORY);
        note.setWarehouseId(WAREHOUSE);
        note.setCommodityName("分次成交测试电解铜");
        note.setSpec("{}");
        note.setTotalQuantity(new BigDecimal("100"));
        note.setAvailableQuantity(new BigDecimal("100"));
        note.setFrozenQuantity(BigDecimal.ZERO);
        note.setUnit("吨");
        note.setStatus(InventoryNote.Status.IN_STOCK);
        note.setVersion(0);
        noteMapper.insert(note);
        noteId = note.getId();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        orderIds.forEach(orderMapper::deleteById);
        orderIds.clear();
        // Listings before the note: the freeze rows reference the note.
        listingIds.forEach(listingId -> {
            Listing listing = listingMapper.selectById(listingId);
            if (listing != null && listing.getFreezeId() != null) {
                FreezeRecord freeze = freezeMapper.selectById(listing.getFreezeId());
                if (freeze != null) {
                    freezeMapper.deleteById(freeze.getId());
                }
            }
            listingMapper.deleteById(listingId);
        });
        listingIds.clear();
        noteMapper.deleteById(noteId);
    }

    @Test
    @DisplayName("摘一部分之后再摘一部分，两次都成立")
    void aListingCanBeTakenInParts() {
        Listing listing = publish("20");
        authenticateAs(SELLER);

        // First acceptance: 8 of 20.
        accept(listing, "8");
        InventoryNote afterFirst = noteMapper.selectById(noteId);
        assertThat(afterFirst.getTotalQuantity()).isEqualByComparingTo("92");
        assertThat(afterFirst.getFrozenQuantity()).isEqualByComparingTo("12");

        // The listing's reservation must have moved to the remainder. It is
        // reloaded rather than reused, because that is what the second
        // acceptance will do.
        Listing reloaded = listingMapper.selectById(listing.getId());
        assertThat(reloaded.getRemainingQuantity()).isEqualByComparingTo("12");
        assertThat(reloaded.getFreezeId())
                .as("the listing points at the live reservation, not the settled one")
                .isNotNull();
        assertThat(freezeMapper.selectById(reloaded.getFreezeId()).getStatus())
                .isEqualTo(FreezeRecord.Status.FROZEN);
        assertThat(freezeMapper.selectById(reloaded.getFreezeId()).getQuantity())
                .isEqualByComparingTo("12");

        // Second acceptance: 6 of the remaining 12. This is the step that used
        // to fail with 「该冻结已解冻」.
        accept(reloaded, "6");
        InventoryNote afterSecond = noteMapper.selectById(noteId);
        assertThat(afterSecond.getTotalQuantity()).isEqualByComparingTo("86");
        assertThat(afterSecond.getFrozenQuantity()).isEqualByComparingTo("6");

        Listing third = listingMapper.selectById(listing.getId());
        assertThat(third.getRemainingQuantity()).isEqualByComparingTo("6");
        assertThat(freezeMapper.selectById(third.getFreezeId()).getQuantity())
                .isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("分次成交后撤牌，剩余冻结要真的释放")
    void withdrawingAfterAPartialSaleReleasesTheRemainder() {
        Listing listing = publish("20");
        authenticateAs(SELLER);
        accept(listing, "8");

        // This used to raise a null pointer, which the caller saw as 系统繁忙,
        // and left the remaining 12 tonnes frozen with no path to release them.
        listingService.close(listing.getId(), SELLER);

        InventoryNote after = noteMapper.selectById(noteId);
        assertThat(after.getFrozenQuantity())
                .as("nothing is reserved once the listing is withdrawn")
                .isEqualByComparingTo("0");
        assertThat(after.getAvailableQuantity()).isEqualByComparingTo("92");
        assertThat(after.getTotalQuantity()).isEqualByComparingTo("92");
    }

    // ------------------------------------------------------------------

    private Listing publish(String quantity) {
        authenticateAs(SELLER);
        Listing listing = listingService.publish(new ListingPublishRequest(
                Listing.Side.SELL, noteId, CATEGORY, "分次成交测试电解铜",
                null, null, null,
                new BigDecimal(quantity), "吨",
                new BigDecimal("68000"), Listing.PriceType.FIXED,
                Listing.ConfirmMode.AUTO,
                WAREHOUSE, Listing.DeliveryMethod.SELF_PICKUP, null,
                OffsetDateTime.now().plusDays(30), null), currentUser());
        listingIds.add(listing.getId());
        return listing;
    }

    private void accept(Listing listing, String quantity) {
        authenticateAs(BUYER);
        Order order = orderService.accept(listing.getId(),
                new OrderAcceptRequest(new BigDecimal(quantity), null), currentUser());
        orderIds.add(order.getId());
    }

    private void authenticateAs(Long enterpriseId) {
        LoginUser user = LoginUser.builder()
                .userId(999_000_399L)
                .username("partial-fill-test")
                .enterpriseId(enterpriseId)
                .permissions(Set.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private LoginUser currentUser() {
        return (LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
