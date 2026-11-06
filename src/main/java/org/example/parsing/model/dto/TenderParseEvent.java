package org.example.parsing.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.example.parsing.model.PersonnelRequirement;
import org.example.parsing.model.PerformanceRequirement;
import org.example.parsing.model.QualificationRequirement;
import org.example.parsing.model.ScoringItem;
import org.example.parsing.model.TenderModel;
import org.example.parsing.model.TenderRequirements;

/**
 * 招标文件解析的流式事件单元。
 *
 * <p>对应 SSE 中每条推送的结构化事件，统一为
 * {@code {"module":"<模块名>","data":{...}}}；其中
 * {@code error} 事件以 {@code message} 承载错误信息，{@code completed} 事件二者皆为空。</p>
 *
 * @param <T> 本事件 data 所承载的结构体类型，限于 {@link TenderModel} 的子类
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "招标文件解析流式事件")
public class TenderParseEvent<T extends TenderModel> {

    @Schema(description = "模块名：project/qualification/performance/personnel/scoringItem/error/completed")
    private ParseModule module;

    @Schema(description = "对应模块的结构化数据（按 module 对应具体结构）；error/completed 事件为空",
            oneOf = {TenderRequirements.class, QualificationRequirement.class,
                    PerformanceRequirement.class, PersonnelRequirement.class, ScoringItem.class})
    private T data;

    @Schema(description = "error 事件时的错误描述")
    private String message;

    public TenderParseEvent() {
    }

    public TenderParseEvent(ParseModule module) {
        this.module = module;
    }

    public TenderParseEvent(ParseModule module, T data) {
        this.module = module;
        this.data = data;
    }

    public static TenderParseEvent<?> error(String message) {
        TenderParseEvent<?> event = new TenderParseEvent<>(ParseModule.ERROR);
        event.setMessage(message);
        return event;
    }

    public static TenderParseEvent<?> completed() {
        return new TenderParseEvent<>(ParseModule.COMPLETED);
    }
}