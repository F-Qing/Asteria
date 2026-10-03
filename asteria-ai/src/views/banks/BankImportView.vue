<template>
  <div class="bank-import-view">
    <!-- ── 上传表单视图 ── -->
    <GlassCard v-if="!task" title="导入题库" class="import-card">
      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        :limit="1"
        accept=".docx,.pdf,.txt"
        :on-change="onFileChange"
        :on-exceed="onFileExceed"
        class="upload-area"
      >
        <CloudUpload :size="42" :stroke-width="1.4" class="upload-icon" />
        <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em></div>
        <div class="upload-hint">支持 DOCX / PDF / TXT，单个文件 ≤ 20MB</div>
      </el-upload>

      <!-- 格式入口：解析不出题时用户能自助解决，不用猜 -->
      <div class="format-tip">
        <Info :size="14" :stroke-width="1.8" />
        <span>格式比较乱、怕识别不出来？</span>
        <button type="button" class="link-btn" @click="formatGuideVisible = true">
          查看格式要求 / 复制 AI 整理提示词
        </button>
      </div>

      <div class="form-rows">
        <div class="form-row">
          <label class="form-label">题库名称（可选，默认取文件名）</label>
          <el-input v-model="bankName" placeholder="例如：数据库期末复习题库" />
        </div>
        <div class="form-row">
          <label class="form-label">AI 解析</label>
          <div style="display: flex; align-items: center; gap: 10px">
            <el-switch v-model="aiParse" />
            <span class="text-muted" style="font-size: 13px">
              导入后用 AI 逐题生成解析；没有答案的题会自动补答案（较慢，100 题约 3~8 分钟）
            </span>
          </div>
        </div>
      </div>

      <div class="form-actions">
        <SoftButton variant="ghost" @click="router.push('/banks')">返回</SoftButton>
        <SoftButton :icon="CloudUpload" :loading="uploading" :disabled="!file" @click="startImport">
          开始导入
        </SoftButton>
      </div>
    </GlassCard>

    <!-- ── 进度 / 结果视图 ── -->
    <GlassCard v-else class="progress-card">
      <div class="progress-head">
        <FileText :size="20" :stroke-width="1.6" />
        <div class="progress-file">
          <span class="file-name">{{ task.fileName }}</span>
          <span class="file-size">{{ formatFileSize(task.fileSize) }}</span>
        </div>
      </div>

      <template v-if="task.status !== 'SUCCESS' && task.status !== 'FAILED'">
        <SoftProgress :value="task.progress" show-text class="progress-bar" />
        <div class="stage-line">
          <BreathingLoader small />
          <span>{{ stageText }}</span>
        </div>
      </template>

      <div v-else-if="task.status === 'SUCCESS'" class="result-block success">
        <div class="result-figure success"><Check :size="30" :stroke-width="2" /></div>
        <h3>导入完成</h3>
        <p class="text-muted">共识别 {{ task.totalCount }} 道题目，已整理入库</p>
        <!-- 只有真有失败题才多这一行：没失败时加提示纯属噪音 -->
        <p v-if="task.aiFailedCount" class="ai-warn">
          其中 {{ task.aiFailedCount }} 道题未生成解析，不影响做题
        </p>
        <div class="result-actions">
          <SoftButton variant="outline" @click="reset">继续导入</SoftButton>
          <SoftButton :icon="ArrowRight" @click="router.push(`/banks/${task.bankId}`)">查看题库</SoftButton>
        </div>
      </div>

      <div v-else class="result-block failed">
        <div class="result-figure failed"><TriangleAlert :size="30" :stroke-width="1.6" /></div>
        <h3>导入失败</h3>
        <p class="error-msg">{{ task.errorMessage || '文件解析失败，请检查文件内容' }}</p>
        <!-- 两类 FAILED 的数据命运完全相反，不能共用一句话：
             真失败（切不出题/入库失败）整个事务回滚了，题库没建成；
             而 40021「AI 解析中断」是账号级问题，题库和题目在事务里【已经提交】，数据是留着的。
             用一句"数据已回滚"盖住两种，会让账号欠费的用户以为题库没了。 -->
        <p v-if="isAiInterrupted" class="text-muted">题目已入库，可正常使用</p>
        <p v-else class="text-muted">数据已回滚，不会产生脏数据</p>
        <div class="result-actions">
          <SoftButton variant="outline" :icon="RotateCcw" @click="reset">重新上传</SoftButton>
          <SoftButton variant="ghost" @click="formatGuideVisible = true">查看格式要求</SoftButton>
        </div>
      </div>
    </GlassCard>

    <!-- 标准格式说明 + AI 整理提示词（导入失败时也能直接打开） -->
    <FormatGuideDialog v-model="formatGuideVisible" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadFiles, UploadInstance, UploadRawFile } from 'element-plus'
