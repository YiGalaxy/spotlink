package com.bulk.trade.identity.entity;

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
 * A trading company on the platform — the tenant root.
 *
 * <p>Every other business table eventually points back here, which is what
 * makes {@code enterpriseId} the single tenant key used throughout the system.
 */
@Getter
@Setter
@TableName("t_enterprise")
public class Enterprise {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Human-readable business code, e.g. {@code ENT20260920001}. */
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

    /** Trading seat code issued by the platform after approval. */
    private String traderCode;

    /** 0=pending review, 1=approved, 2=rejected, 3=frozen, 4=closed. */
    private Integer status;

    /** Qualification documents as a JSON array: [{type,name,objectKey,uploadedAt}]. */
    private String qualifications;

    private Long marginAccountId;
    private OffsetDateTime registeredAt;
    private OffsetDateTime approvedAt;
    private Long approvedBy;
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

    /** Enterprise status values, kept next to the field they describe. */
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
