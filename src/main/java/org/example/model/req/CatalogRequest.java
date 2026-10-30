package org.example.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "投标目录生成请求")
public class CatalogRequest {
    @Schema(description = "用户ID", example = "user-001")
    private String userId;

    @Schema(description = "招标要求ID", example = "1234567890")
    private Long tenderRequirementId;
}