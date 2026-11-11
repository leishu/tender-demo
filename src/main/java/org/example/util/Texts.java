package org.example.util;

/**
 * 文本长度工具。
 *
 * <p>数据库 {@code utf8mb4} 的 {@code VARCHAR(n)} 约束的是字符数（code point），
 * 而 {@link String#length()} 与 {@link String#substring(int, int)} 按 UTF-16 code unit
 * 计数。含 emoji、生僻字等代理对字符时，两者口径不一致：按 length 判长会多算，
 * 直接 substring 还可能把代理对切成孤立代理，落库后变成乱码。</p>
 *
 * <p>因此所有“按列宽截断/判长”的场景统一走本工具，保证与校验侧口径一致。</p>
 */
public final class Texts {

    private Texts() {
    }

    /**
     * 按字符（code point）计算长度；{@code null} 视为 0。
     */
    public static int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /**
     * 是否超过字符数上限；{@code null} 视为未超限。
     */
    public static boolean exceeds(String value, int max) {
        return value != null && codePointLength(value) > max;
    }

    /**
     * 按字符（code point）截断到 {@code max} 个字符；{@code null} 原样返回。
     * 截断点一定落在完整字符边界上，不会产生孤立代理。
     */
    public static String truncate(String value, int max) {
        if (value == null || max <= 0) {
            return value;
        }
        int length = codePointLength(value);
        if (length <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }
}
