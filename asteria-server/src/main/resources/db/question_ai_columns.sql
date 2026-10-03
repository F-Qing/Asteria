-- ============================================================
-- 增量迁移：给 question 表加「AI 解析状态」与「答案来源」两组列
--
-- 用途：
--   1. 让 AI 解析**能断点续跑** —— 崩溃/中止后只重跑没处理完的题，
--      不用把已经花钱解析过的题再跑一遍（LLM 调用不是幂等的，重跑真的要再付一次钱）
--   2. 让答案**有出处** —— 区分「文件里原本就有」和「AI 推出来的」，
--      将来做人工复核、回滚、统计「多少题靠 AI 补的」都要靠它
--
-- 前置：question 表已存在（bank_tables.sql）
-- 用法：mysql -uroot -p finaltext < question_ai_columns.sql
--
-- ⚠️ 本脚本可重复执行：先判断列是否存在，不存在才加（MySQL 的
--    ADD COLUMN IF NOT EXISTS 只在 8.0.29+ 支持，这里用 information_schema
--    判断，兼容更老的 8.0 版本，也避免"重复执行报 1060 列名重复"）
-- ============================================================

USE finaltext;

-- ------------------------------------------------------------
-- 1. ai_status：这道题的 AI 解析走到哪一步了
--    PENDING  还没解析过（新导入的题默认值）
--    DONE     解析成功
--    FAILED   解析失败（原因在 ai_error 里）
--    断点续跑只捞 PENDING 和 FAILED
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'ai_status'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE question ADD COLUMN ai_status VARCHAR(20) NOT NULL DEFAULT ''PENDING''
     COMMENT ''AI解析状态：PENDING未解析 / DONE成功 / FAILED失败（断点续跑只捞非DONE的）''',
  'SELECT ''ai_status 已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. ai_error：失败原因（截断到 500 字，和 error_message 一个规格）
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'ai_error'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE question ADD COLUMN ai_error VARCHAR(500) DEFAULT NULL
     COMMENT ''AI解析失败原因（已脱敏，可能被截断）''',
  'SELECT ''ai_error 已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. ai_retry_count：重试过几次
--    用来识别"一直失败"的题 —— 重试 N 次仍失败就别再试了，别无限烧钱
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'ai_retry_count'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE question ADD COLUMN ai_retry_count INT NOT NULL DEFAULT 0
     COMMENT ''AI解析重试次数：用来识别一直失败的题，避免无限重试烧钱''',
  'SELECT ''ai_retry_count 已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4. answer_source：这个答案是谁写的
--    FILE   从原文里解析出来的（可信度最高）
--    AI     模型推出来的（原本缺答案时）
--    MANUAL 人工改过的
--    ★ 这是"数据血缘"：AI 写的答案必须能被区分出来，
--      否则将来想复核/回滚，会发现原始数据已经被覆盖、无从追溯
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'answer_source'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE question ADD COLUMN answer_source VARCHAR(20) NOT NULL DEFAULT ''FILE''
     COMMENT ''答案来源：FILE原文解析 / AI模型推断 / MANUAL人工修改''',
  'SELECT ''answer_source 已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 5. ai_enriched_at：AI 补解析的时间
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND COLUMN_NAME = 'ai_enriched_at'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE question ADD COLUMN ai_enriched_at DATETIME DEFAULT NULL
     COMMENT ''AI补解析完成时间''',
  'SELECT ''ai_enriched_at 已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 6. 索引：断点续跑要按 (bank_id, ai_status) 捞"还没解析的题"
-- ------------------------------------------------------------
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question' AND INDEX_NAME = 'idx_question_bank_ai_status'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE question ADD KEY idx_question_bank_ai_status (bank_id, ai_status)',
  'SELECT ''idx_question_bank_ai_status 已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 存量数据处理：老数据在加列时就自动填了默认值
--   ai_status     = PENDING  → 会被断点续跑捞出来（合理：它们确实没解析过）
--   answer_source = FILE     → 加列之前的答案都来自原文，标签是对的
-- 不用额外 UPDATE。
-- ------------------------------------------------------------

-- 验证：看看加完的结果
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'question'
  AND COLUMN_NAME IN ('ai_status','ai_error','ai_retry_count','answer_source','ai_enriched_at')
ORDER BY ORDINAL_POSITION;
