package org.example.enterprise.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
@TableName("t_qualification")
@Schema(description = "企业资质")
public class Qualification {

    private static final Pattern LEVEL_PATTERN =
            Pattern.compile("(特级|壹级|一级|贰级|二级|叁级|三级|甲级|乙级)");

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 资质类型，如 ISO9001 */
    @Schema(description = "资质类型，如 ISO9001", example = "ISO9001")
    private String type;

    /** 资质名称 */
    @Schema(description = "资质名称", example = "质量管理体系认证证书")
    private String name;

    /** 证书编号 */
    @Schema(description = "证书编号", example = "00124Q31281R0M")
    private String certNo;

    /** 证书有效期至，统一以 ISO 字符串返回给模型，避免模型手工转换日期 */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "证书有效期至", example = "2027-05-31")
    private LocalDate validUntil;

    /** 从名称或类型中识别等级，供模型直接使用，避免模型自行推断 */
    @Schema(description = "资质等级（从名称或类型中识别）", example = "贰级")
    public String getLevel() {
        String level = extractLevel(name);
        return level != null ? level : extractLevel(type);
    }

    private static String extractLevel(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = LEVEL_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
