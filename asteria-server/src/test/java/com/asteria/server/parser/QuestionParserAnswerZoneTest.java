package com.asteria.server.parser;

import com.asteria.common.Tool.QuestionParser;
import com.asteria.common.Tool.RawQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「答案集中区」的守护测试。
 *
 * <p>守的是一个**优先级**缺陷，而不是覆盖面缺陷：
 * 文件把答案集中在末尾时，{@code 1.【答案】A} 会命中题号行正则（题号判断排在答案判断之前），
 * 每一行答案都变成一道以「【答案】A」为题干的新题 —— 真正的题目全部丢答案，还平白多出 N 道废题。
 *
 * <p>最阴的地方是**题数看起来还对得上、日志一片正常**，所以这组用例重点断言两件事：
 * ① 不再产生「幽灵题」；② 答案真的回填到了对应的题上。
 */
class QuestionParserAnswerZoneTest {

    private final QuestionParser parser = new QuestionParser();

    private List<RawQuestion> parse(String text) {
        return parser.parse(text);
    }

    private static String optionsOf(RawQuestion q) {
        return q.getRawOptions().stream()
                .map(o -> o.getKey() + "=" + o.getText())
                .collect(Collectors.joining(","));
    }

    /** 题干是「【答案】xxx」的，就是幽灵题 */
    private static List<RawQuestion> ghosts(List<RawQuestion> list) {
        return list.stream()
                .filter(q -> q.getRawStem() != null && q.getRawStem().contains("【答案】"))
                .toList();
    }

    @Test
    @DisplayName("答案集中在末尾：不再切成幽灵题，答案按题号回填")
    void 答案集中区不再产生幽灵题() {
        String text = """
                1. 下列哪个是语言？
                A. 甲
                B. 乙
                2. 下列哪个是标记？
                A. 丙
                B. 丁
                1.【答案】A
                2.【答案】B
                """;
        List<RawQuestion> list = parse(text);

        assertEquals(2, list.size(), "应当只有 2 道题，多出来的就是幽灵题");
        assertTrue(ghosts(list).isEmpty(), "不该出现题干含【答案】的幽灵题");

        assertEquals("下列哪个是语言？", list.get(0).getRawStem());
        assertEquals("A=甲,B=乙", optionsOf(list.get(0)), "最后一道真题的选项不能被答案区截断");
        assertEquals("A", list.get(0).getRawAnswer());

        assertEquals("下列哪个是标记？", list.get(1).getRawStem());
        assertEquals("A=丙,B=丁", optionsOf(list.get(1)));
        assertEquals("B", list.get(1).getRawAnswer());
    }

    @Test
    @DisplayName("答案区中间夹空行、分隔线、参考答案标题：仍算一片")
    void 答案区允许夹空行与分隔线() {
        String text = """
                1. 第一题
                A. 甲
                B. 乙
                2. 第二题
                A. 丙
                B. 丁

                ------
                1.【答案】A

                2.【答案】D
                """;
        List<RawQuestion> list = parse(text);

        assertEquals(2, list.size());
        assertEquals("A", list.get(0).getRawAnswer());
        assertEquals("D", list.get(1).getRawAnswer());
    }

    @Test
    @DisplayName("答案区只给了部分题号：按题号回填，缺的保持为空")
    void 只给部分答案时按题号回填() {
        String text = """
                1. 第一题
                A. 甲
                B. 乙
                2. 第二题
                A. 丙
                B. 丁
                3. 第三题
                A. 戊
                B. 己
                1.【答案】A
                3.【答案】B
                """;
        List<RawQuestion> list = parse(text);

        assertEquals(3, list.size());
        assertEquals("A", list.get(0).getRawAnswer());
        assertNull(list.get(1).getRawAnswer(), "答案区没给第 2 题，不能张冠李戴");
        assertEquals("B", list.get(2).getRawAnswer());
    }

    @Test
    @DisplayName("题干自带的答案优先，冲突只记可疑、不覆盖")
    void 题内答案优先于答案区() {
        String text = """
                1. 第一题
                A. 甲
                B. 乙
                正确答案：B
                2. 第二题
                A. 丙
                B. 丁
                正确答案：A
                1.【答案】C
                2.【答案】D
                """;
        List<RawQuestion> list = parse(text);

        assertEquals(2, list.size());
        assertEquals("B", list.get(0).getRawAnswer(), "题内答案更可信，不能被答案区覆盖");
        assertEquals("A", list.get(1).getRawAnswer());
        assertTrue(list.get(0).getSuspicious().stream().anyMatch(s -> s.contains("不一致")),
                "两份答案不一致必须留痕，实际：" + list.get(0).getSuspicious());
    }

    @Test
    @DisplayName("普通题库（答案就地写）：行为完全不变")
    void 普通题库不受影响() {
        String text = """
                【第1章】绪论
                【第1题】
                题目：计算机网络的定义是什么？
                选项：
                A. 一种协议
                B. 一种技术
                【答案】B
                """;
        List<RawQuestion> list = parse(text);

        assertEquals(1, list.size());
        assertEquals("B", list.get(0).getRawAnswer());
        assertEquals("绪论", list.get(0).getChapterName());
        assertNotNull(list.get(0).getRawStem());
    }
}
