package com.spotlink.trading.service.access;

import java.util.List;
import java.time.OffsetDateTime;
import com.spotlink.trading.entity.Listing;
import com.spotlink.trading.mapper.ListingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 跨模块访问入口，沿用调用方事务，不向调用方暴露 ORM 条件。 */
@Service
@RequiredArgsConstructor
public class ListingAccessService implements ListingAccess {
    private final ListingMapper mapper;

    @Override public long countPublic(com.spotlink.trading.dto.PublicListingCriteria criteria) {
        return mapper.countPublic(criteria);
    }

    @Override public List<Listing> findByCategory(Long categoryId) {
        return mapper.findByCategory(categoryId);
    }

    @Override public List<Listing> findPublicByKeyword(String keyword, String side, OffsetDateTime now, int limit) {
        return mapper.findPublicByKeyword(keyword, side, now, limit);
    }

    @Override public List<Listing> findPublicByNumber(String number, String side, OffsetDateTime now) {
        return mapper.findPublicByNumber(number, side, now);
    }

    @Override public List<Listing> searchPublic(com.spotlink.trading.dto.PublicListingCriteria criteria,
                                      String sort, int limit) {
        return mapper.searchPublic(criteria, sort, limit);
    }
}
