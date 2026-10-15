package com.hfj.fitagent.agent.fake;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.model.ModelClient;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 脚本化 Fake 模型：只为"协议闭环 + 循环行为"服务，<b>不是</b>生产回退（design §13）。
 *
 * <p>装配条件（两把锁，缺一都不生效）：
 * <ul>
 *   <li>profile 是 dev 或 test；</li>
 *   <li>{@code fitagent.agent.fake-model.enabled=true}。</li>
 * </ul>
 * 第三把锁在 {@link FakeModelStartupGuard}（prod + enabled 直接启动失败）与
 * {@code ModelClientLocator}（生产环境解析到合成实现就抛）里。
 *
 * <p>行为约定：
 * <ul>
 *   <li>按队列顺序返回预置 {@link ModelResponse}，并<b>完整记录</b>每一次 {@link ModelRequest}
 *       供断言（消息配对、tool_choice、工具声明等）；</li>
 *   <li>队列用尽时抛 {@link FakeErrorCode#scriptExhausted(int)}——绝不"重复最后一条"或返回空回答，
 *       否则测试会静默通过一个其实没发生的调用；</li>
 *   <li>每次返回的 {@code providerRequestId} 都带 {@code FAKE-} 前缀，让"这条结果来自假模型"
 *       在轨迹与界面里可见（design §12）。</li>
 * </ul>
 *
 * <p>非线程安全且刻意如此：dev 单线程脚本 + 单元测试直接 new 出来用。
 */
@Component
@Profile({"dev", "test"})
@ConditionalOnProperty(prefix = "fitagent.agent.fake-model", name = "enabled", havingValue = "true")
public class FakeModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(FakeModelClient.class);
    private static final String REQUEST_ID_PREFIX = "FAKE-";

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<ModelRequest> recorded = new ArrayList<>();
    private final String providerId;
    private final String label;

    @Autowired
    public FakeModelClient(FakeModelProperties properties) {
        this.providerId = properties.providerId();
        this.label = properties.label();
        log.warn("Fake 模型客户端已装配 provider={} label={} —— 本轮所有\"模型回答\"都来自本地脚本，"
                + "不能作为供应商联调或费用结论的证据", providerId, label);
    }

    /** 无配置装配（单测）。 */
    public FakeModelClient() {
        this(FakeModelProperties.disabled());
    }

    @Override
    public String providerId() {
        return providerId;
    }

    /** 合成实现：ModelClientLocator 据此在生产环境拒绝它。 */
    @Override
    public boolean synthetic() {
        return true;
    }

    public FakeModelClient enqueue(ModelResponse response) {
        script.add(response);
        return this;
    }

    public FakeModelClient enqueueAll(ModelResponse... responses) {
        for (ModelResponse response : responses) {
            enqueue(response);
        }
        return this;
    }

    /** 预置一次失败：用来测"供应商错误 / 结果未知"分支，而不是只在成功路径上打转。 */
    public FakeModelClient enqueueFailure(RuntimeException failure) {
        script.add(failure);
        return this;
    }

    public int remaining() {
        return script.size();
    }

    public List<ModelRequest> recordedRequests() {
        return List.copyOf(recorded);
    }

    public int callCount() {
        return recorded.size();
    }

    /** 断言用：第 n 次调用收到的请求。 */
    public ModelRequest requestAt(int index) {
        return recorded.get(index);
    }

    /** 模式标签，界面据此显示"假模型"角标。 */
    public String label() {
        return label;
    }

    @Override
    public ModelResponse complete(ModelRequest request, AgentContext context) {
        recorded.add(request);
        if (script.isEmpty()) {
            throw FakeErrorCode.scriptExhausted(recorded.size() - 1);
        }
        Object next = script.removeFirst();
        if (next instanceof RuntimeException failure) {
            throw failure;
        }
        ModelResponse response = (ModelResponse) next;
        // 假响应也要带可识别的 requestId：任何一条结果都能被追溯到"这是脚本，不是 DeepSeek"
        return new ModelResponse(response.content(), response.toolCalls(), response.finishReason(),
                response.usage(), REQUEST_ID_PREFIX + recorded.size(), response.truncated());
    }
}
