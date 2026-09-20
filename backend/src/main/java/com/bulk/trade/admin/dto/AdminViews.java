package com.bulk.trade.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What the operator console renders.
 *
 * <p>All in one file because they are one thing seen from several angles: every
 * view here exists to answer a question an operator asks, and they share the
 * same conventions — ids as strings, nulls written rather than omitted, labels
 * resolved on the server. Splitting them across a dozen files would make the
 * shared conventions harder to notice and easier to break in one of them.
 *
 * <p><b>Ids are serialised as strings.</b> A snowflake is nineteen digits and
 * JavaScript is exact to sixteen, so an id sent as a number comes back changed
 * — and in a console where every action names a row, an id that has drifted by
 * four is an action taken on the wrong enterprise.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class AdminViews {

    private AdminViews() {
    }

    /** The console's landing screen. */
    public record Overview(
            long enterpriseCount,
            long pendingEnterpriseCount,
            long frozenEnterpriseCount,
            long userCount,
            long orderCount,
            long activeOrderCount,
            long openListingCount,
            BigDecimal tradedAmount,
            String tradedAmountText,
            long auditCount,
            /** The advisor's readiness, carried here from the old dashboard. */
            String advisorModel,
            boolean advisorAvailable,
            List<String> advisorTools
    ) {
    }

    /**
     * One enterprise, as the review screen shows it.
     *
     * <p>Carries the contact details and the qualification metadata rather than
     * a link to them: an operator deciding whether to approve is deciding
     * whether this company is who it says it is, and hiding the evidence
     * behind another click makes the decision a formality.
     */
    public record EnterpriseRow(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String enterpriseCode,
            String name,
            String shortName,
            String unifiedSocialCreditCode,
            String legalPerson,
            String contactName,
            String contactPhone,
            String contactEmail,
            String province,
            String city,
            String address,
            String traderCode,
            Integer status,
            String statusText,
            String rejectReason,
            OffsetDateTime registeredAt,
            OffsetDateTime approvedAt,
            OffsetDateTime createdAt
    ) {
    }

    /** One order, across every tenant. */
    public record OrderRow(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String orderNo,
            String buyerName,
            String sellerName,
            String commodityName,
            BigDecimal quantity,
            String unit,
            BigDecimal price,
            BigDecimal amount,
            String amountText,
            String status,
            String statusText,
            String deliveryMethodText,
            String warehouseName,
            OffsetDateTime createdAt
    ) {
    }

    /** One account. */
    public record UserRow(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String username,
            String realName,
            String phone,
            String email,
            Integer userType,
            String userTypeText,
            Integer status,
            String statusText,
            @JsonSerialize(using = ToStringSerializer.class) Long enterpriseId,
            String enterpriseName,
            List<String> roles,
            OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt
    ) {
    }

    /** One recorded action. */
    public record AuditRow(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String username,
            String module,
            String action,
            String targetType,
            @JsonSerialize(using = ToStringSerializer.class) Long targetId,
            String beforeData,
            String afterData,
            String ip,
            Boolean success,
            String errorMessage,
            OffsetDateTime createdAt
    ) {
    }

    /** A role, with the codes it carries. */
    public record RoleRow(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name,
            String description,
            boolean system,
            List<String> permissionCodes
    ) {
    }

    /** A grantable permission, for the checkbox list. */
    public record PermissionRow(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name,
            Integer permType,
            String path,
            Integer sortOrder
    ) {
    }
}
