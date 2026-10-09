package com.spotlink.shared;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** 扫描全部主源码，新增模块同样受检查，避免局部修复后重新混层。 */
class PersistenceLayerArchitectureTest {
    private static final Pattern QUERY_IMPLEMENTATION = Pattern.compile(
            "com\\.baomidou\\.mybatisplus\\.(?:core\\.(?:conditions|toolkit\\.Wrappers)|extension\\.(?:conditions|service|activerecord|toolkit\\.(?:Db|SqlHelper|SqlRunner)))"
            + "|\\b(?:JdbcTemplate|NamedParameterJdbcTemplate|SqlSession)\\b|\\.(?:setSql|lambdaQuery|lambdaUpdate|prepareStatement|createStatement)\\s*\\("
            + "|@(?:Select|Insert|Update|Delete)(?:Provider)?\\b");

    @Test void queryImplementationBelongsToPersistenceLayerAndControllersUseServices() throws Exception {
        var violations = new ArrayList<String>();
        Path root = Path.of("src/main/java");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                String source = Files.readString(file);
                boolean persistence = path.contains("/mapper/") || path.contains("/repository/");
                if (!persistence && QUERY_IMPLEMENTATION.matcher(source).find()) violations.add(path + ": 查询实现不在持久化层");
                if (!persistence) {
                    var packageMatch = Pattern.compile("package ([^;]+);").matcher(source);
                    assertThat(packageMatch.find()).isTrue();
                    String owner = packageMatch.group(1);
                    var mapperImports = Pattern.compile("import (com\\.spotlink\\.[^;]+)\\.mapper\\.[^;]+;").matcher(source);
                    while (mapperImports.find()) {
                        if (!owner.startsWith(mapperImports.group(1) + "."))
                            violations.add(path + ": 跨模块直接依赖 Mapper，须调用所属模块服务契约");
                    }
                }
                if (path.contains("/controller/") && Pattern.compile("import com\\.spotlink\\.[^;]+\\.(?:mapper|repository)\\.").matcher(source).find())
                    violations.add(path + ": Controller 直接访问持久化层");
            }
        }
        assertThat(violations).isEmpty();
    }
}
