package com.asteria.server.ai;

import com.asteria.pojo.entity.Question;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 补解析的单测。
 *
 * <p>不碰网络：这里只测"程序自己的判断"—— 哪些情况该补答案、哪些情况绝不能动原答案、
 * 以及结果里有没有把"这个答案是 AI 写的"标出来。
 *
 * <p>为什么盯住"数据血缘"：AI 补的答案会直接进库给用户看，如果分不清哪条是 AI 写的，
 * 将来想人工复核或回滚就无从下手。这个标记丢了，等于把可追溯性丢了。
 */
class QuestionAiEnricherTest {

    private final QuestionAiEnricher enricher = new QuestionAiEnricher();

    /** 造一道题：answer 传 null 表示"文件里没给答案" */
    private static Question question(String type, String answer) {
        Question q = new Question();
        q.setId(1L);
        q.setType(type);
        q.setStem("示例题干");
        q.setAnswer(answer);
        return q;
    }

    @Nested
    @DisplayName("EnrichResult：把『答案是谁写的』表达清楚")
    class ResultContract {

        @Test
        @DisplayName("只补解析 → answer 为 null、answerFromAi=false（调用方据此不动 answer）")
        void should_markAnalysisOnly() {
            QuestionAiEnricher.EnrichResult r = QuestionAiEnricher.EnrichResult.analysisOnly("这是解析");

            assertEquals("这是解析", r.analysis());
            assertNull(r.answer(), "没补答案时 answer 必须是 null —— 调用方靠它决定动不动原答案");
            assertFalse(r.answerFromAi(), "没补答案就不该标成 AI 来源");
        }

        @Test
        @DisplayName("连答案一起补 → answerFromAi=true（调用方据此标 answer_source=AI）")
        void should_markAnswerFromAi() {
            QuestionAiEnricher.EnrichResult r = QuestionAiEnricher.EnrichResult.withAnswer("这是解析", "A");

            assertEquals("这是解析", r.analysis());
            assertEquals("A", r.answer());
            assertTrue(r.answerFromAi(), "模型补的答案必须被标成 AI 来源，这是数据血缘的落点");
        }
    }

    @Nested
    @DisplayName("提示词：题目原本有答案时不许改")
    class PromptCarriesTheRule {

        @Test
        @DisplayName("题目已有答案 → 提示词里带上原答案，并声明以它为准")
        void should_tellModelToRespectExistingAnswer() {
            String prompt = buildUserPrompt(question("SINGLE", "D"), false);

            assertTrue(prompt.contains("D"), "应把原答案带进提示词，实际：" + prompt);
            assertTrue(prompt.contains("题型：SINGLE"), "实际：" + prompt);
            assertTrue(prompt.contains("题干：示例题干"), "实际：" + prompt);
        }

        @Test
        @DisplayName("题目缺答案 → 提示词里明确要求模型解出来（而不是留空）")
        void should_askModelToSolve_whenAnswerMissing() {
            String prompt = buildUserPrompt(question("SINGLE", ""), true);

            assertTrue(prompt.contains("没有提供") || prompt.contains("解出来"),
                    "缺答案时要明确告诉模型自己解，实际：" + prompt);
        }

        @Test
        @DisplayName("不同题型给不同的答案格式要求（单选/多选/判断的写法不一样）")
        void should_givePerTypeAnswerFormat() {
            assertTrue(buildUserPrompt(question("SINGLE", "A"), false).contains("单个大写字母"));
            assertTrue(buildUserPrompt(question("MULTIPLE", "A,B"), false).contains("英文逗号"));
            assertTrue(buildUserPrompt(question("TRUE_FALSE", "A"), false).contains("A（正确）"));
            assertTrue(buildUserPrompt(question("FILL_BLANK", "甲"), false).contains("分号"));
        }

        /** 私有方法，用反射调；这个测试的目的就是盯住提示词内容 */
        private String buildUserPrompt(Question q, boolean answerMissing) {
            return ReflectionTestUtils.invokeMethod(enricher, "buildUserPrompt", q, answerMissing);
        }
    }

