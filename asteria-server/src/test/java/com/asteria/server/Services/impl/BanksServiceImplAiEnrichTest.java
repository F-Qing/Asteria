package com.asteria.server.Services.impl;

import com.asteria.pojo.entity.Question;
import com.asteria.pojo.entity.VO.BankResultVO;
import com.asteria.pojo.enums.AiStatus;
import com.asteria.pojo.enums.ImportStatus;
import com.asteria.server.ai.AiRequestConfig;
import com.asteria.server.ai.QuestionAiEnricher;
import com.asteria.server.mapper.QuestionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 解析的「部分失败」行为测试。
 *
 * <p>要守住两条线：
 * <ol>
 *   <li><b>单题失败不许毁掉整批</b>：失败只记账，任务照常成功；</li>
 *   <li><b>账号级失败（余额不足/限流/Key 失效）必须被认出来</b>：
 *       认不出来就会对着一个欠费的账号把整个题库跑一遍，白等白花钱。</li>
 * </ol>
 *
 * <p>为什么直接测包级私有的 {@code aiEnrich} / {@code isSystemicAiFailure}：
 * 这两个方法都是纯逻辑（前者靠 mock 的 Mapper/Enricher，后者完全不碰外部资源），
 * 走 HTTP 或整条导入链路去测只会让"到底是哪条判断错了"变模糊。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 解析：部分失败与账号级失败")
class BanksServiceImplAiEnrichTest {

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private QuestionAiEnricher questionAiEnricher;

    @InjectMocks
    private BanksServiceImpl banksService;

    /**
     * 手工建一次实体元信息。
     *
     * <p>为什么需要：{@code LambdaUpdateWrapper.set(Question::getAiStatus, ...)} 会【立刻】把方法引用
     * 解析成列名，而这份解析缓存平时是 MyBatis 启动时建 TableInfo 才填进去的。
     * 纯 Mockito 测试里没有 MyBatis 上下文，不初始化就会抛
     * {@code MybatisPlusException: can not find lambda cache for this entity [Question]} ——
     * 那是测试环境缺件，不是被测代码的问题（生产启动时一定有）。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Question.class);
    }

    /** 一次性给出本次待解析的题（aiEnrich 第一步就是从库里捞题） */
    private void givenQuestions(long... ids) {
        List<Question> questions = Arrays.stream(ids).mapToObj(id -> {
            Question q = new Question();
            q.setId(id);
            q.setBankId(100L);
            q.setAiStatus(AiStatus.PENDING.name());
            return q;
        }).toList();
        when(questionMapper.selectList(any())).thenReturn(questions);
    }

