package com.bulk.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the bulk commodity spot trading platform.
 *
 * <p>Module layout (business modules, not technical layers):
 * <pre>
 *   identity    - enterprise, user, role, permission, authentication
 *   commodity   - category tree, commodity specification
 *   inventory   - electronic inventory note (NOT a warehouse receipt)
 *   trading     - listing, delisting, negotiated deal, order
 *   contract    - contract and e-signature
 *   settlement  - margin, freeze, payment flow, reconciliation
 *   logistics   - delivery and weighing
 *   marketdata  - market quote aggregation and SSE push
 *   advisor     - AI advisor (tool use + RAG)
 *   shared      - shared kernel, the only package other modules may depend on
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.bulk.trade.**.mapper")
@EnableScheduling
public class BulkTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(BulkTradeApplication.class, args);
    }
}
