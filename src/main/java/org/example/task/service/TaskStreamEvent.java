package org.example.task.service;

/**
 * 流式任务的一条事件：SSE 模块名 + 事件负载。
 *
 * <p>负载保持领域侧的原始事件对象，由任务层原样序列化落库，保证事件格式与改造前完全一致。
 * {@code message} 只在 {@code error} 事件上使用，任务层用它落 {@code error_message} 列，
 * 避免为了取一条文案去反射领域事件。</p>
 *
 * @param module  SSE 模块名（{@code project}/{@code personnel}/{@code error}/{@code completed}…）
 * @param payload 事件负载，落库时序列化成 {@code data}
 * @param message 错误描述；非 {@code error} 事件为 {@code null}
 */
public record TaskStreamEvent(String module, Object payload, String message) {

    public TaskStreamEvent(String module, Object payload) {
        this(module, payload, null);
    }
}
