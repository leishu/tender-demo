package org.example.service;

import io.agentscope.core.agent.RuntimeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时上下文构建器。
 *
 * <p>sessionId 由五段组成，使用 {@code -} 连接：年-月-日-时-当小时内的流水号，
 * 例如 {@code 2026-11-12-08-3} 表示 2026 年 11 月 12 日 08 点创建的第 3 个会话。
 * 流水号在每个自然小时切换时归零重新计数。
 */
@Slf4j
@Component
public class RuntimeContextBuilder {

    /** sessionId 前四段的格式：年-月-日-时 */
    private static final DateTimeFormatter HOUR_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    /** 当前小时标识 + 该小时内的计数器 */
    private final AtomicReference<HourCounter> counterRef =
            new AtomicReference<>(new HourCounter("", new AtomicLong(0)));

    public RuntimeContext build(String userId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(nextSessionId())
                .build();
    }

    /**
     * 生成 sessionId：年-月-日-时-小时流水号。
     *
     * <p>小时切换时通过 CAS 更换计数器实例，保证跨小时的并发调用也能正确归零。
     */
    private String nextSessionId() {
        String hourKey = LocalDateTime.now().format(HOUR_PATTERN);
        HourCounter counter;
        for (; ; ) {
            HourCounter current = counterRef.get();
            if (current.hourKey().equals(hourKey)) {
                counter = current;
                break;
            }
            HourCounter fresh = new HourCounter(hourKey, new AtomicLong(0));
            if (counterRef.compareAndSet(current, fresh)) {
                counter = fresh;
                break;
            }
        }
        return hourKey + "-" + counter.sequence().incrementAndGet();
    }

    /** 某一小时内的流水号计数器 */
    private record HourCounter(String hourKey, AtomicLong sequence) {
    }
}
