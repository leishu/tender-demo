package org.example.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.net.InetSocketAddress;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 全局 Web 访问日志过滤器。
 *
 * <p>项目为 WebFlux（含 SSE 流式接口），Servlet 的 {@code Filter} 不生效，
 * 因此用 {@link WebFilter} 统一收集每个 HTTP 请求的接口、耗时与结果，
 * 无需在每个 Controller 里重复埋点。</p>
 *
 * <p>开始行要记录命中的 Controller 方法，而 {@code WebFilter} 执行时请求尚未分发、handler 还没解析，
 * 因此通过 {@link ServerWebExchangeDecorator} 监听 handler 属性的写入，解析完成立即输出。</p>
 *
 * <p>耗时在响应流真正结束（完成 / 异常 / 客户端断开）时才统计，
 * 所以 SSE 接口记录的是整段流式输出的实际时长。</p>
 *
 * <p>输出示例：</p>
 * <pre>
 * INFO --> POST /tender/catalog/adjust handler=TenderController#adjustCatalog
 * INFO &lt;-- POST /tender/catalog/adjust -&gt; 200, cost=1523ms, client=127.0.0.1
 * </pre>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebAccessLogFilter implements WebFilter {

    /** 不记录访问日志的路径前缀：监控端点、在线接口文档与静态资源 */
    private static final List<String> EXCLUDED_PATH_PREFIXES =
            List.of("/actuator", "/v3/api-docs", "/swagger-ui", "/webjars", "/favicon.ico");

    /** 单条日志最多记录的 URL 长度，避免超长查询串撑爆日志 */
    private static final int MAX_LOGGED_LENGTH = 200;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isExcluded(path)) {
            return chain.filter(exchange);
        }

        HttpMethod httpMethod = exchange.getRequest().getMethod();
        String method = httpMethod == null ? "-" : httpMethod.name();
        String target = target(exchange.getRequest());
        long startNanos = System.nanoTime();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean();

        // handler 在请求分发时才解析出来，这里在解析完成时补打请求开始行
        ServerWebExchange tracked = new HandlerTrackingExchange(exchange, handler -> {
            if (started.compareAndSet(false, true)) {
                log.info("--> {} {} handler={}", method, target, handlerName(handler));
            }
        });

        return chain.filter(tracked)
                .doOnError(errorRef::set)
                .doFinally(signal -> {
                    // 未匹配到 handler（如 404）时不会有解析回调，兜底补一行
                    if (started.compareAndSet(false, true)) {
                        log.info("--> {} {} handler=-", method, target);
                    }
                    logAccess(exchange, method, target, startNanos, signal, errorRef.get());
                });
    }

    /** 请求结束时输出一行汇总日志：状态码、耗时与客户端地址。 */
    private void logAccess(ServerWebExchange exchange, String method, String target,
                           long startNanos, SignalType signal, Throwable error) {
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        String status = status(exchange, signal, error);
        String client = clientAddress(exchange.getRequest());

        if (error != null) {
            log.warn("<-- {} {} -> {}, cost={}ms, client={}, 异常: {}",
                    method, target, status, costMs, client, error.toString());
        } else if (signal == SignalType.CANCEL) {
            // 客户端主动断开（SSE 场景常见），单独标注便于区分正常完成
            log.info("<-- {} {} -> {}, cost={}ms, client={}, cancelled",
                    method, target, status, costMs, client);
        } else {
            log.info("<-- {} {} -> {}, cost={}ms, client={}",
                    method, target, status, costMs, client);
        }
    }

    /**
     * 解析响应状态码。异常场景下响应可能尚未提交（由更外层的异常处理器写回），
     * 此时优先取异常自身的状态码，例如 {@code ResponseStatusException} 的 400/404；
     * 正常结束时若框架未显式设置，则实际写回的就是默认的 200。
     */
    private String status(ServerWebExchange exchange, SignalType signal, Throwable error) {
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        if (statusCode == null && error instanceof ErrorResponse errorResponse) {
            statusCode = errorResponse.getStatusCode();
        }
        if (statusCode != null) {
            return String.valueOf(statusCode.value());
        }
        return error == null && signal == SignalType.ON_COMPLETE ? "200" : "-";
    }

    /** 命中的处理器：Controller 方法优先，其余取处理器类名。 */
    private static String handlerName(Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
        }
        return handler == null ? "-" : handler.getClass().getSimpleName();
    }

    private static String target(ServerHttpRequest request) {
        String path = request.getPath().value();
        String query = request.getURI().getRawQuery();
        String target = (query == null || query.isEmpty()) ? path : path + "?" + query;
        return target.length() <= MAX_LOGGED_LENGTH
                ? target
                : target.substring(0, MAX_LOGGED_LENGTH) + "...";
    }

    private static String clientAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return "-";
        }
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }

    private static boolean isExcluded(String path) {
        return EXCLUDED_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * 交换对象包装：把属性表换成可观察的实现，
     * 一旦 Spring 写入 {@link HandlerMapping#BEST_MATCHING_HANDLER_ATTRIBUTE} 就回调一次。
     */
    private static final class HandlerTrackingExchange extends ServerWebExchangeDecorator {

        private final Map<String, Object> attributes;

        HandlerTrackingExchange(ServerWebExchange delegate, Consumer<Object> onHandlerResolved) {
            super(delegate);
            this.attributes = new HandlerTrackingAttributes(delegate.getAttributes(), onHandlerResolved);
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }
    }

    /** 属性表包装：读写全部委托给原属性表，只在写入 handler 属性时触发回调。 */
    private static final class HandlerTrackingAttributes extends AbstractMap<String, Object> {

        private final Map<String, Object> delegate;
        private final Consumer<Object> onHandlerResolved;

        HandlerTrackingAttributes(Map<String, Object> delegate, Consumer<Object> onHandlerResolved) {
            this.delegate = delegate;
            this.onHandlerResolved = onHandlerResolved;
        }

        @Override
        public Object put(String key, Object value) {
            Object previous = delegate.put(key, value);
            if (HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE.equals(key) && value != null) {
                onHandlerResolved.accept(value);
            }
            return previous;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return delegate.entrySet();
        }

        @Override
        public Object get(Object key) {
            return delegate.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return delegate.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return delegate.containsValue(value);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public Object remove(Object key) {
            return delegate.remove(key);
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }
}
