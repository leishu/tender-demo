package org.example.catalog.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "投标目录生成请求")
public class CatalogRequest {
    @Schema(description = "用户ID", example = "user-001")
    private String userId;

    @Schema(description = "会话ID（可选）。传入同一值可续写同一段会话，目录生成与调整会共享历史上下文；"
            + "不传则由服务端按小时生成新会话。仅允许字母、数字、点、下划线、连字符，长度 1-128",
            example = "conv-20261113-001")
    private String sessionId;

    @Schema(description = "招标要求ID", example = "1234567890")
    private Long tenderRequirementId;
}