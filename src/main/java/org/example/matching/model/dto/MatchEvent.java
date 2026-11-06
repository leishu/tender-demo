package org.example.matching.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.example.matching.model.PersonnelMatchResult;
import org.example.matching.model.QualificationMatchResult;

/**
 * 匹配流式事件单元。
 *
 * <p>SSE 中每条推送统一为 {@code {"module":"<模块名>","data":{...}}}；
 * {@code error} 事件以 {@code message} 承载错误信息，{@code completed} 事件二者皆为空。</p>
 *
 * @param <T> 本事件 data 所承载的匹配结果类型
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "人员/企业资质匹配流式事件")
public class MatchEvent<T> {

    @Schema(description = "模块名：personnel/qualification/error/completed")
    private MatchModule module;

    @Schema(description = "匹配结果；error/completed 事件为空",
            oneOf = {PersonnelMatchResult.class, QualificationMatchResult.class})
    private T data;

    @Schema(description = "error 事件时的错误描述")
    private String message;

    public MatchEvent() {
    }

    public MatchEvent(MatchModule module, T data) {
        this.module = module;
        this.data = data;
    }

    public static MatchEvent<?> error(String message) {
        MatchEvent<?> event = new MatchEvent<>();
        event.setModule(MatchModule.ERROR);
        event.setMessage(message);
        return event;
    }

    public static MatchEvent<?> completed() {
        MatchEvent<?> event = new MatchEvent<>();
        event.setModule(MatchModule.COMPLETED);
        return event;
    }
}
