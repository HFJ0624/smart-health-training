package com.hfj.fitagent.modules.metrics.support;

import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 修订号读取器：它是 metrics 唯一被批准的跨模块读，行为必须窄到可以一眼看完。
 *
 * <p>重点是"读不到 = null"，不是 0：0 会被页面当成"数据一次都没变过"，
 * 于是旧的统计结论被永久宣称为仍然有效。
 */
class MetricsDataRevisionReaderTest {

    private static final long USER = 7L;

    private UserAccountMapper userAccountMapper;
    private MetricsDataRevisionReader reader;

    @BeforeEach
    void setUp() {
        userAccountMapper = mock(UserAccountMapper.class);
        reader = new MetricsDataRevisionReader(userAccountMapper);
    }

    @Test
    @DisplayName("修订号以字符串出参（BigInt 契约），只做一次只读查询")
    void returnsRevisionAsText() {
        when(userAccountMapper.findDataRevision(USER)).thenReturn(42L);
        assertThat(reader.currentRevisionText(USER)).isEqualTo("42");
    }

    @Test
    @DisplayName("账户行没有修订号时是 null，绝不返回 0")
    void missingRevisionStaysNull() {
        when(userAccountMapper.findDataRevision(USER)).thenReturn(null);
        assertThat(reader.currentRevisionText(USER)).isNull();
    }

    @Test
    @DisplayName("查询本身失败也只标注未知：只读统计接口不该因此变成 500")
    void queryFailureDegradesToNull() {
        when(userAccountMapper.findDataRevision(USER)).thenThrow(new IllegalStateException("连接不可用"));
        assertThat(reader.currentRevisionText(USER)).isNull();
    }
}
