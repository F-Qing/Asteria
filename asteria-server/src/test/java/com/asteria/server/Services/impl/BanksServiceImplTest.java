package com.asteria.server.Services.impl;

import com.asteria.common.Tool.FileTextReader;
import com.asteria.common.exception.BusinessException;
import com.asteria.pojo.entity.BankImport;
import com.asteria.pojo.entity.VO.BankResultVO;
import com.asteria.pojo.entity.VO.BanksVO;
import com.asteria.pojo.enums.ImportStatus;
import com.asteria.server.Services.BanksImportTransactional;
import com.asteria.server.mapper.BanksImportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * BanksServiceImpl 单元测试（教程 08 业务篇）。
 * 只测主线程同步逻辑：上传校验矩阵 + Message 轮询三级回落。
 * 后台导入线程属于异步流程，留给集成测试（教程 §6 有说明）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("题库导入服务")
class BanksServiceImplTest {

    @Mock
    private BanksImportMapper bankImportMapper;
    @Mock
    private FileTextReader fileTextReader;
    @Mock
    private BanksImportTransactional importTransactional;

    @InjectMocks
    private BanksServiceImpl banksService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // @Value 注入的配置字段在纯 Mockito 环境里不存在，手动指到临时目录
        ReflectionTestUtils.setField(banksService, "uploadDir", tempDir.toString());
    }

    /** 造一个"内存文件"：不落盘、不用真实磁盘，isEmpty/getSize 都按内容来 */
    private MultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, null, content);
    }

    @Nested
    @DisplayName("上传校验 importBanks")
    class ImportValidation {

        @Test
        @DisplayName("文件为 null → 40001，且不碰数据库")
        void should_throw40001_when_fileIsNull() {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> banksService.importBanks(null, "题库", null));

            assertEquals(40001, e.getCode());
            verifyNoInteractions(bankImportMapper);
        }

        @Test
        @DisplayName("空文件 → 40001")
        void should_throw40001_when_fileIsEmpty() {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> banksService.importBanks(file("a.txt", new byte[0]), "题库", null));

            assertEquals(40001, e.getCode());
        }

        @Test
        @DisplayName("扩展名不在白名单（exe）→ 40002，报错带原文件名")
        void should_throw40002_when_extNotAllowed() {
            MultipartFile evil = file("virus.exe", "content".getBytes());

            BusinessException e = assertThrows(BusinessException.class,
                    () -> banksService.importBanks(evil, "题库", null));

            assertEquals(40002, e.getCode());
            assertTrue(e.getMessage().contains("virus.exe"));
        }

        @Test
        @DisplayName("没有扩展名 → 同样按非法类型拦截（extOf 返回空串）")
        void should_throw40002_when_extMissing() {
            MultipartFile noExt = file("README", "content".getBytes());

            BusinessException e = assertThrows(BusinessException.class,
                    () -> banksService.importBanks(noExt, "题库", null));

            assertEquals(40002, e.getCode());
        }

        @Test
        @DisplayName("超过 20MB → 40003（mock 报大小，不必真造 20MB 内存）")
        void should_throw40003_when_fileTooLarge() {
            MultipartFile huge = mock(MultipartFile.class);
            when(huge.isEmpty()).thenReturn(false);
            when(huge.getOriginalFilename()).thenReturn("huge.pdf");
            when(huge.getSize()).thenReturn(20L * 1024 * 1024 + 1);

            BusinessException e = assertThrows(BusinessException.class,
                    () -> banksService.importBanks(huge, "题库", null));

            assertEquals(40003, e.getCode());
        }

        @Test
        @DisplayName("合法 txt：登记 PENDING 任务并返回 taskId")
        void should_createPendingTask_when_txtIsValid() throws IOException {
            MultipartFile ok = file("题库.txt", "1. 以下正确的是？\nA. 甲\nB. 乙".getBytes());

            BanksVO vo = banksService.importBanks(ok, "我的题库", null);

            // 返回值：taskId 是随机 UUID，只能断言"非空 + 状态正确"
            assertNotNull(vo.getTaskId());
            assertEquals(ImportStatus.PENDING.name(), vo.getStatus());

            // 数据库登记：状态必须是 PENDING、进度 0、带上了文件名
            ArgumentCaptor<BankImport> captor = ArgumentCaptor.forClass(BankImport.class);
            verify(bankImportMapper).insert(captor.capture());
            BankImport saved = captor.getValue();
            assertEquals(ImportStatus.PENDING.name(), saved.getStatus());
            assertEquals(0, saved.getProgress());
            assertEquals("题库.txt", saved.getFileName());
            // 断言 UUID 形状：36 位带 4 个短横（说明没用原始文件名当存储名）
            assertTrue(saved.getTaskId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        }
    }

    @Nested
    @DisplayName("轮询任务状态 Message（内存 → 数据库 → 不存在 三级回落）")
    class MessageFallback {

        @SuppressWarnings("unchecked")
        private ConcurrentHashMap<String, BankResultVO> snapshotMap() {
            return (ConcurrentHashMap<String, BankResultVO>)
                    ReflectionTestUtils.getField(banksService, "importTasks");
        }

        @Test
        @DisplayName("第一级：内存有快照就优先返回（实时进度），不查数据库")
        void should_returnMemorySnapshotAndSkipDb_when_snapshotExists() {
            snapshotMap().put("t1", BankResultVO.builder()
                    .taskId("t1").status(ImportStatus.AI_PROCESSING.name()).progress(42).build());

            BankResultVO result = banksService.Message("t1");

            assertEquals(42, result.getProgress());
            assertEquals(ImportStatus.AI_PROCESSING.name(), result.getStatus());
            verifyNoInteractions(bankImportMapper);
        }

        @Test
        @DisplayName("第二级：内存没有（如服务重启）→ 回落数据库记录")
        void should_fallbackToDb_when_memoryMiss() {
            when(bankImportMapper.selectById("t2")).thenReturn(BankImport.builder()
                    .taskId("t2").status(ImportStatus.SUCCESS.name()).progress(100)
                    .fileName("旧题库.docx").totalCount(50).bankId(7L).build());

            BankResultVO result = banksService.Message("t2");

            assertEquals(ImportStatus.SUCCESS.name(), result.getStatus());
            assertEquals(50, result.getTotalCount());
            assertEquals(7L, result.getBankId());
        }

        @Test
        @DisplayName("第三级：内存和库都没有 → 返回 FAILED“任务不存在”而不是抛异常或 null")
        void should_returnFailedVO_when_taskNowhere() {
            // 不 stub：mock 的 selectById 默认就返回 null，正好模拟"查无此任务"
            BankResultVO result = banksService.Message("ghost");

            assertEquals(ImportStatus.FAILED.name(), result.getStatus());
            assertEquals("任务不存在", result.getErrorMessage());
            verify(bankImportMapper).selectById("ghost");
        }
    }

    @Test
    @DisplayName("bankName 留空时用 taskId 兜底，不会写出 null 名字")
    void should_useTaskIdAsBankName_when_bankNameBlank() throws IOException {
        MultipartFile ok = file("x.txt", "1. 题目".getBytes());

        BanksVO vo = banksService.importBanks(ok, "   ", null);

        ArgumentCaptor<BankImport> captor = ArgumentCaptor.forClass(BankImport.class);
        verify(bankImportMapper).insert(captor.capture());
        assertEquals(vo.getTaskId(), captor.getValue().getBankName());
    }
}
