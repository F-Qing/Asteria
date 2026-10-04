package com.asteria.server.parser;

import com.asteria.common.Tool.QuestionParser;
import com.asteria.common.Tool.RawQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QuestionParser} 正则覆盖面的守护测试。
 *
 * <p>为什么值得单独守护：每多覆盖一种写法，这份文件就**不用走 AI 兜底**（省钱、还不丢题）。
 * 所以测试分两半——"该认出来的必须认出来"和"不该误拆的必须纹丝不动"。
 * 后者同样重要：正则放宽后最怕把正文错当选项切碎，那会造成比不解析更糟的脏数据。
 */
class QuestionParserTest {

    private final QuestionParser parser = new QuestionParser();

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

    @Nested
    @DisplayName("题号变体：这些写法以前会掉进 AI 兜底")
    class HeaderVariants {

        @Test
        void 数字加右括号() {
            assertEquals("题干内容", only("1) 题干内容").getRawStem());
            assertEquals("题干内容", only("1）题干内容").getRawStem());
        }

        @Test
        void 裸写第N题() {
            assertEquals("题干内容", only("第1题 题干内容").getRawStem());
            assertEquals("题干内容", only("第12题：题干内容").getRawStem());
        }

        @Test
        void 方括号数字() {
            assertEquals("题干内容", only("【1】题干内容").getRawStem());
            assertEquals("题干内容", only("[1] 题干内容").getRawStem());
        }

        @Test
        void 方括号第N题() {
            // 旧版只在「题号行」认它，题干抓不出来 → 这行会落进可疑行
            assertEquals("题干内容", only("【第1题】题干内容").getRawStem());
        }

        @Test
        void markdown前缀() {
            assertEquals("题干内容", only("**1.** 题干内容").getRawStem());
            assertEquals("题干内容", only("## 1. 题干内容").getRawStem());
            assertEquals("题干内容", only("- 1. 题干内容").getRawStem());
        }
    }

    @Nested
    @DisplayName("选项变体：括号包裹 / 冒号分隔")
    class OptionVariants {

        @Test
        void 字母被括号包住() {
            RawQuestion q = only("1. 题干\n（A）甲\n（B）乙");
            assertEquals("A=甲,B=乙", optionsOf(q));
        }

        @Test
        void 中文方括号包住字母() {
            RawQuestion q = only("1. 题干\n【A】甲\n【B】乙");
            assertEquals("A=甲,B=乙", optionsOf(q));
        }

        @Test
        void 冒号分隔() {
            RawQuestion q = only("1. 题干\nA：甲\nB：乙");
            assertEquals("A=甲,B=乙", optionsOf(q));
        }

        @Test
        void 方括号加冒号组合() {
            RawQuestion q = only("1. 题干\n[A] 甲\n[B] 乙");
            assertEquals("A=甲,B=乙", optionsOf(q));
        }
    }

    @Nested
    @DisplayName("行内选项：题干和选项挤在同一行")
    class InlineOptions {

        @Test
        void 题号行里混排选项() {
            RawQuestion q = only("1. 下列哪个是语言？ A. 中文 B. 英文 C. 法文 D. 德文");
            assertEquals("下列哪个是语言？", q.getRawStem());
            assertEquals("A=中文,B=英文,C=法文,D=德文", optionsOf(q));
        }

        @Test
        void 单独一行的内联选项() {
            RawQuestion q = only("1. 题干\nA. 甲 B. 乙 C. 丙");
            assertEquals("A=甲,B=乙,C=丙", optionsOf(q));
        }

        @Test
        void 末选项粘着答案() {
            RawQuestion q = only("1. 题干 A. 甲 B. 乙 答案：B");
            assertEquals("A=甲,B=乙", optionsOf(q));
            assertEquals("B", q.getRawAnswer());
        }
    }

    @Nested
    @DisplayName("答案变体")
    class AnswerVariants {

        @Test
        void 方括号答案() {
            assertEquals("B", only("1. 题干\nA. 甲\nB. 乙\n【答案】B").getRawAnswer());
        }

        @Test
        void 方括号正确答案() {
            assertEquals("B", only("1. 题干\nA. 甲\nB. 乙\n【正确答案】B").getRawAnswer());
        }

        @Test
        void 答字标签() {
            assertEquals("A", only("1. 题干\n答：A").getRawAnswer());
        }

