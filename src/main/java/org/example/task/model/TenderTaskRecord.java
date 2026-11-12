package org.example.task.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 招标任务（对应表 t_tender_task）。
 *
 * <p>一行代表一次任务：解析是流式任务，进度由 {@link TenderTaskEventRecord} 承载；
 * 目录/章节是同步任务，最终产物放在 {@link #resultJson}，供幂等重试直接复用。</p>
 *
 * <p>{@code idempotencyKey} 有唯一索引：同一键重复提交只会创建一条任务，
 * 这也是“网络重试不重复调用模型”的落点。</p>
 */
@Data
@TableName("t_tender_task")
public class TenderTaskRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型：parse / catalog / catalog_adjust / chapter */
    private TaskType taskType;

    /** 任务状态：pending / running / succeeded / failed / cancelled */
    private TaskStatus status;

    /** 用户ID；匿名固定为 {@code __anon__}，与 Agent 状态存储保持同一套命名空间 */
    private String userId;

    /** 幂等键，可为空；非空时全局唯一 */
    private String idempotencyKey;

    /** 任务输入快照（JSON），用于失败重跑与问题追溯 */
    private String inputJson;

    /** 可缓存的最终结果（JSON）；解析任务为空 */
    private String resultJson;

    /** 失败或取消原因 */
    private String errorMessage;

    /** 已落库的最大事件序号，0 表示尚无事件 */
    private Integer lastEventSeq;

    /** 创建时间（epoch 毫秒） */
    private Long createdAt;

    /** 更新时间（epoch 毫秒） */
    private Long updatedAt;

    /** 进入终态的时间（epoch 毫秒） */
    private Long finishedAt;
}
