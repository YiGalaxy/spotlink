package com.spotlink.bootstrap.data;

import com.spotlink.bootstrap.data.repository.DatasetRepository;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.Set;

/** 不启动 Web、定时扫描、开发种子或 AI 客户端的部署入口。 */
public final class DataImportCli {
    private DataImportCli() { }
    public static int run(String[] args) {
        try {
            DatasetPackage.require(args.length >= 2 && Set.of("migrate", "business", "knowledge", "verify", "status").contains(args[1]), "命令格式：data migrate|business|knowledge|verify|status [batch] [initial|runtime]");
            String command = args[1];
            boolean simple = Set.of("migrate", "status").contains(command);
            DatasetPackage.require(simple ? args.length == 2 : command.equals("verify") ? args.length == 4 && Set.of("initial", "runtime").contains(args[3]) : args.length == 3, "命令参数无效");
            var datasource = new DriverManagerDataSource(required("SPOTLINK_DB_URL"), required("SPOTLINK_DB_USER"), required("SPOTLINK_DB_PASSWORD"));
            if (command.equals("migrate")) {
                Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
                System.out.println("[结构迁移] 已完成；业务和知识尚须独立导入。"); return 0;
            }
            DatasetPackage pack = simple ? null : DatasetPackage.load(Path.of(System.getenv().getOrDefault("SPOTLINK_DATA_ROOT", "/workspace")).toAbsolutePath().normalize(), args[2]);
            try (var repository = new DatasetRepository(datasource.getConnection())) {
                if (command.equals("status")) System.out.println("[数据库台账] " + repository.status());
                else if (command.equals("verify")) { repository.verify(pack, args[3].equals("initial")); System.out.println("[数据校验] " + args[3] + " 通过。"); }
                else System.out.println("[导入] " + command + " " + repository.importPhase(pack, command));
                System.out.println("[向量] " + repository.vectorStatus() + "；embedded=0 表示尚未构建，文本/关键词导入不调用模型。");
            }
            return 0;
        } catch (DatasetRepository.Conflict failure) {
            System.err.println("[冲突] " + failure.getMessage()); return 5;
        } catch (IllegalArgumentException | java.io.IOException failure) {
            System.err.println("[校验失败] " + failure.getMessage()); return 4;
        } catch (Exception failure) {
            // 驱动原始异常可能含 URL/配置，默认只报分类，禁止打印凭证。
            System.err.println("[执行失败] " + (failure.getMessage() != null && failure.getMessage().startsWith("提交结果待核验") ? failure.getMessage() : failure.getClass().getSimpleName() + "；检查本项目数据库健康、结构迁移与数据台账后重试。")); return 6;
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        DatasetPackage.require(value != null && !value.isBlank(), "缺少容器配置 " + name);
        return value;
    }
}
