package com.asteria.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
/**
 * 题目实体，对应 finaltext.question 表。
 *
 * <p>options / knowledgePoints 在库里是 JSON 列，这里用 String 承接原始 JSON 文本，
 * 由 Service 层用 Jackson 转成 List&lt;QuestionOption&gt; / List&lt;String&gt;——
 * 不用 MyBatis-Plus 的 JacksonTypeHandler，避免实体上必须加 autoResultMap 才生效的隐式坑。
 */
@Data
@TableName("question")
public class Question {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属题库 id（冗余列，方便按题库直接筛题） */
    private Long bankId;
    /** 所属章节 id（外键 → chapter.id，删章节级联删题目） */
    private Long chapterId;
    /** 题型：SINGLE 单选 / MULTIPLE 多选 / TRUE_FALSE 判断 / ESSAY 简答 / FILL_BLANK 填空（取值见 QuestionType 枚举） */
    private String type;
    /** 题干 */
    private String stem;
    /** 选项 JSON 数组：[{"key":"A","content":"..."}]；判断题/填空题/简答题为 null */
    private String options;
    /** 标准答案：单选 A / 多选 A,B / 判断 TRUE|FALSE / 简答为文本 / 填空多个空用「；」分隔 */
    private String answer;
    /** 答案解析：现在留空，接入 AI 后填写 */
    private String analysis;
    /** 知识点标签 JSON 数组：["知识点1","知识点2"]；接入 AI 后填写 */
    private String knowledgePoints;

    /**
     * AI 解析状态：PENDING 未解析 / DONE 成功 / FAILED 失败（取值见 {@code AiStatus}）。
     *
     * <p>断点续跑只捞非 DONE 的题 —— 模型调用是要花钱的，而且重试真的要再付一次，
     * 所以必须记清楚哪些题已经解析过。
     */
    private String aiStatus;
    /** AI 解析失败原因（已脱敏、可能被截断到 500 字） */
    private String aiError;
    /** 重试次数：用来识别"一直失败"的题，避免无限重试烧钱 */
    private Integer aiRetryCount;
    /**
     * 答案来源：FILE 原文解析 / AI 模型推断 / MANUAL 人工修改（取值见 {@code AnswerSource}）。
     *
     * <p>数据血缘：AI 补的答案必须能区分出来，否则人工复核和回滚都无从下手。
     */
    private String answerSource;
    /** AI 补解析完成时间 */
    private LocalDateTime aiEnrichedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
