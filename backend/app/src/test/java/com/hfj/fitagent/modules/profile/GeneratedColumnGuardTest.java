package com.hfj.fitagent.modules.profile;

import com.hfj.fitagent.modules.profile.entity.BodyMeasurementEntity;
import com.hfj.fitagent.modules.profile.entity.UserGoalEntity;
import com.hfj.fitagent.modules.profile.entity.UserProfileEntity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成列 {@code active_user_id} 的写入防线（验收 A13 在应用侧能证明的那一半）。
 *
 * <p>数据库层面"ORM 生成的 SQL 不含该列"要在真实 MySQL 8.4 上跑 {@code @Tag("requires-docker")} 的用例才能坐实；
 * 这里能确定性地证明的是两件更根本的事：实体类压根没有这个属性，以及本模块源码里没有任何一处提到要写它。
 * 只要这两条成立，ORM 就没有拼出 {@code SET active_user_id = ...} 的机会。
 */
class GeneratedColumnGuardTest {

    private static final List<Class<?>> ENTITIES = List.of(
            UserGoalEntity.class, UserProfileEntity.class, BodyMeasurementEntity.class);

    @Test
    @DisplayName("实体不声明 active_user_id：从源头杜绝向 STORED 生成列写入")
    void entitiesDoNotExposeTheGeneratedColumn() {
        for (Class<?> entity : ENTITIES) {
            List<String> fields = Arrays.stream(entity.getDeclaredFields())
                    .map(f -> f.getName())
                    .toList();
            assertThat(fields)
                    .as("%s 不得映射生成列", entity.getSimpleName())
                    .doesNotContain("activeUserId");
        }
    }

    @Test
    @DisplayName("模块源码（去掉注释后）不出现 active_user_id / activeUserId 这两个标识符")
    void moduleSourceNeverReferencesTheGeneratedColumn() throws IOException {
        Path sourceRoot = moduleSourceRoot();
        Assumptions.assumeTrue(sourceRoot != null && Files.isDirectory(sourceRoot),
                "源码目录不可用（非 Maven 工作目录运行），跳过源码扫描");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains("activeUserId") || code.contains("active_user_id")) {
                    offenders.add(sourceRoot.relativize(file).toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /** surefire 的工作目录是模块基目录（backend/app）；从那里向上找源码路径。 */
    private static Path moduleSourceRoot() {
        String fragment = "com/hfj/fitagent/modules/profile";
        List<String> candidates = List.of(
                "src/main/java/" + fragment,
                "app/src/main/java/" + fragment,
                "../app/src/main/java/" + fragment,
                "../" + fragment);
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\n]*", " ");
    }
}
