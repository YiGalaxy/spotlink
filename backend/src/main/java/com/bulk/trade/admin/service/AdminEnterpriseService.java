package com.bulk.trade.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.admin.dto.AdminViews;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.shared.audit.AuditService;
import com.bulk.trade.shared.exception.BusinessException;
import com.bulk.trade.shared.security.SecurityUtils;
import com.bulk.trade.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 审核谁可以交易。
 *
 * <p><b>这是审核功能中原本缺失的那一半。</b>平台一直拒绝让未通过审核的企业登录，
 * 却完全没有办法让任何一家通过审核。`pending01` 就是那个演示账号，它处于一个谁也
 * 走不出去的状态。这里的每个方法，都是一条早已在被执行的规则的另一半。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminEnterpriseService {

    private final EnterpriseMapper enterpriseMapper;
    private final AuditService audit;

    public List<AdminViews.EnterpriseRow> search(Integer status, String keyword) {
        var query = Wrappers.<Enterprise>lambdaQuery().orderByDesc(Enterprise::getId);
        if (status != null) {
            query.eq(Enterprise::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            // name 上的 trigram 索引就是为这个搜索框而建的。
            query.and(w -> w.like(Enterprise::getName, keyword.trim())
                    .or().like(Enterprise::getEnterpriseCode, keyword.trim())
                    .or().like(Enterprise::getUnifiedSocialCreditCode, keyword.trim()));
        }
        return enterpriseMapper.selectList(query).stream().map(AdminEnterpriseService::toRow).toList();
    }

    /**
     * 通过一份申请，并发放一个交易席位。
     *
     * <p>席位号是从 id 推导出来的，而不是靠自增计数，所以对同一家企业重复通过审核
     * 得到的还是同一个号，而不会白白消耗掉下一个号。真实的市场会从序列里发放席位，
     * 并且能把已释放的席位重新分配出去；那是一个缺口，这里把它记下来，而不是用某种
     * 看起来像序列的东西把它遮住。
     */
    @Transactional
    public AdminViews.EnterpriseRow approve(Long id, String traderCode) {
        Enterprise enterprise = require(id);
        if (enterprise.getStatus() != null && enterprise.getStatus() == Enterprise.Status.APPROVED) {
            throw BusinessException.of(ResultCode.ADMIN_ENTERPRISE_ALREADY_REVIEWED);
        }
        String before = state(enterprise);

        enterprise.setStatus(Enterprise.Status.APPROVED);
        enterprise.setApprovedAt(OffsetDateTime.now());
        enterprise.setApprovedBy(SecurityUtils.currentUserId());
        // 这里要清空，这样一家曾被驳回、如今又通过审核的企业，就不会把当年那条
        // 理由一直带在记录里。updateById 默认会忽略 null 字段，除非该字段主动声明
        // 要参与更新，而 Enterprise.rejectReason 正是这样声明的。
        enterprise.setRejectReason(null);
        if (enterprise.getTraderCode() == null || enterprise.getTraderCode().isBlank()) {
            enterprise.setTraderCode(issueTraderCode(enterprise, traderCode));
        }
        update(enterprise);

        audit.record("enterprise", "approve", "ENTERPRISE", id, before, state(enterprise));
        log.info("Enterprise {} approved by {}", enterprise.getEnterpriseCode(),
                SecurityUtils.currentUsername());
        return toRow(enterprise);
    }

    @Transactional
    public AdminViews.EnterpriseRow reject(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            // 这一列存在，但从来没有被写入过。留空的话，就等于给申请方看一条没人
            // 解释得清的驳回。
            throw BusinessException.of(ResultCode.ADMIN_REJECT_REASON_REQUIRED);
        }
        Enterprise enterprise = require(id);
        String before = state(enterprise);

        enterprise.setStatus(Enterprise.Status.REJECTED);
        enterprise.setRejectReason(reason.trim());
        update(enterprise);

        audit.record("enterprise", "reject", "ENTERPRISE", id, before, state(enterprise));
        log.info("Enterprise {} rejected: {}", enterprise.getEnterpriseCode(), reason);
        return toRow(enterprise);
    }

    @Transactional
    public AdminViews.EnterpriseRow freeze(Long id, String reason) {
        Enterprise enterprise = require(id);
        if (enterprise.getStatus() == null || enterprise.getStatus() != Enterprise.Status.APPROVED) {
            throw BusinessException.of(ResultCode.ADMIN_ENTERPRISE_NOT_APPROVED);
        }
        String before = state(enterprise);

        enterprise.setStatus(Enterprise.Status.FROZEN);
        update(enterprise);

        audit.record("enterprise", "freeze", "ENTERPRISE", id, before, state(enterprise));
        log.info("Enterprise {} frozen: {}", enterprise.getEnterpriseCode(), reason);
        return toRow(enterprise);
    }

    @Transactional
    public AdminViews.EnterpriseRow unfreeze(Long id) {
        Enterprise enterprise = require(id);
        if (enterprise.getStatus() == null || enterprise.getStatus() != Enterprise.Status.FROZEN) {
            throw BusinessException.of(ResultCode.CONFLICT, "该企业不在冻结状态");
        }
        String before = state(enterprise);

        enterprise.setStatus(Enterprise.Status.APPROVED);
        update(enterprise);

        audit.record("enterprise", "unfreeze", "ENTERPRISE", id, before, state(enterprise));
        log.info("Enterprise {} unfrozen", enterprise.getEnterpriseCode());
        return toRow(enterprise);
    }

    // ------------------------------------------------------------------

    private Enterprise require(Long id) {
        Enterprise enterprise = enterpriseMapper.selectById(id);
        if (enterprise == null) {
            throw BusinessException.of(ResultCode.ADMIN_ENTERPRISE_NOT_FOUND);
        }
        return enterprise;
    }

    private void update(Enterprise enterprise) {
        if (enterpriseMapper.updateById(enterprise) == 0) {
            throw BusinessException.of(ResultCode.CONFLICT, "该企业正在被其他操作修改，请重试");
        }
    }

    /** 由 id 确定性地推导，所以重复通过审核不会发第二个席位出去。 */
    private String issueTraderCode(Enterprise enterprise, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return "T" + String.format("%06d", Math.floorMod(enterprise.getId(), 1_000_000));
    }

    /** 参与审计的状态快照：小到能在日志里一眼读完，又具体到足以说明问题。 */
    private String state(Enterprise enterprise) {
        return "status=" + enterprise.getStatus()
                + (enterprise.getTraderCode() == null ? "" : ", traderCode=" + enterprise.getTraderCode())
                + (enterprise.getRejectReason() == null ? "" : ", rejectReason=" + enterprise.getRejectReason());
    }

    static AdminViews.EnterpriseRow toRow(Enterprise e) {
        return new AdminViews.EnterpriseRow(
                e.getId(), e.getEnterpriseCode(), e.getName(), e.getShortName(),
                e.getUnifiedSocialCreditCode(), e.getLegalPerson(), e.getContactName(),
                e.getContactPhone(), e.getContactEmail(), e.getProvince(), e.getCity(),
                e.getAddress(), e.getTraderCode(), e.getStatus(), statusText(e.getStatus()),
                e.getRejectReason(), e.getRegisteredAt(), e.getApprovedAt(), e.getCreatedAt());
    }

    static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Enterprise.Status.PENDING -> "待审核";
            case Enterprise.Status.APPROVED -> "已通过";
            case Enterprise.Status.REJECTED -> "已驳回";
            case Enterprise.Status.FROZEN -> "已冻结";
            case Enterprise.Status.CLOSED -> "已注销";
            default -> "未知";
        };
    }
}
