package com.hfj.fitagent.modules.profile;

import com.hfj.fitagent.modules.profile.entity.BodyMeasurementEntity;
import com.hfj.fitagent.modules.training.entity.TrainingPlanEntity;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntity;
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

    /** 三张带 active_user_id 生成列的表：user_goal、training_plan、workout_session。 */
    private static final List<Class<?>> ENTITIES = List.of(
            UserGoalEntity.class, UserProfileEntity.class, BodyMeasurementEntity.class,
            TrainingPlanEntity.class, WorkoutSessionEntity.class);

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
    @DisplayName("带闸门列的模块源码（去掉注释后）不出现 active_user_id / activeUserId 这两个标识符")
    void moduleSourceNeverReferencesTheGeneratedColumn() throws IOException {
        List<Path> roots = moduleSourceRoots();
        Assumptions.assumeTrue(!roots.isEmpty(), "源码目录不可用（非 Maven 工作目录运行），跳过源码扫描");

        List<String> offenders = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                    if (code.contains("activeUserId") || code.contains("active_user_id")) {
                        offenders.add(root.getParent().relativize(file).toString());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * 只扫"自己拥有闸门列"的那几个模块。
     *
     * <p>为什么不扫全仓：{@code PlanImportParsers} 的"服务端专属字段拒绝清单"里合法地列着
     * {@code active_user_id}（导入时见到就报 UNKNOWN_FIELD），那是防线而不是违规。
     */
    private static List<Path> moduleSourceRoots() {
        List<String> fragments = List.of("com/hfj/fitagent/modules/profile", "com/hfj/fitagent/modules/workout");
        List<String> prefixes = List.of("src/main/java/", "app/src/main/java/", "../app/src/main/java/", "../");
        List<Path> found = new ArrayList<>();
        for (String fragment : fragments) {
            for (String prefix : prefixes) {
                Path path = Path.of(prefix + fragment);
                if (Files.isDirectory(path)) {
                    found.add(path.toAbsolutePath().normalize());
                    break;
                }
            }
        }
        return found;
    }

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\n]*", " ");
    }
}
