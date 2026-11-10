package org.example.util;

/**
 * 从流式文本中提取完整 JSON 对象。
 *
 * <p>模型以 {@code TextBlockDeltaEvent} 增量输出 JSON，单个对象可能被拆到多个
 * delta 片段中。本工具做字符串感知的大括号配对，只有对象完整闭合后才取走原文，
 * 调用方即可“凑齐一个推送一个”。</p>
 *
 * <p>扫描过程中会识别并修复模型漏转义的双引号（见 {@link #repairUnescapedQuotes(CharSequence)}）：
 * 中文正文里写成 {@code "reason":"未找到"一级资质"} 这种裸引号时，既可能让大括号
 * 配对失准，也会在下游反序列化时整条结果被丢弃。</p>
 *
 * <p>数组形式也可处理：扫描时会跳过对象之外的 {@code [}、逗号等字符，逐个取出
 * 数组中的对象，因此模型误输出成数组时不会整批丢失。</p>
 *
 * <p><b>性能：</b>流式场景请使用 {@link Reader}。它一次只向前扫描新增片段，
 * 复杂度与输入长度线性相关；{@link #takeFirstCompleteJson(StringBuilder)} 为兼容旧调用保留，
 * 每次都会从头扫描整段缓冲区，长输出下会退化为 O(n²)。</p>
 */
public final class JsonStreamExtractor {

    private JsonStreamExtractor() {
    }

    /**
     * 从缓冲前部取走第一个完整闭合的 JSON 对象原文，取不到返回 {@code null}。
     *
     * <p>内部用 {@link Reader} 实现，等价于“新建 reader → 追加 → 取一个”，
     * 适合一次性处理；持续追加的流式场景请直接复用 {@link Reader}。</p>
     *
     * @param buffer 累积的流式文本，取走对象后原地删除对应片段
     */
    public static String takeFirstCompleteJson(StringBuilder buffer) {
        Reader reader = new Reader();
        reader.append(buffer);
        String json = reader.poll();
        if (json != null) {
            buffer.setLength(0);
            buffer.append(reader.remaining());
        }
        return json;
    }

    /**
     * 增量 JSON 对象读取器：{@link #append(CharSequence)} 追加片段，{@link #poll()} 取完整对象。
     *
     * <p>内部只维护扫描游标与括号/字符串状态，已扫描过的片段不会重复扫描，
     * 长输出（例如一次性生成整棵目录树）下保持 O(n)。</p>
     *
     * <p>唯一的回退点：位于缓冲区末尾、暂时无法判断是“字符串结束”还是“正文裸引号”的引号。
     * 新片段到达后会从该引号处复核，回退范围仅限当前未完成的字符串。</p>
     */
    public static final class Reader {

        /** 尚未取走的原始文本（取走对象后从头部删除） */
        private final StringBuilder buffer = new StringBuilder();

        /** 下一个待扫描字符下标 */
        private int pos;
        /** 当前对象的大括号深度 */
        private int depth;
        /** 当前对象的起始下标，未进入对象时为 -1 */
        private int start = -1;
        private boolean inString;
        private boolean escaped;
        private boolean inFieldName;

        /** 因“后面只有空白”而暂按字符串结束处理的引号；-1 表示没有 */
        private int uncertainQuote = -1;
        /** 处理该引号之前的状态，用于复核失败时回退 */
        private int snapshotPos;
        private int snapshotDepth;
        private int snapshotStart;
        private boolean snapshotInString;
        private boolean snapshotInFieldName;

        /** 追加一段流式文本。 */
        public void append(CharSequence delta) {
            if (delta != null) {
                buffer.append(delta);
            }
        }

        /** 缓冲区中尚未取走的字符数。 */
        public int bufferedLength() {
            return buffer.length();
        }

        /** 尚未取走的原始文本，主要用于兼容旧的 StringBuilder 调用方式。 */
        public String remaining() {
            return buffer.toString();
        }

        /**
         * 取走第一个完整闭合的 JSON 对象（已修复裸引号），没有则返回 {@code null}。
         */
        public String poll() {
            if (!resolveUncertainQuote()) {
                return null;
            }

            while (pos < buffer.length()) {
                char c = buffer.charAt(pos);

                if (inString) {
                    if (escaped) {
                        escaped = false;
                        pos++;
                    } else if (c == '\\') {
                        escaped = true;
                        pos++;
                    } else if (c == '"') {
                        int decision = stringEndDecision(buffer, pos + 1, inFieldName);
                        if (decision > 0) {
                            inString = false;
                            pos++;
                        } else if (decision < 0) {
                            // 后面只有空白，暂按结束处理；后续增量到达后再复核
                            snapshot(pos);
                            uncertainQuote = pos;
                            inString = false;
                            pos = buffer.length();
                        } else {
                            // 正文中的裸引号：等价于补上 \"，字符串继续
                            pos++;
                        }
                    } else {
                        pos++;
                    }
                    continue;
                }

                switch (c) {
                    case '"' -> {
                        inString = true;
                        inFieldName = startsFieldName(buffer, pos);
                        pos++;
                    }
                    case '{' -> {
                        if (depth == 0) {
                            start = pos;
                        }
                        depth++;
                        pos++;
                    }
                    case '}' -> {
                        if (depth > 0) {
                            depth--;
                            if (depth == 0 && start >= 0) {
                                String json = repairUnescapedQuotes(buffer.substring(start, pos + 1));
                                consume(pos + 1);
                                return json;
                            }
                        }
                        pos++;
                    }
                    default -> pos++;
                }
            }
            return null;
        }

        /**
         * 复核上一次因到达缓冲区末尾而暂判为“字符串结束”的引号。
         *
         * @return {@code false} 表示仍无法判定（后面依旧只有空白），本次不应继续扫描
         */
        private boolean resolveUncertainQuote() {
            if (uncertainQuote < 0) {
                return true;
            }
            int decision = stringEndDecision(buffer, uncertainQuote + 1, snapshotInFieldName);
            if (decision < 0) {
                return false;
            }
            if (decision == 0) {
                // 新内容说明它是正文里的裸引号：回退到处理它之前，按“转义”重新扫描
                pos = snapshotPos + 1;
                depth = snapshotDepth;
                start = snapshotStart;
                inString = snapshotInString;
                inFieldName = snapshotInFieldName;
                escaped = false;
                uncertainQuote = -1;
                return true;
            }
            // 确认是字符串结束：保持 inString=false，从新增内容继续扫描
            uncertainQuote = -1;
            return true;
        }

        private void snapshot(int quoteIndex) {
            snapshotPos = quoteIndex;
            snapshotDepth = depth;
            snapshotStart = start;
            snapshotInString = true;
            snapshotInFieldName = inFieldName;
        }

        private void consume(int end) {
            buffer.delete(0, end);
            pos = 0;
            depth = 0;
            start = -1;
            inString = false;
            escaped = false;
            inFieldName = false;
            uncertainQuote = -1;
        }
    }

    /**
     * 判断引号位置是否为字符串结束：
     * <ul>
     *   <li>{@code 1}：后续首个非空白字符是结构字符（{@code , } }} {@code ]}，字段名还接受 {@code :}）；</li>
     *   <li>{@code 0}：后续出现了普通字符，说明当前引号是正文里的裸引号；</li>
     *   <li>{@code -1}：后面只有空白（已到缓冲区末尾），暂时无法判定。</li>
     * </ul>
     */
    private static int stringEndDecision(CharSequence text, int from, boolean inFieldName) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return (c == ',' || c == '}' || c == ']' || (inFieldName && c == ':')) ? 1 : 0;
        }
        return -1;
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
