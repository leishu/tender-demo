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

    @Schema(description = "会话ID（可选）。传入同一值可续写同一段会话；不传则由服务端按小时生成新会话。"
            + "仅允许字母、数字、点、下划线、连字符，长度 1-128",
            example = "conv-20261113-001")
    private String sessionId;

    @Schema(description = "已入库的招标要求主键", example = "1234567890", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tenderRequirementId;
}
