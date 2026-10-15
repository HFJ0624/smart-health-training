package com.hfj.fitagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动装配守卫：多构造器的 Spring 组件必须显式标 {@code @Autowired}。
 *
 * <p>这条不是风格问题。Spring 在候选构造器多于一个且没有 {@code @Autowired} 时，会退回
 * "无参构造器"去找——找不到就是启动期 {@code No default constructor found}，
 * 找得到则更糟：**静默忽略带配置的那个构造器**（例如 {@code AppClock()} 用系统时钟、
 * {@code FakeModelClient()} 用 disabled() 而不是 fitagent.agent.fake-model.* 的配置值）。
 *
 * <p>本项目的单元测试都不起 Spring 上下文（不连库、不起 Docker），所以这类错误
 * **测试全绿也能带着**——只有真启动才会炸。这个检查用组件扫描替代真上下文，
 * 因此不需要数据库就能把同一类问题挡住。
 */
class BeanConstructorAmbiguityTest {

    private static final String BASE_PACKAGE = "com.hfj.fitagent";

    @Test
    @DisplayName("所有 stereotype 组件：构造器多于一个就必须有且仅有一个 @Autowired 构造器")
    void everyMultiConstructorBeanDeclaresItsInjectionPoint() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition def) {
                        // 只看能直接实例化的类（接口/抽象类由配置类手工装配）
                        return def.getMetadata().isConcrete() && def.getMetadata().isIndependent();
                    }
                };
        for (Class<? extends java.lang.annotation.Annotation> marker
                : List.of(Component.class, Service.class, Controller.class, RestController.class)) {
            scanner.addIncludeFilter(new AnnotationTypeFilter(marker));
        }

        List<String> offenders = new ArrayList<>();
        scanner.findCandidateComponents(BASE_PACKAGE).forEach(def -> {
            Class<?> type;
            try {
                type = Class.forName(def.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描到无法加载的组件：" + def.getBeanClassName(), e);
            }
            Constructor<?>[] ctors = type.getDeclaredConstructors();
            if (ctors.length < 2) {
                return;
            }
            long annotated = 0;
            for (Constructor<?> ctor : ctors) {
                // 允许 @Autowired 落在无参构造器上：AppClock()/CoachPromptTemplates() 的生产入口就是无参那个
                // （分别用系统时钟、从 classpath 读模板），标注它才算"声明了注入点"
                if (ctor.isAnnotationPresent(Autowired.class)) {
                    annotated++;
                }
            }
            if (annotated != 1) {
                offenders.add(type.getName() + " 有 " + ctors.length + " 个构造器、"
                        + annotated + " 个标了 @Autowired");
            }
        });

        assertThat(offenders)
                .as("多构造器却没指定注入点的 bean 会让启动失败或静默用错构造器")
                .isEmpty();
    }
}
