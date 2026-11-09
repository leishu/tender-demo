package org.example.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.extern.slf4j.Slf4j;
import org.example.middleware.LangfuseSpanEnrichmentMiddleware;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Langfuse（cloud.langfuse.com）OpenTelemetry 导出配置。
 *
 * <p>Langfuse 只支持 OTLP/HTTP，认证方式为 Basic Auth：
 * {@code base64(publicKey:secretKey)}。OTLP 路径固定为
 * {@code /api/public/otel/v1/traces}。</p>
 *
 * <p>启用后会把 OTel SDK 注册到 {@link GlobalOpenTelemetry}，
 * AgentScope 的 {@code OtelTracingMiddleware} 会读取该全局实例并上报
 * {@code invoke_agent}、{@code chat}、{@code execute_tool} 三类 span。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true")
public class LangfuseTracingConfig {

    private static final String LANGFUSE_OTLP_TRACES_PATH = "/api/public/otel/v1/traces";

    @Bean
    public LangfuseSpanEnrichmentMiddleware langfuseSpanEnrichmentMiddleware(
            LangfuseProperties properties) {
        return new LangfuseSpanEnrichmentMiddleware(properties.isCaptureContent());
    }

    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk langfuseOpenTelemetry(LangfuseProperties properties) {
        String publicKey = properties.getPublicKey();
        String secretKey = properties.getSecretKey();
        if (!StringUtils.hasText(publicKey) || !StringUtils.hasText(secretKey)) {
            throw new IllegalStateException(
                    "langfuse.enabled=true 时必须配置 langfuse.public-key 与 langfuse.secret-key，"
                            + "建议通过环境变量 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY 注入");
        }

        String endpoint = buildEndpoint(properties.getHost());
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));

        OtlpHttpSpanExporterBuilder exporterBuilder = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", authorization)
                // Langfuse v4 数据模型需要该头，否则 OTLP 数据可能延迟约 10 分钟才可见
                .addHeader("x-langfuse-ingestion-version", "4")
                .setTimeout(properties.getExportTimeout());
        properties.getHeaders().forEach(exporterBuilder::addHeader);

        Resource resource = buildResource(properties);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(sampleRate(properties))))
                // 默认 5s 批量导出；缩短到 2s，降低进程退出前 span 还在队列里导致
                // trace 缺节点（如 AGENT 根节点/最后一轮 GENERATION）的概率
                .addSpanProcessor(BatchSpanProcessor.builder(exporterBuilder.build())
                        .setScheduleDelay(Duration.ofSeconds(2))
                        .build())
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        if (GlobalOpenTelemetry.isSet()) {
            // 追踪不应影响应用启动：已存在全局实例时不再覆盖（例如 OpenTelemetry Java Agent
            // 或其他组件先完成了注册），仅提示配置未生效。
            log.warn("GlobalOpenTelemetry 已被其他组件初始化，跳过 Langfuse SDK 注册；"
                    + "请确认没有其他 OpenTelemetry Agent，或让 Langfuse 配置更早执行");
            return sdk;
        }

        GlobalOpenTelemetry.set(sdk);
        log.info("Langfuse tracing enabled: endpoint={}, service={}, environment={}, sampleRate={}, captureContent={}",
                endpoint,
                properties.getServiceName(),
                properties.getEnvironment(),
                sampleRate(properties),
                properties.isCaptureContent());
        return sdk;
    }

    private static Resource buildResource(LangfuseProperties properties) {
        AttributesBuilder attributes = Attributes.builder()
                .put("service.name", properties.getServiceName())
                .put("deployment.environment.name", properties.getEnvironment());
        if (StringUtils.hasText(properties.getRelease())) {
            attributes.put("service.version", properties.getRelease());
        }
        return Resource.getDefault().merge(Resource.create(attributes.build()));
    }

    private static String buildEndpoint(String host) {
        String base = StringUtils.hasText(host) ? host.trim() : "https://cloud.langfuse.com";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + LANGFUSE_OTLP_TRACES_PATH;
    }

    private static double sampleRate(LangfuseProperties properties) {
        double rate = properties.getSampleRate();
        return Math.max(0.0, Math.min(1.0, rate));
    }
}
