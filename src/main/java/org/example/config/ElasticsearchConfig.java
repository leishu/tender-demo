package org.example.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch 客户端配置。
 *
 * <p>使用官方 Java API Client，支持用户名密码与 API Key 两种认证方式。</p>
 */
@Configuration
@EnableConfigurationProperties(KnowledgeBaseProperties.class)
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(
            KnowledgeBaseProperties properties,
            ObjectProvider<OpenTelemetrySdk> openTelemetrySdkProvider) {
        // ES Java Client 9.x 在构造 transport 时会调用 GlobalOpenTelemetry.get()，
        // 若此时还没有注册 SDK，它会把全局实例固化为 no-op，导致后续 Langfuse 上报失效。
        // 这里先触发 OTel SDK 的创建；未启用 Langfuse 时该调用不会产生副作用。
        openTelemetrySdkProvider.getIfAvailable();

        KnowledgeBaseProperties.Es es = properties.getEs();

        List<URI> uris = es.getUris().stream()
                .filter(StringUtils::hasText)
                .map(URI::create)
                .toList();
        if (uris.isEmpty()) {
            throw new IllegalStateException("knowledge-base.es.uris 不能为空");
        }

        Rest5ClientBuilder builder = Rest5Client.builder(uris.toArray(URI[]::new));

        List<Header> headers = new ArrayList<>();
        String authorization = EsAuthHeaders.authorization(es);
        if (authorization != null) {
            headers.add(new BasicHeader("Authorization", authorization));
        }
        if (!headers.isEmpty()) {
            builder.setDefaultHeaders(headers.toArray(Header[]::new));
        }

        return new Rest5ClientTransport(builder.build(), new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
