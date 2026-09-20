package com.bulk.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 大宗商品现货交易平台的入口。
 *
 * <p>模块划分（按业务能力，不按技术分层）：
 * <pre>
 *   identity    企业、用户、角色、权限、认证
 *   commodity   品类树、商品规格
 *   inventory   电子库存单（**不是仓单**）
 *   trading     挂牌、摘牌、协议交易、订单
 *   contract    合同与电子签署
 *   settlement  保证金、冻结、资金流水、对账
 *   logistics   交收与磅差
 *   marketdata  行情聚合与 SSE 推送
 *   advisor     AI 顾问（工具调用 + RAG）
 *   publicapi   无需登录的公开数据
 *   admin       运营后台
 *   shared      共享内核，其他模块唯一可以依赖的包
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
