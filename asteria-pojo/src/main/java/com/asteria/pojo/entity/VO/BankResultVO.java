package com.asteria.pojo.entity.VO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 导入任务快照：GET /api/banks/import/{taskId} 的返回体（接口文档「查询导入任务进度」） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankResultVO {
    private String taskId;         // 任务 ID（UUID）
    private String status;         // ImportStatus.name()
    private String fileName;       // 用户上传的原始文件名
    private Long fileSize;         // 文件大小（字节）
    private Integer progress;      // 进度 0-100（文档定义为整数）
    private Long bankId;           // 成功后才有值，其余阶段为 null
    private Integer totalCount;    // 识别题目总数
    private String errorMessage;   // FAILED 时的失败原因

    /*
     * 下面三个 AI 解析计数是【实时算出来的】，不落库。
     *
     * 为什么不存进 bank_import：question.ai_status 已经是每道题成败的唯一真相源，
     * 再在任务行上存一份计数就是第二个真相源 —— 写时机稍微对不上（比如某题失败后重试成功）
     * 就会出现"界面上说 3 道没解析、实际只剩 1 道"，而且没法自查。
     * 现算的代价是轮询时多几次 COUNT，但 bank_id 上有索引，比维护一致性便宜得多。
     */

    /** AI 解析已完成的题数 */
    private Integer aiDoneCount;
    /** 还没解析的题数（PENDING；失败的那些算在 aiFailedCount 里，不重复计） */
    private Integer aiPendingCount;
    /** 解析失败的题数 */
    private Integer aiFailedCount;
}