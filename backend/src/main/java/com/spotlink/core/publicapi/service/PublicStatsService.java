package com.spotlink.publicapi.service;

import com.spotlink.publicapi.dto.PublicStats;
import com.spotlink.publicapi.mapper.PublicStatsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PublicStatsService {
    private final PublicStatsMapper stats;

    public PublicStats overview() {
        BigDecimal amount = stats.tradedAmount();
        return new PublicStats(stats.countApprovedEnterprises(), stats.countOpenListings(), stats.countTrades(),
                stats.tradedQuantity(), amount, PublicStats.formatAmount(amount), stats.inventoryQuantity());
    }
}