    @Nested
    @DisplayName("答案格式校验：宁可不写，也不写错的进去")
    class AnswerNormalization {

        @Test
        @DisplayName("单选：只认单个字母，别的都丢掉")
        void should_acceptOnlySingleLetter_forSingle() {
            assertEquals("A", normalize("A", "SINGLE"));
            assertEquals("B", normalize("b", "SINGLE"), "小写要能收敛成大写");
            assertEquals("A", normalize(" A ", "SINGLE"), "两边空格要能容忍");
            assertNull(normalize("AB", "SINGLE"), "单选给了两个字母显然是错的，丢掉");
            assertNull(normalize("答案是A", "SINGLE"), "夹带文字也丢掉");
            assertNull(normalize("", "SINGLE"));
        }

        @Test
        @DisplayName("多选：去重 + 升序，和文件里手写的风格保持一致")
        void should_dedupeAndSort_forMultiple() {
            assertEquals("A,C", normalize("A,C", "MULTIPLE"));
            assertEquals("A,C", normalize("C,A", "MULTIPLE"), "乱序要排好");
            assertEquals("A,B,C", normalize("A,A,B,C", "MULTIPLE"), "重复要去掉");
            assertNull(normalize("AC", "MULTIPLE"), "缺逗号分隔符也丢掉（格式不符）");
            assertNull(normalize("答案是A,C", "MULTIPLE"), "夹带文字丢掉");
            // 单个字母是允许的：题型已经由文件确定为多选，模型只补了个答案，
            // 不能因为"只有一个字母"就把它丢掉 —— 那会让这道题永远没有答案
            assertEquals("A", normalize("A", "MULTIPLE"));
        }

        @Test
        @DisplayName("判断题：各种写法都收敛到 A/B（这是修过的老 bug，别退回去）")
        void should_normalizeTrueFalse_toAB() {
            assertEquals("A", normalize("对", "TRUE_FALSE"));
            assertEquals("A", normalize("正确", "TRUE_FALSE"));
            assertEquals("A", normalize("TRUE", "TRUE_FALSE"));
            assertEquals("B", normalize("错", "TRUE_FALSE"));
            assertEquals("B", normalize("FALSE", "TRUE_FALSE"));
            assertEquals("B", normalize("×", "TRUE_FALSE"));
            assertNull(normalize("也许吧", "TRUE_FALSE"), "认不出的就丢掉");
        }

        @Test
        @DisplayName("填空/简答：原样保留，不做字母卡控（文本答案才是正确形态）")
        void should_keepText_forEssayAndBlank() {
            assertEquals("参考答案文本", normalize("参考答案文本", "ESSAY"));
            assertEquals("春；春天", normalize("春；春天", "FILL_BLANK"),
                    "填空的多空/同义答案不能被拆开或改动");
        }

        private String normalize(String raw, String type) {
            return ReflectionTestUtils.invokeMethod(enricher, "normalizeAiAnswer", raw, type);
        }
    }

    @Nested
    @DisplayName("端到端：走完整的 enrich()，模型用替身")
    class EndToEnd {

        /**
         * 造一个"假模型"：调它永远返回指定的文本。
         *
         * <p>注意 stub 的顺序 —— Mockito 不许在 {@code when(...)} 的参数里再调 {@code mock()}，
         * 那会被当成"上一个 stub 没写完"直接抛 UnfinishedStubbingException。
         * 所以先把所有替身造好，再设置行为。
         */
        private OpenAiChatModel fakeModelReturning(String text) {
            ChatResponse response = Mockito.mock(ChatResponse.class, Mockito.RETURNS_DEEP_STUBS);
            Mockito.when(response.getResult().getOutput().getText()).thenReturn(text);

            OpenAiChatModel model = Mockito.mock(OpenAiChatModel.class);
            Mockito.when(model.call(Mockito.any(Prompt.class))).thenReturn(response);
            return model;
        }

