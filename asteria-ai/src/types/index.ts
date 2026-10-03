/* ============================================================
   与 generate-api-doc.ps1 生成的《Asteria AI 接口文档》一致的
   TS 类型定义（对应后端 /api 前缀接口）
   ============================================================ */

/* ── 通用 ── */

/** 统一响应结构 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

/** 分页响应 data */
export interface PageData<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

export type QuestionType = 'SINGLE' | 'MULTIPLE' | 'TRUE_FALSE' | 'ESSAY' | 'FILL_BLANK'
/** ALL 仅用于刷题请求 */
export type QuestionTypeWithAll = QuestionType | 'ALL'
export type PracticeMode = 'SEQUENTIAL' | 'RANDOM'
export type SessionStatus = 'IN_PROGRESS' | 'COMPLETED'
export type UploadStatus = 'PENDING' | 'PARSING' | 'AI_FORMATTING' | 'AI_PROCESSING' | 'SUCCESS' | 'FAILED'
export type AgentMode = 'BUILTIN' | 'EXTERNAL'
export type MessageRole = 'user' | 'assistant' | 'system'

/* ── 3.1 题库与导入 ── */

export interface ImportTaskCreated {
  taskId: string
  status: UploadStatus
}

export interface ImportTask {
  taskId: string
  status: UploadStatus
  fileName: string
  fileSize: number
  progress: number
  bankId: number | null
  totalCount: number
  errorMessage: string | null
  /** AI 解析已完成的题数（后端实时统计，未入库的任务为 null） */
  aiDoneCount?: number | null
  /** 还没解析的题数（不含失败的那些） */
  aiPendingCount?: number | null
  /** 解析失败的题数（成功卡片靠它决定要不要提示"有题没生成解析"） */
  aiFailedCount?: number | null
}

export interface Bank {
  id: number
  name: string
  fileName: string
  fileType: string
  questionCount: number
  singleCount: number
  multipleCount: number
  trueFalseCount: number
  essayCount: number
  fillBlankCount: number
  chapterCount: number
  createdAt: string
}

export interface Chapter {
  id: number
  bankId: number
  name: string
  sort: number
  questionCount: number
}

export interface BankDetail extends Bank {
  chapters: Chapter[]
  wrongCount: number
}

export interface BankQuery {
  keyword?: string
  page?: number
  pageSize?: number
}

/* ── 3.2 题目 ── */

export interface QuestionOption {
  key: string
  text: string
}

export interface Question {
  id: number
  bankId: number
  chapterId: number
  chapterName: string
  type: QuestionType
  stem: string
  options: QuestionOption[] | null
  answer: string
  analysis: string
  knowledgePoints: string[]
}

/** 刷题会话中的题目（不含 answer/analysis，防偷看） */
export type PracticeQuestion = Omit<Question, 'answer' | 'analysis'>

export interface QuestionQuery {
  chapterId?: number
  type?: QuestionType
  keyword?: string
  page?: number
  pageSize?: number
}

/* ── 3.3 刷题 ── */

export interface PracticeSession {
  id: number
  bankId: number
  mode: PracticeMode
  questionType: QuestionTypeWithAll
  chapterId: number | null
  status: SessionStatus
  totalCount: number
  answeredCount: number
  correctCount: number
}

export interface PracticeSessionCreateReq {
  bankId: number
  mode: PracticeMode
  questionType: QuestionTypeWithAll
  chapterId: number | null
}

export interface AnswerRecord {
  questionId: number
  userAnswer: string
  isCorrect: boolean | null
}

export interface PracticeSessionDetail extends PracticeSession {
  questions: PracticeQuestion[]
  records: AnswerRecord[]
}

export interface AnswerSubmitReq {
  questionId: number
  userAnswer: string
}

export interface AnswerSubmitResult {
  questionId: number
  userAnswer: string
  isCorrect: boolean | null
  answer: string
  analysis: string
  answeredCount: number
  correctCount: number
  sessionStatus: SessionStatus
}

export interface TypeStat {
  type: QuestionType
  total: number
  correct: number
}

export interface WrongQuestionItem {
  questionId: number
  stem: string
  type: QuestionType
  userAnswer: string
  answer: string
}

export interface SessionResult {
  sessionId: number
  totalCount: number
  answeredCount: number
  correctCount: number
  accuracy: number
  typeStats: TypeStat[]
  wrongQuestions: WrongQuestionItem[]
}

export interface WrongStats {
  bankId: number
  wrongTotal: number
  byType: { type: QuestionType; count: number }[]
  byChapter: { chapterId: number; chapterName: string; count: number }[]
}

/** GET /practice/stats — 学习统计（首页统计卡） */
export interface StudyStats {
  /** 已刷题：累计作答次数（同一题重做也计数） */
  practiced: number
  /** 总体正确率：0~100 的整数 */
  accuracy: number
}

/** GET /practice/sessions?limit=5 — 最近刷题列表（首页卡片） */
export interface RecentSession {
  id: number
  bankId: number
  bankName: string
  mode: PracticeMode
  status: SessionStatus
  totalCount: number
  answeredCount: number
  correctCount: number
  /** ISO 字符串 */
  createdAt: string
}

/* ── 3.4 AI 聊天 ── */

export interface ChatSession {
  id: number
  title: string
  agentMode: AgentMode
  messageCount: number
  updatedAt: string
}

export interface ChatSessionCreateReq {
  title?: string
  agentMode?: AgentMode
}

export interface ChatMessage {
  id: number
  sessionId: number
  role: MessageRole
  content: string
  createdAt: string
}

export interface ChatMessageSendReq {
  content: string
  agentMode?: AgentMode
}

/** SSE chunk 事件 data */
export interface SseChunkData {
  delta: string
}

/** SSE done 事件 data */
export interface SseDoneData {
  messageId: number
  content: string
  interrupted?: boolean
}

/** SSE error 事件 data */
export interface SseErrorData {
  code: number
  message: string
}

/* ── 3.5 知识点总结 ── */

export interface KnowledgeSummary {
  id: number
  bankId: number
  bankName: string
  highlights: string[]
  keyPoints: string[]
  hotTopics: string[]
  easyMistakes: string[]
  studySuggestions: string[]
  generatedAt: string
  fromCache: boolean
}

export interface KnowledgeSummaryReq {
  bankId: number
  force: boolean
}

/* ── 3.6 配置 ── */

export interface ExamCountdown {
  /** 后端主键（CRUD 需要；字段名联调时确认） */
  id: number
  name: string
  date: string
  daysLeft: number
  level: 'red' | 'orange' | 'green'
}

export interface ExamCountdownCreateReq {
  name: string
  /** YYYY-MM-DD */
  date: string
}

export interface ExamCountdownUpdateReq {
  name?: string
  date?: string
}

/* ── AI 配置测试 ── */

/** POST /ai/test — AI 连通性测试结果 */
export interface AiTestResult {
  /** 是否连通 */
  ok: boolean
  /** 原因：成功时是"连接正常"；失败时说明具体原因（如"API Key 无效（HTTP 401）"） */
  message: string
}
