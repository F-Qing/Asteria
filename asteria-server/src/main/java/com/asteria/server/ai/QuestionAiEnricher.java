package com.asteria.server.ai;

import com.asteria.pojo.entity.Question;
import com.asteria.pojo.enums.TrueFalseAnswer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用 AI 给一道题补齐「解析」，题目原本没答案时顺手把「答案」也解出来。
 *
 * <p>为什么单独一个类：它只干"一道题进 → 解析出"。
 * 以后刷题页"重新生成解析"、错题本"生成讲解"都能直接复用。
 */
@Component
@Slf4j
public class QuestionAiEnricher {

    /** 解析要的是"照题目讲清楚"，不是发挥 → 温度压低（按功能写死的参数，不给用户填） */
    private static final double TEMPERATURE = 0.3;

    @Autowired
    private AiChatModelFactory chatModelFactory;

    /** AI 直接吐回来的结构（字段名要和 JSON Schema 对齐） */
    public record AiAnswer(String answer, String analysis) {
    }

    /** 给调用方的结果：answer 为 null 表示"不改答案" */
    public record EnrichResult(String answer, String analysis) {
    }

    /**
     * 给一道题补解析。
     *
     * <p>只收两个参数：题目本身 + AI 配置。"缺不缺答案"由题目自己决定（内部判断），
     * 不让调用方去操心这个规则。
     */
    public EnrichResult enrich(Question question, AiRequestConfig config) {
        // 题目原本缺答案：null（文件里没这一项）或空白串（写了但空着）
        boolean answerMissing = question.getAnswer() == null || question.getAnswer().isBlank();

        OpenAiChatModel chatModel = chatModelFactory.create(config, TEMPERATURE);
        BeanOutputConverter<AiAnswer> converter = new BeanOutputConverter<>(AiAnswer.class);

        String system = """
                你是一位资深教师，负责为题库里的题目写解析。要求：
                1. 用中文，3~5 句话，说清"为什么"以及涉及的知识点，不要复述题干。
                2. 题目给出了标准答案时，一律以它为准，不要质疑或改写。
                3. 标准答案为空时，你要自己解出正确答案，并在解析里说明依据。
                4. 解析里要写公式（LaTeX，如 \\frac、\\alpha、\\times）时，**反斜杠必须写成两个**：
                   写 "\\\\frac{1}{2}" 而不是 "\\frac{1}{2}"。少写一个反斜杠会让公式丢失，
                   甚至让整段 JSON 解析失败。
                5. 只输出 JSON，不要任何额外文字，不要 markdown 代码块。
                """;

        // getFormat() 生成的"格式说明书"必须自己拼进提示词，否则模型不受约束
        String user = buildUserPrompt(question, answerMissing) + "\n" + converter.getFormat();

        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage(system),
                new UserMessage(user))));

        // 文本 → 对象；解析不了会抛异常，由调用方记账。
        // 先做反斜杠修复：解析里经常要写公式（\frac 之类），裸反斜杠会让 JSON 解析失败或静默损坏
        AiAnswer ai = converter.convert(AiJsonRepair.repairBackslashes(
                response.getResult().getOutput().getText()));
        if (ai == null || ai.analysis() == null || ai.analysis().isBlank()) {
            throw new IllegalStateException("AI 没有返回可用的解析内容");
        }
        String analysis = ai.analysis().trim();

        // 缺答案 → 把 AI 解出来的答案也一起写回去
        if (answerMissing) {
            String answer = normalizeAiAnswer(ai.answer(), question.getType());
            if (answer == null) {
                log.warn("AI 返回的答案格式不合法，只保留解析：questionId={}, type={}, raw={}",
                        question.getId(), question.getType(), ai.answer());
            }
            return new EnrichResult(answer, analysis);
        }

        // 原本有答案 → 只写解析，不碰 answer
        return new EnrichResult(null, analysis);
    }

    /** 把题型 / 题干 / 选项 / 现有答案拼成给模型看的内容 */
    private String buildUserPrompt(Question question, boolean answerMissing) {
        StringBuilder sb = new StringBuilder();
        sb.append("题型：").append(question.getType()).append('\n');
        sb.append("题干：").append(question.getStem()).append('\n');
        if (question.getOptions() != null && !question.getOptions().isBlank()) {
            sb.append("选项：").append(question.getOptions()).append('\n');   // 库里存的就是 JSON 文本
        }
        sb.append("标准答案：")
                .append(answerMissing ? "（题目文件里没有提供，请你解出来）" : question.getAnswer())
                .append('\n');

        sb.append(switch (question.getType()) {
            case "SINGLE" -> "answer 必须是单个大写字母，例如 A。";
            case "MULTIPLE" -> "answer 必须是多个大写字母、用英文逗号分隔并升序，例如 A,C。";
            case "TRUE_FALSE" -> "answer 只能是 A（正确）或 B（错误）。";
            case "FILL_BLANK" -> "answer 是各空答案，多个空用中文分号「；」分隔。";
            default -> "answer 是参考答案文本。";
        });
        return sb.toString();
    }

    /** 按题型校验 AI 给的答案；不合法返回 null（宁可不写，也不写错的进去） */
    private String normalizeAiAnswer(String raw, String type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        return switch (type) {
            case "SINGLE" -> v.matches("[A-Z]") ? v : null;
            case "MULTIPLE" -> {
                if (!v.matches("[A-Z](,[A-Z])*")) {
                    yield null;
                }
                // 去重 + 升序，和文件里手写的答案风格保持一致
                yield v.chars().distinct()
                        .filter(c -> c >= 'A' && c <= 'Z')
                        .sorted()
                        .mapToObj(c -> String.valueOf((char) c))
                        .reduce((a, b) -> a + "," + b)
                        .orElse(null);
            }
            // 收敛到前端约定的 A/B（TRUE/FALSE 会导致判分时 "TRUE".equals("A") 恒为 false）
            case "TRUE_FALSE" -> TrueFalseAnswer.keyOf(v);
            default -> raw.trim();      // 简答/填空：交给模型，不做格式卡控
        };
    }
}
