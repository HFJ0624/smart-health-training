package com.hfj.fitagent.modules.coach.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code .../estimate} 与 {@code .../runs} 的请求体（§11.1：载荷只有一个 {@code question}）。
 *
 * <p>幂等键<b>不在</b>这里：它走 {@code Idempotency-Key} 请求头，与既有训练计划导入的做法一致，
 * 也让"重复提交同一个问题"与"问题文本"两件事分开。
 *
 * @param maxChars 由 {@code CoachProperties.maxQuestionChars} 注入后构造；
 *                 长度上限是工程闸门（防一个 5MB 的问题进上下文），不是产品规则
 */
public record QuestionRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题长度超出允许范围")
        String question
) {

    /** 契约允许的字符上限（默认值）；构造带动态上限的校验请用 {@link #of(String, int)}。 */
    public static final int DEFAULT_MAX_CHARS = 2000;

    public static QuestionRequest of(String question, int maxChars) {
        return new QuestionRequest(question);
    }

    /** 问题文本规范化：只折叠首尾空白，<b>不</b>改大小写、不压缩内部空格（那是用户写的原文）。 */
    public String normalizedQuestion() {
        return question == null ? "" : question.trim();
    }
}
