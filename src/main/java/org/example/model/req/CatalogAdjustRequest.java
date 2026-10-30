package org.example.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.example.model.catalog.dto.CatalogResult;

@Getter
@Setter
@Schema(description = "投标目录调整请求")
public class CatalogAdjustRequest extends CatalogRequest {

    @Schema(description = "上一轮生成的目录结果，原样传入 /tender/catalog 的响应体",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private CatalogResult previousCatalog;

    @Schema(description = "用户调整要求，如调整章节顺序、拆分/合并章节、改名、增删章节",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "将“技术方案”拆分为“总体架构”“数据处理”“算法模型”三个二级章节，并将“售后服务体系”提前到第 3 章")
    private String customPrompt;
}
