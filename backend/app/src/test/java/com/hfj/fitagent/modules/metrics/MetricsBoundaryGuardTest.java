package com.hfj.fitagent.modules.metrics;

import com.hfj.fitagent.modules.metrics.support.MetricsDataRevisionReader;
import com.hfj.fitagent.modules.metrics.vo.MetricsVos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块边界守卫：把 metrics 的两条硬约束变成可执行的断言，而不是只写在注释里。
 *
 * <p>AGENTS.md 与 {@code docs/p1-architecture.md} §4 规定业务模块之间只走 public Service 方法，
 * 且实体永不出现在响应里。这两条都属于"新加一个依赖就静默破掉"的规则，靠人记迟早会漏，
 * 所以这里对整个包做扫描式检查。
 */
class MetricsBoundaryGuardTest {

    @Test
    @DisplayName("整个 metrics 包只有那个被批准的只读小件碰过别人的 Mapper")
    void onlyTheSanctionedReaderTouchesAMapper() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : allMetricsTypes()) {
            for (Field field : type.getDeclaredFields()) {
                String typeName = field.getType().getName();
                if (typeName.endsWith("Mapper") && type != MetricsDataRevisionReader.class) {
                    offenders.add(type.getName() + " 注入了 " + typeName);
                }
            }
        }
        assertThat(offenders).as("跨模块 Mapper 依赖必须收敛在 MetricsDataRevisionReader 一处").isEmpty();
    }

    @Test
    @DisplayName("被批准的只读小件窄到一眼看完：一个依赖、一个方法、只读修订号")
    void sanctionedReaderStaysMinimal() {
        List<String> injected = new ArrayList<>();
        for (Field field : MetricsDataRevisionReader.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                injected.add(field.getType().getSimpleName());
            }
        }
        assertThat(injected).as("只允许注入那一个只读 Mapper").containsExactly("UserAccountMapper");
        List<String> methods = new ArrayList<>();
        for (java.lang.reflect.Method method : MetricsDataRevisionReader.class.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                methods.add(method.getName());
            }
        }
        assertThat(methods).containsExactly("currentRevisionText");
    }

    @Test
    @DisplayName("统计出参里不出现任何实体类型：entity 永不出现在响应里")
    void responseVosNeverCarryAnEntity() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : allMetricsTypes()) {
            if (!type.isRecord()) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                String typeName = component.getType().getName();
                if (typeName.contains(".entity.")) {
                    offenders.add(type.getName() + "." + component.getName() + " : " + typeName);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /** 扫描范围必须真的覆盖到 VO 树，否则上面两条断言会"空工地通过"。 */
    @Test
    @DisplayName("扫描覆盖到 metrics 的全部类（含 MetricsVos 的嵌套出参）")
    void scanActuallyCoversTheModule() {
        List<Class<?>> types = allMetricsTypes();
        assertThat(types).anyMatch(type -> type == TrainingMetricsService.class);
        assertThat(types).anyMatch(type -> type == MetricsRules.class);
        assertThat(types).anyMatch(type -> type == MetricsVos.Metric.class);
        assertThat(types).anyMatch(type -> type == MetricsVos.DayComparison.class);
        assertThat(types).anyMatch(type -> type == MetricsVos.WeekSummary.class);
        assertThat(types).anyMatch(type -> type.getName().startsWith("com.hfj.fitagent.modules.metrics.vo"));
        assertThat(types).hasSizeGreaterThan(20);
    }

    // ================================================================== 小件

    /** 从已编译产物里把本模块的类全部加载回来（含嵌套 record），不依赖任何清单。 */
    private static List<Class<?>> allMetricsTypes() {
        try {
            Path classesRoot = Path.of(MetricsRules.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            Path packageDir = classesRoot.resolve(Path.of("com", "hfj", "fitagent", "modules", "metrics"));
            assertThat(Files.isDirectory(packageDir)).as("找不到已编译的 metrics 包：" + packageDir).isTrue();
            List<Class<?>> loaded = new ArrayList<>();
            try (var files = Files.walk(packageDir)) {
                for (File file : files.filter(p -> p.toString().endsWith(".class"))
                        .map(Path::toFile).toList()) {
                    String relative = packageDir.relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                    String className = "com.hfj.fitagent.modules.metrics."
                            + relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
                    loaded.add(Class.forName(className, false, MetricsBoundaryGuardTest.class.getClassLoader()));
                }
            }
            return loaded;
        } catch (ReflectiveOperationException | java.io.IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException("metrics 包扫描失败", e);
        }
    }
}
