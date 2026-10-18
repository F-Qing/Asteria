package com.asteria.server.parser;

import com.asteria.common.Tool.QuestionParser;
import com.asteria.common.Tool.RawQuestion;
import com.asteria.pojo.enums.QuestionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边界写法的守护测试（第 2 批）。
 *
 * <p>守四条以前"认不出来"或"认错了"的写法：
 * <ol>
 *   <li>带圈数字题号 {@code ①下列哪项正确}（旧版 0 道题）；</li>
 *   <li>括号字母内联 {@code 题干 (A) 甲 (B) 乙}（旧版整行当题干，选项全丢）；</li>
 *   <li>单行答案 {@code 1.【答案】A}（旧版变成一道幽灵题）；</li>
 *   <li>答案带解析 {@code B。因为 A 不对}（旧版会把解析里的字母也算成答案）。</li>
 * </ol>
 *
 * <p>第 ② 条配套一个<b>负例</b>：正文里的「比较（A）和（B）两种方案」不能拆 ——
 * 放宽正则最容易的翻车方式就是把正文切碎，那比不解析更糟。
 */
class QuestionParserEdgeCaseTest {

    private final QuestionParser parser = new QuestionParser();
    private final QuestionClassifier classifier = new QuestionClassifier();

    private List<RawQuestion> parse(String text) {
        return parser.parse(text);
    }

    private RawQuestion only(String text) {
        List<RawQuestion> list = parse(text);
        assertEquals(1, list.size(), "应当只解析出 1 道题，实际 " + list.size() + " 道");
        return list.get(0);
    }

    private static String optionsOf(RawQuestion q) {
        return q.getRawOptions().stream()
                .map(o -> o.getKey() + "=" + o.getText())
                .collect(Collectors.joining(","));
    }

    @Test
    @DisplayName("带圈数字题号：①下列哪项正确")
    void 带圈数字题号() {
        RawQuestion q = only("①下列哪项正确\nA. 甲\nB. 乙");
        assertEquals("下列哪项正确", q.getRawStem());
        assertEquals("A=甲,B=乙", optionsOf(q));
    }

    @Test
    @DisplayName("括号字母内联：题干 (A) 甲 (B) 乙")
    void 括号字母内联() {
        RawQuestion q = only("1. 下列哪个是语言？ (A) 甲 (B) 乙 (C) 丙");
        assertEquals("下列哪个是语言？", q.getRawStem());
        assertEquals("A=甲,B=乙,C=丙", optionsOf(q));
    }

    @Test
    @DisplayName("负例：正文里的（A）（B）不能被拆成选项")
    void 正文里的括号不误拆() {
        // 括号选项要求「右括号后跟空白」，这条正文没有，所以整行原样当题干
        RawQuestion q = only("1. 比较（A）和（B）两种方案");
        assertTrue(q.getRawOptions().isEmpty(), "正文不该被拆，实际拆出：" + optionsOf(q));
        assertEquals("比较（A）和（B）两种方案", q.getRawStem());
    }

    @Test
    @DisplayName("单行答案（只有 1 行）不再变成幽灵题")
    void 单行答案不再产生幽灵题() {
        List<RawQuestion> list = parse("""
                1. 下列哪个是语言？
                A. 甲
                B. 乙
                1.【答案】A
                """);
        assertEquals(1, list.size(), "不该多出「题干=【答案】A」的幽灵题");
        assertEquals("下列哪个是语言？", list.get(0).getRawStem());
        assertEquals("A", list.get(0).getRawAnswer());
        assertEquals("A=甲,B=乙", optionsOf(list.get(0)));
    }

    @Test
    @DisplayName("答案带解析：原文保留，但判型只数开头那段字母")
    void 答案带解析不误判多选() {
        RawQuestion q = only("1. 题干\nA. 甲\nB. 乙\n正确答案：B。因为 A 不对，C 也不对");

        assertEquals("B。因为 A 不对，C 也不对", q.getRawAnswer(), "原文答案要保持原样，不截断");
        assertEquals(QuestionType.SINGLE, classifier.classify(q),
                "解析里的 A/C 不能被当成多选证据");
    }

    @Test
    @DisplayName("AnswerTexts 只取开头那段选项字母")
    void 答案字母前缀抽取() {
        assertEquals("D", AnswerTexts.letters("D；超文本标记语言;"), "学习通导出格式");
        assertEquals("B", AnswerTexts.letters("B。因为 A 不对"));
        assertEquals("AC", AnswerTexts.letters("A、C"), "顿号分隔的多选");
        assertEquals("AC", AnswerTexts.letters("A和C都对"), "和字连接的多选");
        assertEquals("", AnswerTexts.letters("三次握手需要三次报文"), "简答题答案里没有选项字母");
    }
}
