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
 * 资金变动。
 *
 * <p>每一次变动都在同一个事务里做两件事：追加一行流水账，并更新账户上缓存的
 * 各项总额。要么都做，要么都不做——一行没有余额变化的流水，与一次没有流水的
 * 余额变化一样错，而两者只要单独存在其一，账户就无法被审计。
 *
 * <p>账户上的乐观锁把同一账户上的并发变动串行化，因此两笔提现不可能都读到
 * 同一个可用余额，并且都成功。
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
        FundAccount account = findAccount(enterpriseId);
        if (account == null) {
            throw BusinessException.of(ResultCode.ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    /**
     * 返回账户，或 null。
     *
     * <p>供那些在查无账户时有一句得体话可说的调用方使用。它之所以存在，正是
     * 因为顾问：一个抛异常的工具到达模型那里只是一个不透明的失败，而
     * “我账上还有多少钱”值得一句回答，而不是一个异常。任何会动用资金的地方
     * 都不该用它——那些地方要的是 {@link #requireAccount} 和它的拒绝。
     */
    public FundAccount findAccount(Long enterpriseId) {
        return accountMapper.selectOne(Wrappers.<FundAccount>lambdaQuery()
                .eq(FundAccount::getEnterpriseId, enterpriseId));
    }

    /**
     * 开一个资金账户，如果这家企业还没有的话。
     *
     * <p>「通过审核的企业都有一个资金账户」是这套系统的一条不变量，而此前没有任何地方
     * 在建立它：迁移 V6 给当时已存在的企业各开了一个，此后通过审核的企业则没有。于是
     * 一家新企业的第一笔资金操作会撞上「资金账户不存在」，而没有任何界面能把它补出来。
     *
     * <p>账户的形状——账号怎么起、三个金额从哪来、状态取什么值——是结算模块的事，
     * 所以放在这里，而不是放在审核那一侧。审核只需要表达「这家企业现在可以交易了」。
     */
    @Transactional
    public FundAccount openAccountIfAbsent(Long enterpriseId, String enterpriseCode) {
        FundAccount existing = findAccount(enterpriseId);
        if (existing != null) {
            return existing;
        }
        FundAccount account = new FundAccount();
        account.setAccountNo("ACC" + enterpriseCode);
        account.setEnterpriseId(enterpriseId);
        account.setBalance(BigDecimal.ZERO);
        account.setAvailableBalance(BigDecimal.ZERO);
        account.setFrozenBalance(BigDecimal.ZERO);
        account.setStatus(1);
        accountMapper.insert(account);
        log.info("Opened fund account {} for enterprise {}", account.getAccountNo(), enterpriseCode);
        return account;
    }

    public List<FundFlow> flows(Long enterpriseId, int limit) {
        FundAccount account = requireAccount(enterpriseId);
        return flowMapper.selectList(Wrappers.<FundFlow>lambdaQuery()
                .eq(FundFlow::getAccountId, account.getId())
                .orderByDesc(FundFlow::getId)
                .last("limit " + Math.min(Math.max(limit, 1), 200)));
    }

    /** 增加资金。在本版本中，钱是凭空来的，而这正是要点所在。 */
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
     * 把资金作为保证金预留起来。
     *
     * <p>预留不是支出：总额不变，变的只是它在可用与冻结之间的划分。正是这一
     * 区别，使得一笔被取消的订单可以在并未发生退款的情况下把钱还回去。
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
     * 从冻结余额中支付已结算的款项。
     *
     * <p>从冻结而不是从可用里出：这笔钱在下单时就被预留了，而在这里支付它，
     * 正是那次预留的目的。若从可用里扣，账户就能为同一笔交易付两次钱。
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

        // 资金彻底离开账户：冻结额与总额同时下降。
        account.setFrozenBalance(account.getFrozenBalance().subtract(amount));
        account.setBalance(account.getBalance().subtract(amount));
        persist(account);
        record(account, FundFlow.Direction.OUT, FundFlow.BizType.PAYMENT, amount, bizId,
                remark == null ? "货款支付" : remark);
    }

    /** 记入作为货款收到的资金。 */
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
     * 追加一行流水账。
     *
     * <p>携带变动之后当下的余额，这样一份对账单是一次直读，同时仍然可以通过
     * 从头重放流水账来核验。
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
