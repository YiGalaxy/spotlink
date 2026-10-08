package com.spotlink.identity.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 平台上的一家贸易企业——租户的根。
 *
 * <p>其他每一张业务表最终都指回这里，这正是 {@code enterpriseId} 能成为贯穿整个
 * 系统的唯一租户键的原因。
 */
@Getter
@Setter
@TableName("t_enterprise")
public class Enterprise {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 人类可读的业务编号，例如 {@code ENT20260920001}。 */
    private String enterpriseCode;

    private String name;
    private String shortName;
    private String unifiedSocialCreditCode;
    private String legalPerson;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String province;
    private String city;
    private String address;

    /** 审核通过后由平台签发的交易席位编码。 */
    private String traderCode;

    /** 0=待审核，1=已通过，2=已驳回，3=已冻结，4=已注销。 */
    private Integer status;

    /** 资质文件，JSON 数组：[{type,name,objectKey,uploadedAt}]。 */
    private String qualifications;

    private Long marginAccountId;
    private OffsetDateTime registeredAt;
    private OffsetDateTime approvedAt;
    private Long approvedBy;
    /**
     * 一份申请为什么被驳回。
     *
     * <p><b>{@code updateStrategy = ALWAYS} 是关键所在。</b>通过一家曾被驳回的企业
     * 审核时，必须把这个字段清空，而 MyBatis-Plus 默认会把 null 字段从生成的更新语句里
     * 略去——所以那条旧理由就会熬过一次审核活下来，运营后台会显示一家「已通过」的企业
     * 却挂着一条驳回理由。这个坑项目已经踩过一次了，在订单的确认截止时间上。
     */
    @com.baomidou.mybatisplus.annotation.TableField(
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String rejectReason;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @TableLogic
    private Integer deleted;

    /** 企业状态取值，放在它们所描述的字段旁边。 */
    public static final class Status {
        public static final int PENDING = 0;
        public static final int APPROVED = 1;
        public static final int REJECTED = 2;
        public static final int FROZEN = 3;
        public static final int CLOSED = 4;

        private Status() {
        }
    }
}
