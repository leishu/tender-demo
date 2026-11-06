package org.example.util;

/**
 * 从流式文本缓冲区中提取完整 JSON 对象。
 *
 * <p>模型以 {@code TextBlockDeltaEvent} 增量输出 JSON，单个对象可能被拆到多个
 * delta 片段中。本工具在缓冲区上做字符串感知的大括号配对，只有对象完整闭合后
 * 才取走原文，调用方即可“凑齐一个推送一个”。</p>
 *
 * <p>扫描前会先修复模型漏转义的双引号（见 {@link #repairUnescapedQuotes(CharSequence)}）：
 * 中文正文里写成 {@code "reason":"未找到"一级资质"} 这种裸引号时，既可能让大括号
 * 配对失准，也会在下游反序列化时整条结果被丢弃。</p>
 *
 * <p>数组形式也可处理：扫描时会跳过对象之外的 {@code [}、逗号等字符，逐个取出
 * 数组中的对象，因此模型误输出成数组时不会整批丢失。</p>
 */
public final class JsonStreamExtractor {

    private JsonStreamExtractor() {
    }

    /**
     * 从缓冲前部取走第一个完整闭合的 JSON 对象原文，取不到返回 {@code null}。
     *
     * @param buffer 累积的流式文本，取走对象后原地删除对应片段
     */
    public static String takeFirstCompleteJson(StringBuilder buffer) {
        String repaired = repairUnescapedQuotes(buffer);
        if (!repaired.contentEquals(buffer)) {
            buffer.replace(0, buffer.length(), repaired);
        }

        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '{':
                    if (depth == 0) {
                        start = i;
                    }
                    depth++;
                    break;
                case '}':
                    depth--;
                    if (depth == 0 && start >= 0) {
                        String json = buffer.substring(start, i + 1);
                        buffer.delete(start, i + 1);
                        return json;
                    }
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    /**
     * 给字符串值内部未转义的双引号补上反斜杠转义。
     *
     * <p>判据是“引号后跳过空白紧跟 {@code ,} {@code } {@code ]} 才算字符串结束”，
     * 其余位置的引号按 {@code \"} 转义；字段名（紧跟 {@code :} 的那个字符串）额外允许
     * 以冒号结尾，避免把 {@code "详见"第三章:评分"} 这类正文里的冒号误判成键值分隔。</p>
     *
     * <p>合法 JSON 的闭合引号后面必然是结构字符或空白，因此对合法输入是幂等的，
     * 未经修复的原文原样返回。字符串末尾恰好是一个裸引号后紧跟逗号这类场景无法区分，
     * 这里按“闭合”处理，与不修复时的行为一致。</p>
     *
     * @param text 待修复的流式文本
     * @return 修复后的文本，无需修复时与入参内容相同
     */
    public static String repairUnescapedQuotes(CharSequence text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        boolean inFieldName = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!inString) {
                if (c == '"') {
                    inString = true;
                    inFieldName = startsFieldName(text, i);
                }
                out.append(c);
                continue;
            }
            if (escaped) {
                escaped = false;
                out.append(c);
            } else if (c == '\\') {
                escaped = true;
                out.append(c);
            } else if (c == '"') {
                if (isStringEnd(text, i + 1, inFieldName)) {
                    inString = false;
                    out.append(c);
                } else {
                    out.append('\\').append('"');
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 引号前的上一个非空白字符是 {@code {} 或 {@code ,} 时，视为字段名的起始引号。
     * 数组元素字符串也会命中这条规则，但只在字符串内部出现冒号时产生影响，代价可接受。
     */
    private static boolean startsFieldName(CharSequence text, int quoteIndex) {
        for (int i = quoteIndex - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '{' || c == ',';
        }
        return true;
    }

    /**
     * 判断当前位置的引号是否为字符串结束引号：其后（跳过空白）只能出现结构字符。
     * 文本已到结尾时按“结束”处理，等后续增量到达再重新判定。
     */
    private static boolean isStringEnd(CharSequence text, int from, boolean inFieldName) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == ',' || c == '}' || c == ']' || (inFieldName && c == ':');
        }
        return true;
    }
}
