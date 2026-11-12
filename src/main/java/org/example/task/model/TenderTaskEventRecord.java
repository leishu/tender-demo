package org.example.task.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 招标任务事件（对应表 t_tender_task_event）。
 *
 * <p>一行代表 SSE 推送过的一条事件。{@code payload} 就是 SSE 的 {@code data} 行原文
 * （如 {@code {"module":"qualification","data":{...}}}），前端可直接复用原有解析逻辑。</p>
 *
 * <p>{@code (taskId, seq)} 唯一：{@code seq} 在任务内从 1 单调递增，既做 SSE 的
 * {@code id} 字段，也做续传游标（{@code Last-Event-ID}）。</p>
 */
@Data
@TableName("t_tender_task_event")
public class TenderTaskEventRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 任务内事件序号，从 1 单调递增 */
    private Integer seq;

    /** 事件类型：project / qualification / performance / personnel / scoringItem / error / completed */
    private String eventType;

    /** 事件载荷（JSON），SSE 的 data 行原文 */
    private String payload;

    /** 创建时间（epoch 毫秒） */
    private Long createdAt;
}
