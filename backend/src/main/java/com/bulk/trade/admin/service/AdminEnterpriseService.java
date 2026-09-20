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
 * Reviewing who may trade.
 *
 * <p><b>The half of the审核 feature that did not exist.</b> The platform has
 * always refused to let an unapproved enterprise log in — and had no way at all
 * to approve one. `pending01` is the demonstration account for a state nothing
 * could leave. Every method here is the other half of a rule that was already
 * being enforced.
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
            // The trigram index on name was built for exactly this box.
            query.and(w -> w.like(Enterprise::getName, keyword.trim())
                    .or().like(Enterprise::getEnterpriseCode, keyword.trim())
                    .or().like(Enterprise::getUnifiedSocialCreditCode, keyword.trim()));
        }
        return enterpriseMapper.selectList(query).stream().map(AdminEnterpriseService::toRow).toList();
    }

    /**
     * Approves an application, and issues a trading seat.
     *
     * <p>The seat code is derived from the id rather than counted, so approving
     * the same enterprise twice yields the same code instead of burning the
     * next one. A real venue issues these from a sequence and can reassign a
     * released seat; that is a gap, and it is noted rather than hidden behind
     * something that looks like a sequence.
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
        // Cleared, so an enterprise that was once rejected and is now approved
        // does not carry the old reason into its record. updateById ignores
        // nulls unless the field opts in, which Enterprise.rejectReason does.
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
            // The column exists and has never been written. Leaving it empty
            // would show the applicant a rejection nobody can explain.
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

    /** Deterministic from the id, so re-approving does not issue a second seat. */
    private String issueTraderCode(Enterprise enterprise, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return "T" + String.format("%06d", Math.floorMod(enterprise.getId(), 1_000_000));
    }

    /** The audited state, small enough to read in a log and specific enough to matter. */
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
