package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 轻量幂等键存储：同一个 {@code Idempotency-Key} 在 TTL 内只执行一次创建操作，
 * 后续请求直接返回首次创建的 id。
 *
 * <p>用于 {@code /tender/parse/confirm} 这类“提交后不可重复”的写接口：
 * 前端重复点击、网络重试或客户端超时重发时，不会重复入库。</p>
 *
 * <p>实现为进程内缓存（容量上限 + TTL + 分段锁）。<b>只在单实例内有效</b>：
 * 多实例部署或需要跨重启生效时，应替换为数据库唯一键或 Redis 实现。
 * 未携带幂等键的请求保持原有语义，照常创建。</p>
 */
@Slf4j
@Component
public class IdempotencyStore {

    /** 幂等键有效期：过期后同一 key 可再次创建，避免客户端复用旧 key 永久拿不到新数据 */
    private static final Duration TTL = Duration.ofMinutes(30);

    /** 最多缓存条数，超出后按最久未使用淘汰，防止被随机 key 撑爆内存 */
    private static final int MAX_ENTRIES = 2048;

    /** 分段锁数量：按 key 哈希取模，避免同一 key 并发穿透，同时不让所有 key 串行 */
    private static final int LOCK_STRIPES = 64;

    private final Map<String, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);

    private final Object[] locks = new Object[LOCK_STRIPES];

    public IdempotencyStore() {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    /**
     * 以 {@code key} 为幂等键执行 {@code creator}：键已存在且未过期时直接返回旧值。
     *
     * @param key     幂等键，为空时不做幂等处理，直接执行
     * @param creator 创建逻辑，必须返回新记录的主键
     */
    public Long getOrCreate(String key, Supplier<Long> creator) {
        if (!StringUtils.hasText(key)) {
            return creator.get();
        }
        String normalized = key.trim();
        synchronized (lockFor(normalized)) {
            Entry existing = get(normalized);
            if (existing != null) {
                log.info("命中幂等键，复用已有记录: key={}, id={}", normalized, existing.value());
                return existing.value();
            }
            Long created = creator.get();
            put(normalized, created);
            return created;
        }
    }

    private Object lockFor(String key) {
        return locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    private Entry get(String key) {
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry != null && entry.expiresAt() <= System.currentTimeMillis()) {
                entries.remove(key);
                return null;
            }
            return entry;
        }
    }

    private void put(String key, Long value) {
        synchronized (entries) {
            entries.put(key, new Entry(value, System.currentTimeMillis() + TTL.toMillis()));
            while (entries.size() > MAX_ENTRIES) {
                // accessOrder=true：迭代顺序即最近最少使用顺序
                String eldest = entries.keySet().iterator().next();
                entries.remove(eldest);
            }
        }
    }

    private record Entry(Long value, long expiresAt) {
    }
}
