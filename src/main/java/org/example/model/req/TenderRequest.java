package org.example.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "招标文件解析请求")
public class TenderRequest {
    @Schema(description = "用户ID", example = "user-001")
    private String userId;

    @Schema(description = "招标文件原始文本", example = "本项目要求投标人须具备建筑工程施工总承包壹级及以上资质……")
    private String tenderText;
}