    private AiRequestConfig config() {
        return new AiRequestConfig("openai", "sk-secret", "https://api.example.com", "gpt-x");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, BankResultVO> snapshotMap() {
        return (ConcurrentHashMap<String, BankResultVO>)
                ReflectionTestUtils.getField(banksService, "importTasks");
    }

    // ========== 单题失败：只记账，不中断 ==========

    @Test
    @DisplayName("单题失败：如实计入失败数、不抛异常（任务照常成功，用户能用剩下的题）")
    void should_countSingleFailure_withoutThrowing() {
        givenQuestions(1L, 2L, 3L);
        // 用题目 id 决定成败，而不是靠 thenReturn/thenThrow 的调用顺序 ——
        // 并发跑的时候调用顺序本来就不确定，靠顺序的测试会变成随机失败
        when(questionAiEnricher.enrich(any(), any())).thenAnswer(invocation -> {
            Question q = invocation.getArgument(0);
            if (q.getId() == 3L) {
                throw new IllegalStateException("AI 没有返回可用的解析内容");
            }
            return QuestionAiEnricher.EnrichResult.analysisOnly("这是解析");
        });

        BanksServiceImpl.EnrichOutcome outcome = banksService.aiEnrich("t1", 100L, config());

        assertEquals(1, outcome.failedCount());
        assertFalse(outcome.abortedBySystemIssue(), "普通单题失败不该被当成账号级问题");
        assertNull(outcome.systemicReason());
    }

    // ========== 账号级失败的识别 ==========

    @Test
    @DisplayName("余额不足（英文 insufficient balance）→ 判为账号级失败")
    void should_detectInsufficientBalance() {
        assertTrue(banksService.isSystemicAiFailure(
                new RuntimeException("Error: insufficient balance, please top up")));
    }

    @Test
    @DisplayName("限流（429 / rate limit）→ 判为账号级失败")
    void should_detectRateLimit() {
        assertTrue(banksService.isSystemicAiFailure(
                new RuntimeException("HTTP 429 Too Many Requests")));
        assertTrue(banksService.isSystemicAiFailure(
                new RuntimeException("rate limit exceeded, retry later")));
    }

    @Test
    @DisplayName("中文原因（余额不足 / 请求频率过高）→ 也能识别")
    void should_detectChineseReason() {
        assertTrue(banksService.isSystemicAiFailure(new RuntimeException("账户余额不足，请充值后重试")));
        assertTrue(banksService.isSystemicAiFailure(new RuntimeException("请求频率过高，已被限流")));
    }

    @Test
    @DisplayName("原因包在 cause 里（Spring AI 会再包一层）→ 仍能识别")
    void should_detectSystemicFailure_inNestedCause() {
        Throwable wrapped = new RuntimeException("ChatModel call failed",
                new IllegalStateException("insufficient quota"));

        assertTrue(banksService.isSystemicAiFailure(wrapped));
    }

    @Test
    @DisplayName("普通单题错误（AI 没返回可用解析）→ 不能误判成账号级失败")
    void should_notFlagNormalSingleQuestionError() {
        assertFalse(banksService.isSystemicAiFailure(
                new IllegalStateException("AI 没有返回可用的解析内容")));
        assertFalse(banksService.isSystemicAiFailure(
                new RuntimeException("JSON 解析失败：Unexpected end of input")));
    }

    @Test
    @Timeout(5)
    @DisplayName("cause 链自引用或成环时不死循环（防御性上限）")
    void should_notHangOnCyclicCauseChain() {
        Throwable selfReference = new RuntimeException("boom") {
            @Override
            public Throwable getCause() {
                return this;
            }
        };
        assertFalse(banksService.isSystemicAiFailure(selfReference));

        // A → B → A：自引用检查发现不了，只能靠层数上限兜住
        Throwable[] pair = new Throwable[2];
        pair[0] = new RuntimeException("a") {
            @Override
            public Throwable getCause() {
                return pair[1];
            }
        };
        pair[1] = new RuntimeException("b") {
            @Override
            public Throwable getCause() {
                return pair[0];
            }
        };
        assertFalse(banksService.isSystemicAiFailure(pair[0]));
    }

    // ========== 账号级失败后的善后 ==========

    @Test
    @DisplayName("账号级失败：本轮如实记账，并触发「已标 FAILED 的题改回 PENDING」")
    void should_unmarkRetryableFailures_when_systemicFailure() {
        givenQuestions(1L, 2L);
        when(questionAiEnricher.enrich(any(), any()))
                .thenThrow(new RuntimeException("insufficient balance"));

        BanksServiceImpl.EnrichOutcome outcome = banksService.aiEnrich("t1", 100L, config());

        assertEquals(2, outcome.failedCount());
        assertTrue(outcome.abortedBySystemIssue());
        assertTrue(outcome.systemicReason().contains("insufficient balance"),
                "系统性失败的原因要能带到任务级（前端失败卡片显示的就是它）");
        // markEnrichFailed 走 updateById（单题行），批次级"改回 PENDING"走 update(条件更新) ——
        // 验证后者被调用，说明 aiEnrich 确实把善后接上了，而不是只记了个标记
        verify(questionMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("改回 PENDING 时：只动还是 FAILED 的题，状态写成 PENDING 并清空失败原因")
    @SuppressWarnings("unchecked")
    void should_resetOnlyFailedQuestions_toPending() {
        Question q1 = new Question();
        q1.setId(1L);
        Question q2 = new Question();
        q2.setId(2L);

        banksService.unmarkRetryableFailures(List.of(q1, q2));

        ArgumentCaptor<Wrapper<Question>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(questionMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<Question> wrapper = (LambdaUpdateWrapper<Question>) captor.getValue();

        // WHERE：id 限定本次处理过的题，且必须带 ai_status = FAILED ——
        // 少了这个条件就会把已经 DONE 的题也倒退回 PENDING，等于把花过的钱再花一遍
        assertTrue(wrapper.getSqlSegment().contains("ai_status"), wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSegment().contains("id"), wrapper.getSqlSegment());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(AiStatus.FAILED.name()),
                "条件值必须是 AiStatus.FAILED 的枚举名，不能是别的字符串："
                        + wrapper.getParamNameValuePairs());

        // SET：状态 → PENDING（用枚举名，不写字面量）+ 清空错误原因（否则界面还挂着旧报错）
        assertTrue(wrapper.getSqlSet().contains("ai_status"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("ai_error"), wrapper.getSqlSet());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(AiStatus.PENDING.name()),
                "SET 里要把状态改成 AiStatus.PENDING：" + wrapper.getParamNameValuePairs());
    }

    // ========== 界面要的实时计数 ==========

    @Test
    @DisplayName("轮询命中内存快照时，带上实时 AI 计数（DONE / FAILED / 待解析）")
    void should_fillAiCounts_when_memoryHit() {
        snapshotMap().put("t9", BankResultVO.builder()
                .taskId("t9")
                .status(ImportStatus.SUCCESS.name())
                .progress(100)
                .bankId(100L)
                .build());
        // 依次是 DONE 5 / FAILED 2 / 总数 10 → 待解析 3
        when(questionMapper.selectCount(any())).thenReturn(5L, 2L, 10L);

        BankResultVO result = banksService.Message("t9");

        assertEquals(5, result.getAiDoneCount());
        assertEquals(2, result.getAiFailedCount());
        assertEquals(3, result.getAiPendingCount());
    }

    @Test
    @DisplayName("任务还没入库（bankId 为 null）时不查库，计数保持 null（前端据此不显示提示）")
    void should_skipAiCounts_when_bankIdIsNull() {
        snapshotMap().put("t10", BankResultVO.builder()
                .taskId("t10")
                .status(ImportStatus.PARSING.name())
                .progress(10)
                .build());

        BankResultVO result = banksService.Message("t10");

        assertNull(result.getAiDoneCount());
        assertNull(result.getAiFailedCount());
        assertNull(result.getAiPendingCount());
    }

    /**
     * 40021 的**措辞是一条跨端契约**，这里守住它。
     *
     * <p>为什么用"读源码"而不是直接调用：这条消息是在 {@code processImport} 里抛的，
     * 而那是个跑在后台线程里、要读文件要连库的大方法，单测起来成本远高于收益。
     *
     * <p>要守住两件事：
     * <ol>
     *   <li><b>前缀必须是 {@code AI 解析中断：}</b> —— 前端 {@code BankImportView.vue} 靠它区分
     *       "真回滚（题库没建成）" 和 "题库已入库只是没解析完"，改前缀会让那边静默失效；</li>
     *   <li><b>「已完成 N 道」必须排在服务商报文之前</b> —— 整条消息最终会被
     *       {@code briefReason()} 截断到 200 字，而报文可能上百字；
     *       把计数放在报文后面，报文一长就把用户最需要的信息截掉了。</li>
     * </ol>
     */
    @Test
    @DisplayName("40021 的措辞契约：前缀不能变，且「已完成」必须在服务商报文之前")
    void should_keep40021MessageContract() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/asteria/server/Services/impl/BanksServiceImpl.java"));

        // ① 前缀：前端 BankImportView.vue 的 isAiInterrupted 依赖它
        assertTrue(source.contains("\"AI 解析中断：\""),
                "前缀「AI 解析中断：」是前端判断依据，改了要同步改 BankImportView.vue（全角冒号）");

        // ② 顺序：计数的位置必须早于 systemicReason() 的拼接位置。
        //    按"最后一次出现"定位更稳 —— 注释里也提到了 systemicReason，按首次出现会定位到注释而不是代码。
        int countPos = source.indexOf("\"AI 解析中断：已完成 \"");
        int reasonPos = source.lastIndexOf("aiOutcome.systemicReason()");
        assertTrue(countPos > 0, "找不到「AI 解析中断：已完成」的拼装，可能被改写了");
        assertTrue(reasonPos > countPos,
                "服务商报文必须排在「已完成 N 道」【之后】—— 否则 briefReason 的 200 字截断会吃掉计数信息"
                        + "（countPos=" + countPos + ", reasonPos=" + reasonPos + "）");
    }
}
