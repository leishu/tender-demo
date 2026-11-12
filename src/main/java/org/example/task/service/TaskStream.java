package org.example.task.service;

import reactor.core.publisher.Flux;

/**
 * 领域侧交给任务层的流式任务契约。
 *
 * <p>任务层只认“一条事件流 + 两条约定”，不认识具体领域服务（解析、匹配），
 * 领域侧也不需要知道任务表怎么落库、事件怎么推送：</p>
 *
 * <ul>
 *   <li>流自然结束时，最后一条事件应是 {@code completed}，任务层据此把任务落为
 *       {@code succeeded} 并把该事件补写进事件表；只有它才算成功，连接关闭不算。</li>
 *   <li>{@code errorRecordedInStream} 表示中间的 {@code error} 事件是否已经随流落库：
 *       匹配的 error 是“某一路失败/缺条”的非终止告警，必须保留给回放；
 *       解析的 error 只作为终止原因，等任务层抢到终态后再写，避免取消与自然完成
 *       并发时事件流里出现两个互相矛盾的终止事件。</li>
 * </ul>
 *
 * <p>事件流应当是冷的（模型调用发生在订阅时），这样幂等命中、参数校验失败都不会白跑一次。</p>
 *
 * @param events                事件流
 * @param errorRecordedInStream {@code error} 事件是否随流落库
 */
public record TaskStream(Flux<TaskStreamEvent> events, boolean errorRecordedInStream) {
}
