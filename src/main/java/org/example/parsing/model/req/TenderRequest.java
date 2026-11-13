package org.example.parsing.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "招标文件解析请求")
public class TenderRequest {
    @Schema(description = "用户ID", example = "user-001")
    private String userId;

    @Schema(description = "会话ID（可选）。传入同一值可续写同一段会话，Agent 会带上该会话的历史上下文；"
            + "不传则由服务端按小时生成新会话。仅允许字母、数字、点、下划线、连字符，长度 1-128",
            example = "conv-20261113-001")
    private String sessionId;

    @Schema(description = "招标文件原始文本", example = "本项目要求投标人须具备建筑工程施工总承包壹级及以上资质……")
    private String tenderText;
}