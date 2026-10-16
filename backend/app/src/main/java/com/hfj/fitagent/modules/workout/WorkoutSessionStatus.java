package com.hfj.fitagent.modules.workout;

/**
 * 训练场次状态。
 *
 * DRAFT 是"进行中草稿"，**不参与任何正式统计**；COMPLETED 只表示"用户点了结束并确认"，
 * 不代表目标完成度 100%；DISCARDED 是用户主动丢弃，保留行以便追溯。
 */
public enum WorkoutSessionStatus {
    DRAFT,
    COMPLETED,
    DISCARDED;

    public boolean isDraft() {
        return this == DRAFT;
    }

    /** 只有已确认结束的场次才进统计。 */
    public boolean countsAsTrained() {
        return this == COMPLETED;
    }
}