        @Test
        void 判断题裸答案() {
            RawQuestion q = only("1. 判断下列说法是否正确\nA. 正确\nB. 错误\n对");
            assertEquals("对", q.getRawAnswer());
        }

        @Test
        void 我的答案不能当标准答案() {
            RawQuestion q = only("1. 题干\nA. 甲\nB. 乙\n我的答案：A\n正确答案：B");
            assertEquals("B", q.getRawAnswer());
        }
    }

    @Nested
    @DisplayName("不误伤：宁可少拆，不可错拆")
    class NoFalsePositive {

        @Test
        void 只有一个字母标记不拆() {
            RawQuestion q = only("1. 关于 e.g. 的用法 A. 说明");
            assertTrue(q.getRawOptions().isEmpty(), "单个标记不该被切成选项，实际 " + optionsOf(q));
        }

        @Test
        void 字母标记不连续不拆() {
            RawQuestion q = only("1. 说明 A. 甲 C. 丙");
            assertTrue(q.getRawOptions().isEmpty(), "A、C 不连续，不该拆，实际 " + optionsOf(q));
        }

        @Test
        void 字母前面不是空白不拆() {
            // "答A." 这种字母紧贴汉字，不是选项标记
            RawQuestion q = only("1. 小明答A. 小红答B.");
            assertTrue(q.getRawOptions().isEmpty(), "词内字母不该拆，实际 " + optionsOf(q));
        }

        @Test
        void 代码行不拆() {
            RawQuestion q = only("1. 读下列代码\nint a = 1; // A. 注释 B. 注释");
            assertTrue(q.getRawOptions().isEmpty(), "代码行不该拆选项，实际 " + optionsOf(q));
        }

        @Test
        void 孤零零的分隔符行不算题号() {
            assertTrue(parse("3.").isEmpty(), "只有 '3.' 没内容，不该当成题");
        }

        @Test
        void 题型小节行不是题目() {
            assertTrue(parse("1．选择题").isEmpty(), "题型小节行不该被当成题");
        }
    }

    @Nested
    @DisplayName("Markdown 加粗残留：前缀剥掉，但不误伤单个 * 和 _")
    class MarkdownResidue {

        @Test
        void 加粗选项行() {
            RawQuestion q = only("1. 题干\n**A.** 甲\n**B.** 乙");
            assertEquals("A=甲,B=乙", optionsOf(q));
        }

        @Test
        void 加粗答案() {
            RawQuestion q = only("1. 题干\nA. 甲\nB. 乙\n**【答案】B**");
            assertEquals("B", q.getRawAnswer());
        }

        @Test
        void 加粗题干() {
            assertEquals("题干内容", only("【第1题】\n题目：**题干内容**").getRawStem());
        }

        @Test
        void 单个星号是内容不是加粗() {
            RawQuestion q = only("1. 题干\nA. *args\nB. __init__");
            assertEquals("A=*args,B=__init__", optionsOf(q));
        }
    }

    @Nested
    @DisplayName("回归：原有标准格式不能坏")
    class Regression {

        @Test
        void 标准格式全流程() {
            String text = """
                    【第1章】绪论
                    【第1题】
                    题目：计算机网络的定义是什么？
                    选项：
                    A. 一种协议
                    B. 一种技术
                    【答案】B
                    """;
            RawQuestion q = only(text);
            assertEquals("绪论", q.getChapterName());
            assertEquals("计算机网络的定义是什么？", q.getRawStem());
            assertEquals("A=一种协议,B=一种技术", optionsOf(q));
            assertEquals("B", q.getRawAnswer());
        }

        @Test
        void 数字顿号格式() {
            RawQuestion q = only("1、下列不属于……\nA. 甲\nB. 乙\n正确答案：A");
            assertEquals("下列不属于……", q.getRawStem());
            assertEquals("A=甲,B=乙", optionsOf(q));
            assertEquals("A", q.getRawAnswer());
        }

        @Test
        void 多道题按题号切分() {
            String text = """
                    1. 第一题
                    A. 甲
                    B. 乙
                    2. 第二题
                    A. 丙
                    B. 丁
                    """;
            List<RawQuestion> list = parse(text);
            assertEquals(2, list.size());
            assertEquals("第一题", list.get(0).getRawStem());
            assertEquals("第二题", list.get(1).getRawStem());
        }
    }
}
