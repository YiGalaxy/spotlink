package com.bulk.trade.advisor.tool;

import com.bulk.trade.settlement.entity.FundAccount;
import com.bulk.trade.settlement.entity.FundFlow;
import com.bulk.trade.settlement.service.FundService;
import com.bulk.trade.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Tools that expose the caller's own fund account.
 *
 * <p>Balances and flows are the most sensitive figures on the platform — a
 * competitor who knew a rival's available cash would know exactly how hard to
 * press on price. Every read here is scoped to the caller's enterprise by the
 * same rule as everything else: the tenant comes from the security context and
 * is never a parameter.
 *
 * <p><b>Read-only.</b> There is no tool that moves money. The advisor answers
 * questions; a model that can also transact turns a prompt-injection bug into a
 * transfer, and the convenience of "帮我把保证金交了" is not worth that.
 */
@Component
@RequiredArgsConstructor
public class FundAdvisorTools {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FundService fundService;

    @Tool(name = "query_my_funds",
            description = """
                    Returns the caller's fund account: balance, available and frozen amounts, and
                    the most recent movements. Use it for "我账上还有多少钱", "可用资金", "为什么余额
                    和可用对不上", or before reasoning about whether the caller can afford a trade.
                    Read-only — it cannot move money.""")
    public String queryMyFunds(
            @ToolParam(description = "How many recent movements to list, between 1 and 30. Defaults to 10.")
            Integer flowLimit) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，没有资金账户。";
        }

        FundAccount account = fundService.findAccount(enterpriseId);
        if (account == null) {
            // Answered rather than thrown. A tool that throws reaches the model
            // as an opaque failure, and a question this ordinary deserves a
            // sentence. It also happens to be true: only the demo data opens
            // accounts today, so an enterprise that registered through the API
            // genuinely has none.
            return "当前企业还没有资金账户，因此没有余额可查。资金账户由平台开立，"
                    + "请联系平台运营确认。";
        }

        int limit = flowLimit == null || flowLimit < 1 ? 10 : Math.min(flowLimit, 30);

        StringBuilder sb = new StringBuilder();
        sb.append("资金账户 ").append(account.getAccountNo()).append('\n')
          .append("账户余额: ").append(plain(account.getBalance())).append(" 元\n")
          .append("可用余额: ").append(plain(account.getAvailableBalance())).append(" 元\n")
          .append("冻结金额: ").append(plain(account.getFrozenBalance())).append(" 元\n");

        // Spelled out because it is the question behind the question: the two
        // figures disagree whenever a margin is held, and a reader who does not
        // know that reads the difference as a discrepancy.
        sb.append("（余额 = 可用 + 冻结。冻结部分是已挂出的保证金，交易完成或取消后回到可用。）\n");

        List<FundFlow> flows = fundService.flows(enterpriseId, limit);
        if (flows.isEmpty()) {
            sb.append("\n暂无资金流水。");
            return sb.toString();
        }

        sb.append("\n最近 ").append(flows.size()).append(" 条流水：\n");
        for (FundFlow flow : flows) {
            sb.append("- ").append(format(flow.getCreatedAt()))
              .append(' ').append(direction(flow.getDirection()))
              .append(plain(flow.getAmount())).append(" 元")
              .append("（余额 ").append(plain(flow.getBalanceAfter())).append("）")
              .append(" | ").append(bizType(flow.getBizType()))
              .append(flow.getRemark() == null ? "" : " | " + flow.getRemark())
              .append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private String direction(String direction) {
        return FundFlow.Direction.IN.equals(direction) ? "入账 +" : "出账 -";
    }

    /**
     * Turns the stored code into words.
     *
     * <p>The flow types are the one place where the ledger's vocabulary is
     * genuinely internal — {@code MARGIN_FREEZE} means nothing to a trader — and
     * a tool that returns codes leaves the model to guess at their meaning.
     */
    private String bizType(String bizType) {
        if (bizType == null) {
            return "其他";
        }
        return switch (bizType) {
            case FundFlow.BizType.RECHARGE -> "充值";
            case FundFlow.BizType.WITHDRAW -> "提现";
            case FundFlow.BizType.MARGIN_FREEZE -> "冻结保证金";
            case FundFlow.BizType.MARGIN_RELEASE -> "释放保证金";
            case FundFlow.BizType.PAYMENT -> "货款支付";
            case FundFlow.BizType.REFUND -> "货款退回";
            default -> bizType;
        };
    }

    private String format(OffsetDateTime time) {
        return time == null ? "—" : time.format(TIME);
    }

    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
