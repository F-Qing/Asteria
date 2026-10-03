package com.asteria.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 反斜杠修复的守护测试。
 *
 * <p>背景：数学/物理题的题干里有 {@code \frac} 这类 LaTeX，模型把它原样写进 JSON 后，
 * 字符串会以两种方式坏掉，而这两种坏法都很难发现：
 *
 * <ul>
 *   <li><b>静默损坏</b>：{@code \f} 在 JSON 里是合法转义（换页符 U+000C）→
 *       Jackson 把 {@code \frac} 解析成「换页符 + rac」，题干被悄悄改坏。
 *       {@code \beta}（反斜杠 b 是退格符）、{@code \vdots}（反斜杠 v 是垂直制表符）同理。</li>
 *   <li><b>直接抛异常</b>：{@code \alpha}、{@code \cdot} 不是合法转义 → JsonParseException
 *       → 整块抽取失败 → 整批导入失败。</li>
 * </ul>
 */
class AiJsonRepairTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 换页符，就是 {@code \f} 被 Jackson 解析后的样子 */
    private static final char FF = '\f';
    /** 退格符，就是 {@code \b} 被 Jackson 解析后的样子 */
    private static final char BS = '\b';
    /** 垂直制表符，就是 {@code \v} 被 Jackson 解析后的样子 */
    private static final char VT = '\u000B';

    @Nested
    @DisplayName("修复规则：只补『非法转义』和『假控制转义』的反斜杠")
    class RepairRules {

        @Test
        @DisplayName("正常转义一个都不动（\\n \\t \\r \\\" \\\\ \\/）")
        void should_keepValidEscapes() {
            String json = "{\"a\":\"line1\\nline2\\ttab\\rret\\\"quote\\\\slash\\/solidus\"}";

            assertEquals(json, AiJsonRepair.repairBackslashes(json));
        }

        @Test
        @DisplayName("真的控制字符后面不接字母时，原样保留")
        void should_keepRealControlChars() {
            // 真换页符：后面是文字不是字母，不能当成 \f 命令
            String withRealFF = "{\"a\":\"第一页" + FF + "第二页\"}";
            assertEquals(withRealFF, AiJsonRepair.repairBackslashes(withRealFF));

            String withRealBS = "{\"a\":\"删掉" + BS + "一个字\"}";
            assertEquals(withRealBS, AiJsonRepair.repairBackslashes(withRealBS));

            String withRealVT = "{\"a\":\"上" + VT + "下\"}";
            assertEquals(withRealVT, AiJsonRepair.repairBackslashes(withRealVT));
        }

        @Test
        @DisplayName("Unicode 转义保留")
        void should_keepUnicodeEscape() {
            String json = "{\"a\":\"\\u4e2d\\u6587\"}";

            assertEquals(json, AiJsonRepair.repairBackslashes(json));
        }

        @Test
        @DisplayName("u 后面不是 4 位十六进制 → 当成字面量补成双反斜杠")
        void should_repairBrokenUnicodeEscape() {
            assertEquals("{\"a\":\"\\\\uZZZZ\"}", AiJsonRepair.repairBackslashes("{\"a\":\"\\uZZZZ\"}"));
        }

        @Test
        @DisplayName("非法转义的 LaTeX 命令：单选一个反斜杠")
        void should_repairInvalidEscapeCommands() {
            assertEquals("\\\\alpha", AiJsonRepair.repairBackslashes("\\alpha"));
            assertEquals("\\\\cdot", AiJsonRepair.repairBackslashes("\\cdot"));
            assertEquals("\\\\sqrt", AiJsonRepair.repairBackslashes("\\sqrt"));
        }

        @Test
        @DisplayName("假控制转义的 LaTeX 命令：\\frac / \\beta / \\vdots 也要补")
        void should_repairControlLikeCommands() {
            assertEquals("\\\\frac", AiJsonRepair.repairBackslashes("\\frac"));
            assertEquals("\\\\beta", AiJsonRepair.repairBackslashes("\\beta"));
            assertEquals("\\\\vdots", AiJsonRepair.repairBackslashes("\\vdots"));
        }

        @Test
        @DisplayName("\\begin{cases}：只补第一处，第二处 \\c 本来就不是合法转义")
        void should_repairCommandWithBraces() {
            assertEquals("\\\\begin{cases}", AiJsonRepair.repairBackslashes("\\begin{cases}"));
        }

        @Test
        @DisplayName("末尾孤立的反斜杠补成双反斜杠")
        void should_repairTrailingBackslash() {
            assertEquals("abc\\\\", AiJsonRepair.repairBackslashes("abc\\"));
        }

        @Test
        @DisplayName("幂等：已经合法的 JSON 进出一致")
        void should_beIdempotent() {
            String alreadyValid = "{\"stem\":\"求 \\\\frac{1}{2} 的值\",\"answer\":\"A\"}";

            assertEquals(alreadyValid, AiJsonRepair.repairBackslashes(alreadyValid));
            assertEquals(alreadyValid,
                    AiJsonRepair.repairBackslashes(AiJsonRepair.repairBackslashes(alreadyValid)));
        }

        @Test
        @DisplayName("没有反斜杠时原样返回（走快速路径，不新建对象）")
        void should_returnSameInstance_whenNoBackslash() {
            String noBackslash = "{\"stem\":\"普通题干\"}";

            assertSame(noBackslash, AiJsonRepair.repairBackslashes(noBackslash));
        }

        @Test
        @DisplayName("null 进 null 出")
        void should_returnNull_whenInputNull() {
            assertNull(AiJsonRepair.repairBackslashes(null));
        }

        @Test
        @DisplayName("已知取舍：\\times / \\nabla 不修（\\t \\n 是正文真会用到的字符，改了会误伤）")
        void should_documentKnownGap() {
            // 这两个选择不修是刻意的：无法区分「LaTeX 的 \times」和「真的制表符 + imes」
            assertEquals("\\times", AiJsonRepair.repairBackslashes("\\times"));
            assertEquals("\\nabla", AiJsonRepair.repairBackslashes("\\nabla"));
        }
    }

    @Nested
    @DisplayName("端到端：修复后能真的解析出正确内容")
    class ParseAfterRepair {

        @Test
        @DisplayName("含 \\frac 的题干：修复前静默损坏，修复后内容完好")
        void should_parseLatexStemCorrectly() throws Exception {
            String fromModel = "{\"stem\":\"求 \\frac{1}{2} + \\frac{1}{3} 的值\"}";

            // 先证明「不修真的会坏」—— 这是这个 bug 真实存在的证据
            String corrupted = objectMapper.readTree(fromModel).get("stem").asText();
            assertEquals("求 " + FF + "rac{1}{2} + " + FF + "rac{1}{3} 的值", corrupted);

            // 修复后：内容完好，公式还在
            String json = AiJsonRepair.repairBackslashes(fromModel);
            assertEquals("求 \\frac{1}{2} + \\frac{1}{3} 的值",
                    objectMapper.readTree(json).get("stem").asText());
        }

        @Test
        @DisplayName("含 \\beta 的题干：修复前静默损坏，修复后内容完好")
        void should_parseBetaCorrectly() throws Exception {
            String fromModel = "{\"stem\":\"已知 \\beta = 2\"}";

            assertEquals("已知 " + BS + "eta = 2",
                    objectMapper.readTree(fromModel).get("stem").asText());

            assertEquals("已知 \\beta = 2",
                    objectMapper.readTree(AiJsonRepair.repairBackslashes(fromModel)).get("stem").asText());
        }

        @Test
        @DisplayName("含 \\alpha 的题干：修复前抛异常，修复后正常解析")
        void should_parseWithoutException_whenInvalidEscape() throws Exception {
            String fromModel = "{\"stem\":\"已知 \\alpha + \\gamma = \\pi\"}";

            boolean threw = false;
            try {
                objectMapper.readTree(fromModel);
            } catch (Exception e) {
                threw = true;
            }
            assertEquals(true, threw, "\\alpha 本来就该让 Jackson 报错，否则这个测试就白写了");

            Map<?, ?> parsed = objectMapper.readValue(
                    AiJsonRepair.repairBackslashes(fromModel), Map.class);
            assertEquals("已知 \\alpha + \\gamma = \\pi", parsed.get("stem"));
        }

        @Test
        @DisplayName("真实场景：题干 + 选项 + 答案混着公式，整条 JSON 依然可解析")
        void should_parseRealisticPayload() throws Exception {
            String fromModel = """
                    {"questions":[{"chapter":"第一章","type":"填空题",
                    "stem":"计算 \\frac{d}{dx}(x^2) = ( )",
                    "options":[],"answer":"2x"}]}""";

            var root = objectMapper.readTree(AiJsonRepair.repairBackslashes(fromModel));
            var q = root.get("questions").get(0);

            assertEquals("计算 \\frac{d}{dx}(x^2) = ( )", q.get("stem").asText());
            assertEquals("2x", q.get("answer").asText());
        }
    }
}
