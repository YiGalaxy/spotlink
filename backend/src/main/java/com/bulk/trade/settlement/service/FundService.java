package com.bulk.trade.settlement.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.settlement.entity.FundAccount;
import com.bulk.trade.settlement.entity.FundFlow;
import com.bulk.trade.settlement.mapper.FundAccountMapper;
import com.bulk.trade.settlement.mapper.FundFlowMapper;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Money movements.
 *
 * <p>Every movement does two things in one transaction: appends a ledger row,
 * and updates the account's cached totals. Both or neither — a ledger row with
 * no balance change is as wrong as a balance change with no ledger row, and
 * either one alone makes the account unauditable.
 *
 * <p>The account's optimistic lock serialises concurrent movements on the same
 * account, so two withdrawals cannot both read the same available balance and
 * both succeed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundService {

    private static final DateTimeFormatter NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final FundAccountMapper accountMapper;
    private final FundFlowMapper flowMapper;

    public FundAccount requireAccount(Long enterpriseId) {
        FundAccount account = accountMapper.selectOne(Wrappers.<FundAccount>lambdaQuery()
                .eq(FundAccount::getEnterpriseId, enterpriseId));
        if (account == null) {
            throw BusinessException.of(ResultCode.ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    public List<FundFlow> flows(Long enterpriseId, int limit) {
        FundAccount account = requireAccount(enterpriseId);
        return flowMapper.selectList(Wrappers.<FundFlow>lambdaQuery()
                .eq(FundFlow::getAccountId, account.getId())
                .orderByDesc(FundFlow::getId)
                .last("limit " + Math.min(Math.max(limit, 1), 200)));
    }

    /** Adds funds. In this build the money arrives from nowhere, which is the point. */
    @Transactional
    public FundAccount recharge(Long enterpriseId, BigDecimal amount, String remark) {
        requirePositive(amount);
        FundAccount account = requireAccount(enterpriseId);

        account.setBalance(account.getBalance().add(amount));
        account.setAvailableBalance(account.getAvailableBalance().add(amount));
        persist(account);
        record(account, FundFlow.Direction.IN, FundFlow.BizType.RECHARGE, amount, null,
                remark == null ? "充值" : remark);

        log.info("Enterprise {} recharged {}; available now {}",
                enterpriseId, amount.toPlainString(), account.getAvailableBalance().toPlainString());
        return account;
    }

    @Transactional
    public FundAccount withdraw(Long enterpriseId, BigDecimal amount, String remark) {
        requirePositive(amount);
        FundAccount account = requireAccount(enterpriseId);

        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw BusinessException.of(ResultCode.BALANCE_INSUFFICIENT,
                    "可用余额 %s，不足以提现 %s".formatted(
                            account.getAvailableBalance().stripTrailingZeros().toPlainString(),
                            amount.stripTrailingZeros().toPlainString()));
        }

        account.setBalance(account.getBalance().subtract(amount));
        account.setAvailableBalance(account.getAvailableBalance().subtract(amount));
        persist(account);
        record(account, FundFlow.Direction.OUT, FundFlow.BizType.WITHDRAW, amount, null,
                remark == null ? "提现" : remark);
        return account;
    }

    /**
     * Reserves money as margin.
     *
     * <p>Reserving is not spending: the balance is unchanged, only its split
     * between available and frozen moves. That distinction is why a cancelled
     * order can return the money without a refund having occurred.
     */
    @Transactional
    public void freezeMargin(Long enterpriseId, BigDecimal amount, Long bizId, String remark) {
        requirePositive(amount);
        FundAccount account = requireAccount(enterpriseId);

        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw BusinessException.of(ResultCode.BALANCE_INSUFFICIENT,
                    "可用余额 %s，不足以冻结保证金 %s".formatted(
                            account.getAvailableBalance().stripTrailingZeros().toPlainString(),
                            amount.stripTrailingZeros().toPlainString()));
        }

        account.setAvailableBalance(account.getAvailableBalance().subtract(amount));
        account.setFrozenBalance(account.getFrozenBalance().add(amount));
        persist(account);
        record(account, FundFlow.Direction.OUT, FundFlow.BizType.MARGIN_FREEZE, amount, bizId,
                remark == null ? "保证金冻结" : remark);

        log.info("Enterprise {} margin frozen {} for biz {}", enterpriseId,
                amount.toPlainString(), bizId);
    }

    @Transactional
    public void releaseMargin(Long enterpriseId, BigDecimal amount, Long bizId, String remark) {
        requirePositive(amount);
        FundAccount account = requireAccount(enterpriseId);

        if (account.getFrozenBalance().compareTo(amount) < 0) {
            throw BusinessException.of(ResultCode.CONFLICT,
                    "冻结余额不足，无法释放 " + amount.toPlainString());
        }

        account.setFrozenBalance(account.getFrozenBalance().subtract(amount));
        account.setAvailableBalance(account.getAvailableBalance().add(amount));
        persist(account);
        record(account, FundFlow.Direction.IN, FundFlow.BizType.MARGIN_RELEASE, amount, bizId,
                remark == null ? "保证金解冻" : remark);
    }

    /**
     * Pays settled money out of the frozen balance.
     *
     * <p>Comes out of frozen rather than available: the money was reserved when
     * the order was placed, and paying it here is what that reservation was for.
     * Taking it from available would let an account pay for the same deal twice.
     */
    @Transactional
    public void payFromMargin(Long enterpriseId, BigDecimal amount, Long bizId, String remark) {
        requirePositive(amount);
        FundAccount account = requireAccount(enterpriseId);

        if (account.getFrozenBalance().compareTo(amount) < 0) {
            throw BusinessException.of(ResultCode.CONFLICT,
                    "冻结余额 %s，不足以支付 %s".formatted(
                            account.getFrozenBalance().stripTrailingZeros().toPlainString(),
                            amount.stripTrailingZeros().toPlainString()));
        }

        // Money leaves the account entirely: both frozen and total drop.
        account.setFrozenBalance(account.getFrozenBalance().subtract(amount));
        account.setBalance(account.getBalance().subtract(amount));
        persist(account);
        record(account, FundFlow.Direction.OUT, FundFlow.BizType.PAYMENT, amount, bizId,
                remark == null ? "货款支付" : remark);
    }

    /** Credits money received as payment. */
    @Transactional
    public void receive(Long enterpriseId, BigDecimal amount, Long bizId, String remark) {
        requirePositive(amount);
        FundAccount account = requireAccount(enterpriseId);

        account.setBalance(account.getBalance().add(amount));
        account.setAvailableBalance(account.getAvailableBalance().add(amount));
        persist(account);
        record(account, FundFlow.Direction.IN, FundFlow.BizType.PAYMENT, amount, bizId,
                remark == null ? "收到货款" : remark);
    }

    // ------------------------------------------------------------------

    private void persist(FundAccount account) {
        if (accountMapper.updateById(account) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "资金账户正在被其他操作修改，请重试");
        }
    }

    /**
     * Appends a ledger row.
     *
     * <p>Carries the balance as it stands after the movement, so a statement is
     * a straight read while still being verifiable by replaying the ledger from
     * the start.
     */
    private void record(FundAccount account, String direction, String bizType,
                        BigDecimal amount, Long bizId, String remark) {
        FundFlow flow = new FundFlow();
        flow.setFlowNo(nextNo("FF"));
        flow.setAccountId(account.getId());
        flow.setEnterpriseId(account.getEnterpriseId());
        flow.setDirection(direction);
        flow.setBizType(bizType);
        flow.setAmount(amount);
        flow.setBalanceAfter(account.getBalance());
        flow.setBizId(bizId);
        flow.setRemark(remark);
        flowMapper.insert(flow);
    }

    private void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "金额必须大于 0");
        }
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
