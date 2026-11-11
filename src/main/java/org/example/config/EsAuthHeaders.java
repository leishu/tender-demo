package org.example.config;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 从 {@code knowledge-base.es} 配置构造 Elasticsearch 的 {@code Authorization} 头。
 *
 * <p>官方 Java Client 与内置 inference 的裸 HTTP 调用共用同一套认证规则，
 * 避免出现“主客户端能连、inference 401”的不一致。</p>
 */
public final class EsAuthHeaders {

    private EsAuthHeaders() {
    }

    /**
     * @return 需要设置的头值；未配置任何认证时返回 {@code null}
     */
    public static String authorization(KnowledgeBaseProperties.Es es) {
        if (es == null) {
            return null;
        }
        // API Key 优先级高于用户名密码
        if (StringUtils.hasText(es.getApiKey())) {
            return "ApiKey " + es.getApiKey().trim();
        }
        if (StringUtils.hasText(es.getUsername())) {
            String credentials = es.getUsername() + ":" + (es.getPassword() == null ? "" : es.getPassword());
            return "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }
}
