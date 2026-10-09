package com.spotlink.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 服务与持久化层之间的查询参数，不携带 ORM 条件或 SQL。 */
public record PublicListingCriteria(String keyword, String side, List<Long> warehouseIds,
                                    String unit, BigDecimal minQuantity, BigDecimal maxPrice,
                                    String deliveryMethod, OffsetDateTime now) { }
