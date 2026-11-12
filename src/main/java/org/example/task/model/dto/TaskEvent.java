package org.example.task.model.dto;

/**
 * 任务事件的传输形态：{@code payload} 为 SSE 的 {@code data} 行原文。
 *
 * <p>对外推送时 {@code seq} 同时写进 SSE 的 {@code id} 字段，前端重连把它作为
 * {@code Last-Event-ID} 回传即可从下一条继续。</p>
 */
public record TaskEvent(int seq, String eventType, String payload) {
}
