package org.example.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "投标章节撰写请求")
public class ChapterRequest {
    @Schema(description = "用户ID", example = "user-001")
    private String userId;

    @Schema(description = "会话ID", example = "session-001")
    private String sessionId;

    @Schema(description = "章节标题", example = "施工组织设计")
    private String chapterTitle;

    @Schema(description = "关联的评分点ID集合，逗号分隔", example = "1,2,3")
    private String scoringPointIds;
}