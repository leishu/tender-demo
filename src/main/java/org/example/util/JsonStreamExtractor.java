package org.example.util;

/**
 * 从流式文本缓冲区中提取完整 JSON 对象。
 *
 * <p>模型以 {@code TextBlockDeltaEvent} 增量输出 JSON，单个对象可能被拆到多个
 * delta 片段中。本工具在缓冲区上做字符串感知的大括号配对，只有对象完整闭合后
 * 才取走原文，调用方即可“凑齐一个推送一个”。</p>
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
}
