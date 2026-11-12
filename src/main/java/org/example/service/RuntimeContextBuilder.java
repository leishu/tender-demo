package org.example.service;

import io.agentscope.core.agent.RuntimeContext;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行时上下文构建器。
 *
 * <p>sessionId 由五段组成，使用 {@code -} 连接：年-月-日-时-当小时内的流水号，
 * 例如 {@code 2026-11-12-08-3} 表示 2026 年 11 月 12 日 08 点创建的第 3 个会话。
 * 流水号在每个自然小时切换时归零重新计数。
 */
@Component
public class RuntimeContextBuilder {

    /** sessionId 前四段的格式：年-月-日-时 */
    private static final DateTimeFormatter HOUR_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    /** 统一时区，避免容器默认 UTC 导致小时字段与预期不符 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 可注入的时钟，便于测试跨小时逻辑；默认使用系统时钟 */
    private final Clock clock;

    /** 当前小时标识 + 该小时内的计数器；整体替换保证跨小时归零 */
    private volatile HourCounter counter =
            new HourCounter("", new AtomicInteger(0));

    public RuntimeContextBuilder() {
        this(Clock.system(ZONE));
    }

    /** 供测试注入固定时钟 */
    RuntimeContextBuilder(Clock clock) {
        this.clock = clock;
    }

    public RuntimeContext build(String userId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(nextSessionId())
                .build();
    }

    /**
     * 生成 sessionId：年-月-日-时-小时流水号。
     *
     * <p>通过 {@code synchronized} 保证"读取/切换计数器"与"自增取号"是原子的，
     * 避免线程在小时切换的瞬间拿到旧小时计数器后仍生成旧小时前缀的 sessionId。
     * 取号是极短的操作，锁竞争可忽略。
     */
    private String nextSessionId() {
        String hourKey = LocalDateTime.now(clock).format(HOUR_PATTERN);
        long seq;
        synchronized (this) {
            if (!hourKey.equals(counter.hourKey())) {
                counter = new HourCounter(hourKey, new AtomicInteger(0));
            }
            seq = counter.sequence().incrementAndGet();
        }
        return hourKey + "-" + seq;
    }

    /** 某一小时内的流水号计数器 */
    private record HourCounter(String hourKey, AtomicInteger sequence) {
    }
}