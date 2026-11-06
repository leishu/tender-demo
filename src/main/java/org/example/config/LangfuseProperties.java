package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Langfuse 链路追踪配置。
 *
 * <p>对应 {@code application.yml} 中的 {@code langfuse} 节点。密钥通过环境变量注入，
 * 不要写入配置文件。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    /** 是否启用 Langfuse 追踪；关闭时不会创建 OTel SDK，也不会注册 OTel 中间件 */
    private boolean enabled = false;

    /** Langfuse 服务地址，EU 区为 https://cloud.langfuse.com */
    private String host = "https://cloud.langfuse.com";

    /** Langfuse 项目公钥（pk-lf-...），通过 LANGFUSE_PUBLIC_KEY 注入 */
    private String publicKey;

    /** Langfuse 项目私钥（sk-lf-...），通过 LANGFUSE_SECRET_KEY 注入 */
    private String secretKey;

    /** 资源属性 service.name，Langfuse 中用于区分服务 */
    private String serviceName = "tender-service";

    /** 部署环境，写入 deployment.environment.name */
    private String environment = "local";

    /** 应用版本，可选，写入 service.version */
    private String release;

    /** 采样率，1.0 表示全量采集 */
    private double sampleRate = 1.0;

    /**
     * 是否把模型输入/输出内容写入 span。
     *
     * <p>开启后可以在 Langfuse 中看到完整提示词与模型回复；招标文件可能包含敏感信息，
     * 生产环境请按需关闭。</p>
     */
    private boolean captureContent = true;

    /** OTLP 导出超时时间 */
    private Duration exportTimeout = Duration.ofSeconds(10);

    /** 额外的 OTLP 请求头，会覆盖同名默认头 */
    private Map<String, String> headers = new LinkedHashMap<>();
}
