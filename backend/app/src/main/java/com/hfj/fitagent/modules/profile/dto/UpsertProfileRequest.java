package com.hfj.fitagent.modules.profile.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 档案写入请求（POST 建立 / PUT 修改 同形，见 docs/p1-api.md §4.2）。
 *
 * 有意不包含的字段（写了也没人消费，就不录入数据）：
 * <ul>
 *   <li>{@code nickname} —— 归属 user_account，不在 profile 模块写入（跨模块，本轮 SKIP）；</li>
 *   <li>{@code activityCategory} / {@code dietPreferences} / {@code professionalGuidanceRequired}
 *       —— 本轮无消费方，契约明确不开放写入；</li>
 *   <li>{@code id} / {@code userId} —— 身份只能来自认证上下文。</li>
 * </ul>
 * 全局 FAIL_ON_UNKNOWN_PROPERTIES 会把上述任何键直接判为 UNKNOWN_FIELD，客户端无法用多余键影响服务端。
 *
 * <p>{@code lockVersion} 只在 PUT 有意义：POST 携带它会被服务端按 FORBIDDEN_SERVER_FIELD 拒绝，
 * 这是"创建不接受 lockVersion"的落点，同时保持与契约文档 §4.2 的单一请求体形状一致。
 *
 * <p>{@code trainingConstraints} 契约文档示例写的是裸字符串，而库列与实体都是 JSON 数组
 * （V2 迁移 {@code training_constraints JSON}），因此这里按数组接收；差异已在交付说明中标注。
 */
public record UpsertProfileRequest(

        @NotNull(message = "身高不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "身高必须大于 0")
        @Digits(integer = 4, fraction = 2, message = "身高最多 2 位小数")
        BigDecimal heightCm,

        // 契约 §4.2：equipment 1–16 项。传空数组不是"清空"的合法表达（省略字段才是），所以 min=1
        @Size(min = 1, max = 16, message = "器械需 1–16 项")
        List<@Size(max = 64, message = "器械代码过长") String> equipment,

        @Size(max = 16, message = "训练限制最多 16 条")
        List<@Size(max = 500, message = "单条训练限制最长 500 字") String> trainingConstraints,

        @PastOrPresent(message = "出生日期不能晚于今天")
        LocalDate birthDate,

        @Size(max = 16, message = "性别参数非法")
        String sexForEquation,

        @Size(max = 16, message = "训练经验取值非法")
        String trainingLevel,

        Integer lockVersion
) {
}
