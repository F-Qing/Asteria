-- ============================================================
-- 增量迁移：补 practice_record 的 (session_id, is_correct) 索引
--
-- 前置：practice_tables.sql 已执行
-- 用法：mysql -uroot -p finaltext < practice_record_index.sql
-- ⚠️ 可重复执行：先查 information_schema，索引已存在就跳过
--
-- ------------------------------------------------------------
-- 为什么需要这个索引
-- ------------------------------------------------------------
-- 答题时有两处查询会按 (session_id, is_correct) 过滤，而且**每提交一题就跑一遍**：
--
--   ① PracticeSessionMapper.refreshCounts —— 重算会话的已答/答对数
--        SELECT COUNT(*) FROM practice_record WHERE session_id = ps.id
--        SELECT COUNT(*) FROM practice_record WHERE session_id = ps.id AND is_correct = 1
--   ② PracticeRecordMapper.selectWrongItems —— 查本次会话的错题明细
--        ... WHERE r.session_id = ? AND r.is_correct = 0
--
-- 原有的索引是 uk_pr_session_question (session_id, question_id)：
--   它能用上 session_id 前缀，但 **is_correct 不在索引里** ——
--   所以要把该会话的全部记录逐行取回来再过滤 is_correct。
--
-- 一个 500 题的会话，每答一题扫 500 行，整个会话累计约 12.5 万行；
-- 加上 (session_id, is_correct) 之后，数据库能直接跳到匹配的索引项，
-- 不再逐行回表判断。
--
-- 列顺序 (session_id, is_correct) 是有讲究的：
--   session_id 放前面 —— 它是等值过滤且区分度高（一个会话的记录聚在一起）；
--   is_correct 放后面 —— 用来在会话内部继续收窄。
-- ============================================================

USE finaltext;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'practice_record'
    AND INDEX_NAME = 'idx_pr_session_correct'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE practice_record ADD KEY idx_pr_session_correct (session_id, is_correct)',
  'SELECT ''idx_pr_session_correct 已存在，跳过'' AS skipped');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 说明：以下索引**经核对后决定不加**，不是遗漏
--
--   question.bank_id
--     已有 idx_question_bank_chapter(bank_id, chapter_id) 和
--     idx_question_bank_type(bank_id, type)，按最左前缀 bank_id 就能走索引。
--     再单加一个 bank_id 只会多占空间、拖慢写入。
--
--   import_task 的 status / created_at
--     任务只按主键 task_id 查（进度轮询），不按状态扫表。
--
--   chat_session.title / message_count
--     区分度极低，且没有查询按它们过滤。
--
--   practice_record.is_correct 单列
--     单独一个布尔列区分度只有 2，没意义；必须和 session_id 组合才有用（就是上面加的那个）。
-- ------------------------------------------------------------

-- 验证
SHOW INDEX FROM practice_record;
