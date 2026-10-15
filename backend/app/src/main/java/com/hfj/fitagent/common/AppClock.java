package com.hfj.fitagent.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 时间的唯一入口，便于测试注入固定时钟。
 * 数据库时间戳一律 UTC；业务日期按用户所在时区推导，不在 SQL 里用 NOW() 推断用户日期。
 */
@Component
public class AppClock {

    private final Clock clock;

    @Autowired
    public AppClock() {
        this(Clock.systemUTC());
    }

    public AppClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate businessDate(ZoneId zone) {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    public LocalDate businessDateOf(Instant instant, ZoneId zone) {
        return LocalDate.ofInstant(instant, zone);
    }

    /** 允许的前向时钟偏移：仅防误填，不是业务规则。 */
    public boolean isImplausibleFuture(Instant instant, java.time.Duration skew) {
        return instant.isAfter(clock.instant().plus(skew));
    }
}
