package com.asteria.server.parser;

import java.util.regex.Pattern;

/**
 * 选项字母处理的小工具（包级私有，只有 {@code com.asteria.server.parser} 内部能用）。
 *
 * <p>抽成单独的类是为了避免 QuestionClassifier 和 AnswerNormalizer
 * 各写一份正则——同一个规则只留一处，改的时候不会漏。
 *
 * <p>⚠️ 这里只管「字母」。判断题的对错词语义和 A/B 约定在
 * {@link com.asteria.pojo.enums.TrueFalseAnswer} —— 那边还要给 AI 补答案和前端用，
 * 不能关在这个包里。
 */
final class AnswerTexts {

    /** 答案里的字母（半角、全角都认） */
    private static final Pattern LETTER = Pattern.compile("[A-Za-zＡ-Ｚａ-ｚ]");

    /** 选项字母之间的分隔符：A、C / A,C / A C / A和C 都算一组多选 */
    private static final String LETTER_SEPARATORS = "、,，;；/ 　和与及";

    private AnswerTexts() {
    }

    /**
     * 取答案<b>开头那一段选项字母</b>（允许中间夹分隔符），遇到别的内容立刻停。
     *
     * <p>为什么不能"把全文的字母都捞出来"：学习通/教材的答案是「答案 + 解析」连在一起，
     * 如 {@code B。因为 A 不对，C 才对}、{@code D；超文本标记语言;（HTML）}。
     * 全文捞字母会得到 {@code ABC} —— 单选被误判成多选，而且不报错、不告警。
     *
     * <p>三个例子：
     * <ul>
     *   <li>{@code D；超文本标记语言;} → {@code D}（分号后不是字母，停）</li>
     *   <li>{@code B。因为 A 不对} → {@code B}（句号处停）</li>
     *   <li>{@code A、C} / {@code AC} → {@code A、C} / {@code AC}（分隔符放行）</li>
     * </ul>
     *
     * <p>开头如果没有字母（如简答题答案「三次握手…」）就返回空串，交给调用方按"没有字母"处理。
     */
    static String letterPart(String rawAnswer) {
        if (rawAnswer == null) {
            return "";
        }
        String text = rawAnswer.trim();

        // ① 找到第一个字母（答案前面可能有「选」「答」之类的字）
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            if (isLetter(text.charAt(i))) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return "";
        }

        // ② 从第一个字母往后走：字母和分隔符放行，其余立刻停。
        //    只把"最后一个字母"记作结尾，所以 A、C 后面那一串分隔符不会被算进去。
        int end = start;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isLetter(c)) {
                end = i;
            } else if (!isSeparator(c)) {
                break;
            }
        }
        return text.substring(start, end + 1);
    }

    private static boolean isLetter(char c) {
        return LETTER.matcher(String.valueOf(c)).matches();
    }

    private static boolean isSeparator(char c) {
        return LETTER_SEPARATORS.indexOf(c) >= 0;
    }

    /**
     * 抽出答案里的所有选项字母：去重 + 排序 + 拼接。
     * <p>"A、C" / "A,C" / "A C" / "AC" / "c、a" → 都是 "AC"
     */
    static String letters(String rawAnswer) {
        String part = letterPart(rawAnswer);
        if (part.isEmpty()) {
            return "";
        }
        return LETTER.matcher(part)
                .results()
                .map(m -> normalizeLetter(m.group()))
                .distinct()
                .sorted()
                .reduce("", String::concat);
    }

    /** 全角字母转半角并统一大写：Ａ→A、a→A */
    static String normalizeLetter(String letter) {
        char c = letter.charAt(0);
        if (c >= 'Ａ' && c <= 'Ｚ') {
            c = (char) (c - 'Ａ' + 'A');
        } else if (c >= 'ａ' && c <= 'ｚ') {
            c = (char) (c - 'ａ' + 'a');
        }
        return String.valueOf(Character.toUpperCase(c));
    }
}
