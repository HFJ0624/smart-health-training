package com.hfj.fitagent.modules.profile.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 目标状态迁移（activate / archive）的请求体。
 *
 * <p>契约要求"更新/状态迁移必须携带 lockVersion"（docs/p1-api.md §1 乐观锁），而状态本身不在这里传：
 * 动作由路径决定，请求体只带上一次读取到的版本号，防止"点了两次按钮把别人的改动盖掉"。
 */
public record GoalTransitionRequest(

        @NotNull(message = "lockVersion 不能为空")
        Integer lockVersion
) {
}
