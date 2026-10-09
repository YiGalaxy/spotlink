package com.spotlink.trading.service.access;

import java.util.List;
import java.time.OffsetDateTime;
import com.spotlink.trading.entity.Listing;

/** 模块对外的数据访问契约；查询实现由本模块 Mapper 负责。 */
public interface ListingAccess {
    long countPublic(com.spotlink.trading.dto.PublicListingCriteria criteria);
    List<Listing> findByCategory(Long categoryId);
    List<Listing> findPublicByKeyword(String keyword, String side, OffsetDateTime now, int limit);
    List<Listing> findPublicByNumber(String number, String side, OffsetDateTime now);
    List<Listing> searchPublic(com.spotlink.trading.dto.PublicListingCriteria criteria,
                                      String sort, int limit);
}
