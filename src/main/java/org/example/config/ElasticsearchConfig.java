package org.example.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
    public ElasticsearchTransport elasticsearchTransport(KnowledgeBaseProperties properties) {
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
        if (StringUtils.hasText(es.getApiKey())) {
            headers.add(new BasicHeader("Authorization", "ApiKey " + es.getApiKey()));
        } else if (StringUtils.hasText(es.getUsername())) {
            String credentials = es.getUsername() + ":" + (es.getPassword() == null ? "" : es.getPassword());
            String encoded = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            headers.add(new BasicHeader("Authorization", "Basic " + encoded));
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
