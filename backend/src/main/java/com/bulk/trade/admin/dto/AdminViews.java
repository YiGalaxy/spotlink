package com.bulk.trade.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运营后台所渲染的内容。
 *
 * <p>全部放在一个文件里，因为它们本质上是同一件事从若干个角度看到的样子：这里的
 * 每个视图都是为了回答运营人员提出的某个问题而存在，而且它们遵循同一套约定——
 * ID 用字符串表示、null 要写出来而不是省略、文案标签在服务端解析好。把它们拆到
 * 十几个文件里，只会让这些共同约定更难被注意到，也更容易在其中的某个文件里被
 * 破坏。
 *
 * <p><b>ID 一律序列化为字符串。</b>Snowflake 是 19 位，而 JavaScript 只精确到
 * 16 位，所以以数字形式发出去的 ID 回来时已经变了样——而在一个每个操作都要指名
 * 某一行的运营台里，一个漂移了 4 的 ID 就意味着操作落在了错误的企业上。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class AdminViews {

    private AdminViews() {
    }

    /** 运营后台的首页。 */
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
            /** 顾问的就绪状态，从旧看板搬过来的。 */
            String advisorModel,
            boolean advisorAvailable,
            List<String> advisorTools
    ) {
    }

    /**
     * 一家企业，即审核页面所展示的样子。
     *
     * <p>这里直接携带联系方式和资质元数据，而不是给一个跳转链接：运营人员在做
     * 是否通过审核的判断时，判断的正是这家公司是不是它自称的那一家；把证据藏在
     * 又一次点击之后，会让这个判断沦为走形式。
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

    /** 一笔订单，横跨全部租户。 */
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

    /** 一个账号。 */
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

    /** 一条已记录的操作。 */
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

    /** 一个角色，以及它携带的权限码。 */
    public record RoleRow(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name,
            String description,
            boolean system,
            List<String> permissionCodes
    ) {
    }

    /** 一个可授予的权限，供勾选列表使用。 */
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