import { CloudUpload, FileText, Check, TriangleAlert, ArrowRight, RotateCcw, Info } from 'lucide-vue-next'
import GlassCard from '@/components/base/GlassCard.vue'
import SoftButton from '@/components/base/SoftButton.vue'
import SoftProgress from '@/components/base/SoftProgress.vue'
import BreathingLoader from '@/components/base/BreathingLoader.vue'
import FormatGuideDialog from '@/components/business/FormatGuideDialog.vue'
import { getImportTask, importBank } from '@/api/bank'
import { useBankStore } from '@/stores/bank'
import { formatFileSize } from '@/utils/format'
import type { ImportTask } from '@/types'

const router = useRouter()
const bankStore = useBankStore()

const MAX_SIZE = 20 * 1024 * 1024
const ALLOWED = ['docx', 'pdf', 'txt']

const uploadRef = ref<UploadInstance>()
const file = ref<File | null>(null)
const bankName = ref('')
const uploading = ref(false)

/** 是否用 AI 生成解析（随上传请求一起发，后端据此决定要不要跑 AI 阶段） */
const aiParse = ref(false)

/** 格式要求弹窗（上传前和导入失败后都能打开） */
const formatGuideVisible = ref(false)

const task = ref<ImportTask | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null

onBeforeUnmount(stopPoll)

/* ── 文件校验 ── */
function validate(f: UploadRawFile): boolean {
  const ext = f.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ALLOWED.includes(ext)) {
    ElMessage.error('仅支持 DOCX / PDF / TXT 文件')
    return false
  }
  if (f.size > MAX_SIZE) {
    ElMessage.error('文件大小不能超过 20MB')
    return false
  }
  return true
}

function onFileChange(f: UploadFile, files: UploadFiles) {
  if (!validate(f.raw!)) {
    uploadRef.value?.clearFiles()
    file.value = null
    return
  }
  if (files.length > 1) uploadRef.value?.clearFiles()
  file.value = f.raw!
  if (!bankName.value) bankName.value = f.name.replace(/\.[^.]+$/, '')
}

function onFileExceed(files: File[]) {
  uploadRef.value?.clearFiles()
  const f = files[0] as UploadRawFile
  if (validate(f)) {
    file.value = f
    if (!bankName.value) bankName.value = f.name.replace(/\.[^.]+$/, '')
  }
}

/* ── 上传并创建导入任务 ── */
async function startImport() {
  if (!file.value || uploading.value) return
  uploading.value = true
  try {
    const created = await importBank({
      file: file.value,
      bankName: bankName.value || undefined,
      aiParse: aiParse.value
    })
    task.value = {
      taskId: created.taskId,
      status: created.status,
      fileName: file.value.name,
      fileSize: file.value.size,
      progress: 0,
      bankId: null,
      totalCount: 0,
      errorMessage: null
    }
    startPoll()
  } catch {
    // 上传失败提示已由 request 层给出
  } finally {
    uploading.value = false
  }
}

/* ── 1.5s 轮询任务进度 ── */
function startPoll() {
  stopPoll()
  const tick = async () => {
    if (!task.value) return
    try {
      const t = await getImportTask(task.value.taskId)
      task.value = t
      if (t.status === 'SUCCESS' || t.status === 'FAILED') {
        bankStore.fetchBanks({}, true) // 刷新题库缓存
        return
      }
    } catch {
      // 单次轮询失败不中断，继续尝试
    }
    pollTimer = setTimeout(tick, 1500)
  }
  pollTimer = setTimeout(tick, 1500)
}

function stopPoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

/**
 * 任务是不是被账号级问题（余额不足/限流/Key 失效）打断的。
 *
 * ⚠️ 这里靠前缀认后端 40021 的文案，和后端 BanksServiceImpl 里
 * `"AI 解析中断：" + reason` 那句是硬耦合 —— 改文案必须两边一起改。
 * 之所以认文案而不是错误码：轮询接口返回的是任务快照，没有单独的 code 字段。
 * 认出来是为了区分两类 FAILED：真失败=题库回滚了，AI 中断=题目已经入库。
 */
const isAiInterrupted = computed(() => task.value?.errorMessage?.startsWith('AI 解析中断：') ?? false)

/* ── 阶段文案：解析文档→识别题目→AI 解析入库 ── */
const stageText = computed(() => {
  switch (task.value?.status) {
    case 'PENDING':
      return '排队等待解析…'
    case 'PARSING':
      return '解析文档 → 识别题目…'
    case 'AI_FORMATTING':
      return 'AI 正在识别题目结构（文件排版较乱，正逐块抽取）…'
    case 'AI_PROCESSING':
      return 'AI 解析入库中…'
    default:
      return '处理中…'
  }
})

function reset() {
  stopPoll()
  task.value = null
  file.value = null
  bankName.value = ''
  uploadRef.value?.clearFiles()
}
</script>

<style scoped>
.bank-import-view {
  max-width: 720px;
  margin: 0 auto;
}
.upload-area :deep(.el-upload) {
  width: 100%;
}
.upload-area :deep(.el-upload-dragger) {
  width: 100%;
  padding: var(--space-7) var(--space-5);
}
.upload-icon {
  color: var(--primary-soft);
  margin-bottom: var(--space-3);
}
.upload-hint {
  margin-top: var(--space-2);
  font-size: 13px;
  color: var(--text-muted);
}
.format-tip {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: var(--space-3);
  font-size: 13px;
  color: var(--text-muted);
}
.link-btn {
  padding: 0;
  border: none;
  background: none;
  font-size: 13px;
  color: var(--primary);
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
  transition: var(--transition);
}
.link-btn:hover {
  color: var(--primary-hover);
}
.form-rows {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  margin: var(--space-5) 0;
}
.form-label {
  display: block;
  font-weight: 600;
  font-size: 14px;
  margin-bottom: var(--space-2);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-3);
}

.progress-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
  color: var(--primary-soft);
}
.progress-file {
  display: flex;
  flex-direction: column;
}
.file-name {
  font-weight: 600;
  color: var(--text-main);
}
.file-size {
  font-size: 13px;
  color: var(--text-muted);
}
.progress-bar {
  margin-bottom: var(--space-4);
}
.stage-line {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  color: var(--text-sub);
  font-size: 14px;
}
.result-block {
  text-align: center;
  padding: var(--space-5) 0;
}
/* 结果图标：粉彩渐变圆底 + 线性图标（替代 emoji） */
.result-figure {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto var(--space-3);
  border: 1px solid var(--glass-line);
  box-shadow: var(--shadow-sm), var(--rim-light);
  animation: float 6s ease-in-out infinite;
}
.result-figure.success {
  background: linear-gradient(135deg, color-mix(in srgb, var(--mint) 80%, transparent), color-mix(in srgb, var(--blue-gray) 60%, transparent));
  color: var(--success);
}
.result-figure.failed {
  background: linear-gradient(135deg, color-mix(in srgb, var(--error-bg) 85%, transparent), color-mix(in srgb, var(--sakura) 55%, transparent));
  color: var(--error);
}
.error-msg {
  color: var(--error);
  margin: var(--space-2) 0;
}
/* 「有题没生成解析」用暖色警示：题目本身可用，所以不是 error（红），
   只是让用户注意到"这批没解析全"。颜色走 token，暗色模式下自动跟着变 */
.ai-warn {
  margin: var(--space-2) 0;
  color: var(--warning);
}
.result-actions {
  display: flex;
  justify-content: center;
  gap: var(--space-3);
  margin-top: var(--space-5);
}
</style>