        private QuestionAiEnricher enricherUsing(OpenAiChatModel model) {
            AiChatModelFactory factory = Mockito.mock(AiChatModelFactory.class);
            Mockito.when(factory.create(Mockito.any(AiRequestConfig.class), Mockito.any()))
                    .thenReturn(model);

            QuestionAiEnricher e = new QuestionAiEnricher();
            ReflectionTestUtils.setField(e, "chatModelFactory", factory);
            return e;
        }

        private final AiRequestConfig config =
                new AiRequestConfig("deepseek", "sk-test", "https://api.deepseek.com", "deepseek-flash");

        @Test
        @DisplayName("题目原本缺答案 → 模型补出答案，标记 answerFromAi=true 且规范化成 A")
        void should_fillAnswer_whenMissing() {
            QuestionAiEnricher e = enricherUsing(
                    fakeModelReturning("{\"answer\":\"a\",\"analysis\":\"因为选 A\"}"));

            QuestionAiEnricher.EnrichResult r = e.enrich(question("SINGLE", ""), config);

            assertEquals("因为选 A", r.analysis());
            assertEquals("A", r.answer(), "小写 a 要收敛成大写 A");
            assertTrue(r.answerFromAi(), "补出来的答案必须标成 AI 来源");
        }

        @Test
        @DisplayName("题目原本有答案 → 只写解析，绝不覆盖原答案（answer=null, answerFromAi=false）")
        void should_notTouchExistingAnswer() {
            QuestionAiEnricher e = enricherUsing(
                    fakeModelReturning("{\"answer\":\"B\",\"analysis\":\"解析内容\"}"));

            QuestionAiEnricher.EnrichResult r = e.enrich(question("SINGLE", "D"), config);

            assertEquals("解析内容", r.analysis());
            assertNull(r.answer(), "原答案必须保持不动 —— null 就是'别动它'的信号");
            assertFalse(r.answerFromAi(), "没改成 AI 的，来源就不能标 AI");
        }

        @Test
        @DisplayName("模型给的答案格式不合法 → 只保留解析，不写坏答案进去")
        void should_dropInvalidAnswer_butKeepAnalysis() {
            QuestionAiEnricher e = enricherUsing(
                    fakeModelReturning("{\"answer\":\"答案是A吧\",\"analysis\":\"解析内容\"}"));

            QuestionAiEnricher.EnrichResult r = e.enrich(question("SINGLE", ""), config);

            assertEquals("解析内容", r.analysis(), "解析还是要留下的");
            assertNull(r.answer(), "答案格式不对就丢掉 —— 宁可没答案，也不要错答案");
            assertFalse(r.answerFromAi());
        }

        @Test
        @DisplayName("解析里有 LaTeX 公式 → 反斜杠修复生效，公式不被改坏")
        void should_repairLatexInAnalysis() {
            QuestionAiEnricher e = enricherUsing(
                    fakeModelReturning("{\"answer\":\"A\",\"analysis\":\"用 \\\\frac{1}{2} 计算\"}"));

            QuestionAiEnricher.EnrichResult r = e.enrich(question("SINGLE", ""), config);

            assertEquals("用 \\frac{1}{2} 计算", r.analysis(), "公式要原样保留，不能被解析成换页符");
        }

        @Test
        @DisplayName("模型没给解析内容 → 抛异常（由调用方记成 FAILED，不能静默通过）")
        void should_throw_whenAnalysisBlank() {
            QuestionAiEnricher e = enricherUsing(
                    fakeModelReturning("{\"answer\":\"A\",\"analysis\":\"\"}"));

            Question q = question("SINGLE", "");
            assertThrows(IllegalStateException.class, () -> e.enrich(q, config));
        }
    }

    @Nested
    @DisplayName("回归：解析入口必须先修反斜杠")
    class RegressionGuard {

        @Test
        @DisplayName("enrich() 的源码里必须调用 AiJsonRepair（防止有人把修复删了）")
        void should_stillRepairBackslashes() throws Exception {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/asteria/server/ai/QuestionAiEnricher.java"));
            assertNotNull(source);
            assertTrue(source.contains("AiJsonRepair.repairBackslashes"),
                    "解析里的公式（\\frac 之类）依赖这一步，删了会让 JSON 解析失败或题干被改坏");
        }
    }
}
