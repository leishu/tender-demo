package org.example.model.require;

import com.baomidou.mybatisplus.annotation.TableField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 招标解析相关模型基类，统一承载实体类型判别字段 {@link #entityType}。
 */
@Data
@Schema(description = "招标解析模型基类")
public abstract class TenderModel {

    /** 判别字段：标识本对象对应的实体类型 */
    @TableField(exist = false)
    @Schema(description = "判别字段：标识本对象对应的实体类型，如 project/qualification/performance/personnel/scoringItem")
    private String entityType;
}