package com.spotlink.advisor;

import com.spotlink.advisor.agent.AdvisorAgent;
import com.spotlink.advisor.tool.OrderAdvisorTools;
import com.spotlink.advisor.tool.TaskAdvisorTools;
import com.spotlink.settlement.service.FundService;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.trading.entity.Order;
import com.spotlink.trading.entity.OrderStatus;
import com.spotlink.trading.mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that no advisor tool can be made to return another enterprise's data.
 *
 * <p><b>Why this test exists rather than a comment saying it is fine.</b>
 * Giving a language model tools that read orders and funds is the moment the
 * platform's tenant boundary becomes reachable from a prompt. "The code looks
 * right" is not a reassuring answer to that, and neither is "the model is
 * instructed not to". This asserts the property directly, against real rows,
 * from the two directions an attacker would actually try: listing everything,
 * and guessing a specific identifier.
 *
 * <p><b>The property being tested is structural, not behavioural.</b> No tool
 * accepts an enterprise, company or owner as an argument, so there is nothing
 * for a prompt to fill in. The test would fail the day someone adds a
 * convenient {@code enterpriseId} parameter, which is exactly the day it should.
 *
 * <p>Requires the docker compose stack from the README to be running.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("AI 顾问：跨企业数据隔离")
class AdvisorTenantIsolationTest {

    /** Not real enterprise ids, so nothing here can touch live data. */
    private static final Long TENANT_A = 999_000_101L;
    private static final Long TENANT_B = 999_000_102L;
    private static final Long OTHER_PARTY = 999_000_103L;

    @Autowired
    private AdvisorAgent advisorAgent;

    @Autowired
    private OrderAdvisorTools orderTools;

    @Autowired
    private TaskAdvisorTools taskTools;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private FundService fundService;

    private Order orderOfA;
    private Order orderOfB;

    @BeforeEach
    void setUp() {
        orderOfA = insertOrder(TENANT_A, "隔离测试-甲的订单");
        orderOfB = insertOrder(TENANT_B, "隔离测试-乙的订单");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        // deleteById, not a hand-set `deleted` field: @TableLogic makes
        // updateById ignore that column, so the row would survive.
        orderMapper.deleteById(orderOfA.getId());
        orderMapper.deleteById(orderOfB.getId());
    }

    // ------------------------------------------------------------------
    // Listing everything
    // ------------------------------------------------------------------

    @Test
    @DisplayName("列出订单时只看得到自己的")
    void listOrdersShowsOnlyOwn() {
        authenticateAs(TENANT_A);
        String answer = orderTools.listMyOrders(null);

        assertThat(answer).contains(orderOfA.getOrderNo());
        assertThat(answer).doesNotContain(orderOfB.getOrderNo());
        assertThat(answer).doesNotContain("隔离测试-乙的订单");
    }

    @Test
    @DisplayName("按状态筛选也不会漏出别人的")
    void listOrdersByStatusShowsOnlyOwn() {
        authenticateAs(TENANT_B);
        String answer = orderTools.listMyOrders("已确认");

        assertThat(answer).contains(orderOfB.getOrderNo());
        assertThat(answer).doesNotContain(orderOfA.getOrderNo());
    }

    @Test
    @DisplayName("待办汇总不包含别人的事项")
    void taskListShowsOnlyOwn() {
        authenticateAs(TENANT_A);
        String answer = taskTools.listMyTasks();

        assertThat(answer).doesNotContain(orderOfB.getOrderNo());
        assertThat(answer).doesNotContain("隔离测试-乙的订单");
    }

    // ------------------------------------------------------------------
    // Guessing an identifier
    // ------------------------------------------------------------------

    @Test
    @DisplayName("拿别人的订单号查详情，查不到")
    void detailByOtherPartysNumberReturnsNothing() {
        authenticateAs(TENANT_A);
        String answer = orderTools.getOrderDetail(orderOfB.getOrderNo());

        assertThat(answer).doesNotContain("隔离测试-乙的订单");
        assertThat(answer).doesNotContain(plainOf(orderOfB));
        assertThat(answer).contains("没有找到");
    }

    @Test
    @DisplayName("查不到和不是你的，回答完全一样")
    void unknownAndForbiddenAreIndistinguishable() {
        authenticateAs(TENANT_A);
        String forbidden = orderTools.getOrderDetail(orderOfB.getOrderNo());
        String absent = orderTools.getOrderDetail("OR000000000000000000");

        // Identical wording, or the difference itself confirms that another
        // company's order number exists.
        assertThat(forbidden).isEqualTo(absent);
    }

