package com.asteria.server.Services.impl;

import com.asteria.pojo.entity.Question;
import com.asteria.pojo.entity.QuestionPage;
import com.asteria.pojo.entity.VO.ChapterVO;
import com.asteria.pojo.entity.VO.QuestionOptionVO;
import com.asteria.pojo.entity.VO.QuestionVO;
import com.asteria.server.Services.QuestionsService;
import com.asteria.server.mapper.ChapterMapper;
import com.asteria.server.mapper.QuestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class QuestionsServiceImpl implements QuestionsService {
    private static final int MAX_PAGE_SIZE = 100;
    @Autowired
    private QuestionMapper questionsMapper;
    @Autowired
    private ChapterMapper chapterMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Override
    public QuestionPage PageQuestions(Long bankId, Long chapterId, String type, String keyword,
                                      Integer page, Integer pageSize) {
        // ===== 参数兜底 =====
        long current = (page == null || page < 1) ? 1 : page;
        long size = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, MAX_PAGE_SIZE);
        // 关键字前后空格没有意义，先去掉；去完成空串就当没传
        String stemKeyword = (keyword == null) ? null : keyword.trim();

        // ===== 第 1 步：按条件分页查题目（只查当前页）=====
        // 每个筛选条件都带一个 boolean 开关：开关为 false 时 MyBatis-Plus 直接不拼这段 SQL，
        // 所以「查整库」和「按章节/题型/关键字筛」共用同一个方法，不用写好几条 SQL。
        LambdaQueryWrapper<Question> wrapper = Wrappers.<Question>lambdaQuery()
                .eq(bankId != null, Question::getBankId, bankId)                 // 题库有值才拼
                .eq(chapterId != null, Question::getChapterId, chapterId)        // 章节有值才拼
                .eq(type != null && !type.isBlank(), Question::getType, type)    // 题型有值才拼
                .like(stemKeyword != null && !stemKeyword.isEmpty(),             // 关键字有值才拼 LIKE
                        Question::getStem, stemKeyword)
                .orderByDesc(Question::getCreatedAt);                            // 新导入的排前面
        Page<Question> pageParam = Page.of(current, size);
        questionsMapper.selectPage(pageParam, wrapper);
        List<Question> list = pageParam.getRecords();
        // ===== 第 2 步：章节名不在 question 表里 → 先查一次章节，建「章节id → 章节名」索引 =====
        // 只查一次，别在循环里查库（那样就是 N+1）
        Map<Long, String> chapterNames = new HashMap<>();
        if (bankId != null) {
            for (ChapterVO chapter : chapterMapper.selectChaptersWithCount(bankId)) {
                chapterNames.put(chapter.getId(), chapter.getName());
            }
        }

        // ===== 第 3 步：实体 → VO =====
        List<QuestionVO> questionVOList = new ArrayList<>();
        for (Question question : list) {
            QuestionVO questionVO = new QuestionVO();
            // 同名字段自动拷：id / bankId / chapterId / type / stem / answer / analysis
            BeanUtils.copyProperties(question, questionVO);

            // BeanUtils 拷不过来的三样：
            // 1) 章节名要查表；2) options 是 JSON 字符串要转数组；3) knowledgePoints 同理
            questionVO.setChapterName(chapterNames.getOrDefault(question.getChapterId(), ""));
            questionVO.setOptions(parseJson(question.getOptions(), new TypeReference<List<QuestionOptionVO>>() {}));
            questionVO.setKnowledgePoints(parseJson(question.getKnowledgePoints(), new TypeReference<List<String>>() {}));
            questionVOList.add(questionVO);
        }

        QuestionPage questionPage = new QuestionPage();
        questionPage.setList(questionVOList);
        questionPage.setTotal(pageParam.getTotal());
        questionPage.setPage(current);
        questionPage.setPageSize(size);
        return questionPage;
    }

    /** JSON 字符串 → 对象/列表；空值或解析失败都返回 null（不让一条脏 JSON 把整页搞挂） */
    private <T> T parseJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("JSON 解析失败：{}", json, e);
            return null;
        }
    }
}
