package com.hfj.fitagent.agent.model.deepseek;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.core.RunCancellation;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ThinkingMode;
import com.hfj.fitagent.agent.model.ToolChoice;
import com.hfj.fitagent.agent.trace.TraceEventType;
import com.hfj.fitagent.agent.trace.TraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密钥不入日志的证明（docs/agent-v1-design.md §5.7）。
 *
 * <p>断言方式是<b>捕获被测代码真实产生的每一条日志</b>（logback ListAppender 挂在 {@code com.hfj.fitagent.agent}
 * 包 logger 上，覆盖该包全部类），然后要求密钥原文在任何一条消息里都不出现——
 * 而不是只检查"我没写那行 log.info"，后者挡不住异常消息透传这种间接泄露。
 */
class DeepSeekLogSafetyTest {

    private static final String KEY = AgentTestSupport.FAKE_API_KEY;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        Logger packageLogger = (Logger) LoggerFactory.getLogger("com.hfj.fitagent.agent");
        appender = new ListAppender<>();
        appender.start();
        packageLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        Logger packageLogger = (Logger) LoggerFactory.getLogger("com.hfj.fitagent.agent");
        packageLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("401 全流程（含解密失败、异常消息、warn 日志）都不出现 Key 原文")
    void credentialFailureNeverLogsTheKey() {
        DeepSeekWireFormatTest.RecordingTransport transport = new DeepSeekWireFormatTest.RecordingTransport();
        transport.canned.add(new DeepSeekTransport.Inbound(401, "Authentication Fails"));
        DeepSeekClient client = new DeepSeekClient(DeepSeekProperties.defaults(),
                new DeepSeekRequestFactory(AgentTestSupport.mapper(), DeepSeekProperties.defaults()),
                new DeepSeekResponseParser(AgentTestSupport.mapper()),
                new DeepSeekWireFormatTest.RecordingCredentialResolver(), null, transport, AgentTestSupport.clock());

        assertThatThrownBy(() -> client.complete(request(), AgentTestSupport.context(
                com.hfj.fitagent.agent.core.RunLimits.defaults()), new RunCancellation()))
                .isInstanceOf(ModelProviderException.class);

        assertThat(transport.sent).hasSize(1);
        assertThat(transport.sent.get(0).headers().get("Authorization")).isEqualTo("Bearer " + KEY);
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(KEY);
            assertThat(event.getFormattedMessage()).doesNotContain("Bearer " + KEY);
        }
    }

    @Test
    @DisplayName("传输异常与 Outbound.toString 都不带 Key（record 自动 toString 被显式覆盖）")
    void transportFailurePathDoesNotLogHeaders() {
        DeepSeekWireFormatTest.RecordingTransport transport = new DeepSeekWireFormatTest.RecordingTransport();
        transport.failures.add(new DeepSeekTransportException(DeepSeekErrorCode.RESPONSE_UNKNOWN,
                "连接中断，结果未知", true, null));
        DeepSeekClient client = new DeepSeekClient(DeepSeekProperties.defaults(),
                new DeepSeekRequestFactory(AgentTestSupport.mapper(), DeepSeekProperties.defaults()),
                new DeepSeekResponseParser(AgentTestSupport.mapper()),
                new DeepSeekWireFormatTest.RecordingCredentialResolver(), null, transport, AgentTestSupport.clock());

        assertThatThrownBy(() -> client.complete(request(),
                AgentTestSupport.context(com.hfj.fitagent.agent.core.RunLimits.defaults())))
                .isInstanceOfSatisfying(ModelProviderException.class, e -> assertThat(e.getMessage()).doesNotContain(KEY));

        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(KEY);
        }
    }

    @Test
    @DisplayName("轨迹里的工具参数含 api_key 之类字段时必然被脱敏")
    void traceRedactsKeyLikeArguments() {
        TraceRecorder trace = TraceRecorder.inMemory();
        trace.record(TraceEventType.TOOL_CALL, "get_weight_trend", "call-1",
                "{\"days\":7,\"api_key\":\"" + KEY + "\",\"authorization\":\"Bearer " + KEY + "\"}",
                "sk-abcdef123456 也被当成密钥", "DECLARED", null, null);

        String redacted = trace.events().get(0).argumentsRedacted();
        assertThat(redacted).doesNotContain(KEY).doesNotContain("sk-abcdef123456");
        assertThat(redacted).contains("[已脱敏]");
        // 非敏感字段保持可读，否则轨迹就失去排查价值
        assertThat(redacted).contains("days");
    }

    private static ModelRequest request() {
        return new ModelRequest("deepseek-flash",
                List.of(ModelMessage.system("规则"), ModelMessage.user("我的体重")),
                List.of(), ToolChoice.AUTO, ThinkingMode.DISABLED, 1024, null, Duration.ofSeconds(5));
    }
}