    @Test
    @DisplayName("自己的订单号查得到")
    void ownOrderNumberWorks() {
        authenticateAs(TENANT_A);
        String answer = orderTools.getOrderDetail(orderOfA.getOrderNo());

        assertThat(answer).contains(orderOfA.getOrderNo());
        assertThat(answer).contains("隔离测试-甲的订单");
    }

    // ------------------------------------------------------------------
    // The edges
    // ------------------------------------------------------------------

    @Test
    @DisplayName("平台运营账号没有租户归属，什么也查不到")
    void platformOperatorSeesNothing() {
        authenticateAs(null);

        // Asserted as "no tenant data" rather than "an error", because an
        // operator is a legitimate account type that simply has no scope — and
        // the tools have to be safe for it without a special case.
        assertThat(orderTools.listMyOrders(null)).doesNotContain(orderOfA.getOrderNo());
        assertThat(orderTools.listMyOrders(null)).doesNotContain(orderOfB.getOrderNo());
        assertThat(orderTools.getOrderDetail(orderOfA.getOrderNo())).doesNotContain(orderOfA.getOrderNo());
        assertThat(taskTools.listMyTasks()).doesNotContain(orderOfA.getOrderNo());
    }

    @Test
    @DisplayName("没有登录态时工具不会去查任何企业")
    void anonymousSeesNothing() {
        SecurityContextHolder.clearContext();

        assertThat(orderTools.listMyOrders(null)).doesNotContain(orderOfA.getOrderNo());
        assertThat(taskTools.listMyTasks()).doesNotContain(orderOfA.getOrderNo());
    }

    @Test
    @DisplayName("顾问没有资金工具，余额根本不在它的能力范围内")
    void theAdvisorHasNoFundTool() {
        // The balance is the most sensitive figure on the platform and the
        // advisor has no use for it: nothing it does — rule questions, contract
        // review, market reading — needs to know how much cash a company holds.
        //
        // Every tool result goes into the prompt, which means it goes to an
        // external model API. That is a data-egress decision, and the right
        // answer for a capability nothing requires is not to grant it and then
        // guard it. Asserted rather than left to a comment, because the way
        // this regresses is someone adding it back for convenience.
        List<String> toolNames = new ArrayList<>();
        for (Object bean : advisorAgent.toolBeans()) {
            for (var method : AopUtils.getTargetClass(bean).getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    toolNames.add(tool.name().isBlank() ? method.getName() : tool.name());
                }
            }
        }

        assertThat(toolNames).doesNotContain("query_my_funds");
        assertThat(toolNames).noneMatch(name -> name.contains("fund") || name.contains("balance"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Puts a principal in the security context exactly as the JWT filter does.
     *
     * <p>Calling the tools directly rather than through the HTTP layer is
     * deliberate: it exercises the same code path with the same context, minus
     * the network, so a failure here is a failure of the tools rather than of
     * authentication.
     */
    private void authenticateAs(Long enterpriseId) {
        LoginUser user = LoginUser.builder()
                .userId(999_000_999L)
                .username("isolation-test")
                .enterpriseId(enterpriseId)
                .permissions(Set.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private Order insertOrder(Long buyerId, String commodity) {
        Order order = new Order();
        order.setOrderNo("TEST" + System.nanoTime());
        order.setBuyerId(buyerId);
        order.setSellerId(OTHER_PARTY);
        order.setCategoryId(1002L);
        order.setCommodityName(commodity);
        order.setSpec("{}");
        order.setQuantity(new BigDecimal("10"));
        order.setUnit("吨");
        order.setPrice(new BigDecimal("68000"));
        order.setAmount(new BigDecimal("680000"));
        order.setDeliveryMethod("SELF_PICKUP");
        order.setPaymentTerms("MARGIN_THEN_BALANCE");
        // CONFIRMED so it is neither terminal (which would make it invisible to
        // a task list) nor awaiting a lister (which only one party can answer).
        order.setStatus(OrderStatus.CONFIRMED);
        order.setVersion(0);
        orderMapper.insert(order);
        return order;
    }

    private String plainOf(Order order) {
        return order.getAmount().stripTrailingZeros().toPlainString();
    }
}
