package org.example.matching.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 匹配请求。
 */
@Data
@Schema(description = "人员/企业资质匹配请求")
public class MatchRequest {

    @Schema(description = "用户ID（可选）")
    private String userId;

    @Schema(description = "已入库的招标要求主键", example = "1234567890", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tenderRequirementId;
}
