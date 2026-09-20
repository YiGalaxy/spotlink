package com.bulk.trade.contract.dto;

import com.bulk.trade.contract.entity.Contract;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A contract as the client sees it.
 *
 * <p>{@code mySigned} and {@code counterpartySigned} are resolved per caller so
 * the UI can say "waiting for the other side" without the client having to work
 * out which side it is.
 */
public record ContractView(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String contractNo,
        @JsonSerialize(using = ToStringSerializer.class) Long orderId,
        String title,

        @JsonSerialize(using = ToStringSerializer.class) Long buyerId,
        String buyerName,
        @JsonSerialize(using = ToStringSerializer.class) Long sellerId,
        String sellerName,

        String commodityName,
        BigDecimal quantity,
        String unit,
        BigDecimal price,
        BigDecimal amount,
        BigDecimal weightTolerance,
        Map<String, Object> terms,

        String status,
        String statusText,

        boolean mySigned,
        boolean counterpartySigned,
        OffsetDateTime buyerSignedAt,
        OffsetDateTime sellerSignedAt,

        OffsetDateTime createdAt
) {

    public static ContractView of(Contract contract,
                                  Long viewerEnterpriseId,
                                  String buyerName,
                                  String sellerName,
                                  String commodityName,
                                  Map<String, Object> terms) {
        boolean signed = contract.hasSigned(viewerEnterpriseId);
        boolean other = contract.isBuyer(viewerEnterpriseId)
                ? contract.getSellerSignedAt() != null
                : contract.getBuyerSignedAt() != null;

        return new ContractView(
                contract.getId(),
                contract.getContractNo(),
                contract.getOrderId(),
                contract.getTitle(),
                contract.getBuyerId(),
                buyerName,
                contract.getSellerId(),
                sellerName,
                commodityName,
                contract.getQuantity(),
                contract.getUnit(),
                contract.getPrice(),
                contract.getAmount(),
                contract.getWeightTolerance(),
                terms,
                contract.getStatus(),
                Contract.Status.text(contract.getStatus()),
                signed,
                other,
                contract.getBuyerSignedAt(),
                contract.getSellerSignedAt(),
                contract.getCreatedAt());
    }
}
