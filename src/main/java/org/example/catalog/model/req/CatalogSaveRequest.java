package org.example.catalog.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.example.catalog.model.dto.CatalogResult;

@Getter
@Setter
@Schema(description = "投标目录保存请求")
public class CatalogSaveRequest extends CatalogRequest {

    @Schema(description = "待保存的目录结果，原样传入 /tender/catalog 或 /tender/catalog/adjust 的响应体",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private CatalogResult catalogResult;
}
