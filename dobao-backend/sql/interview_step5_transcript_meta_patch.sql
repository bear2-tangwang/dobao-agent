-- ============================================================================
-- 面试总结功能 · 补丁：把「句子数 / 说话人数」落到列上
-- ============================================================================
-- 适用场景：库已经建好 ai_interview（含 audio_hash、无 ai_interview_segment）。
-- 全新环境不要执行本文件：直接跑 sql/ai_db.sql 即可（那里已包含这两列）。
--
-- 【为什么加这两列】
--
-- 起因是一次真实回归：上传 20 分钟音频后，控制台每 3~5 秒就刷一行
--     d.d.interview.TranscriptNormalizer : 转写稿归一化完成: 句子数=259, ...
-- 查下去发现 GET /interview/{id}/status 为了拿"句子数 / 说话人数"，
-- **每次请求都把整份 transcript_json 重新反序列化一遍**
-- （实测 5.5 分钟音频的原始 JSON 约 80KB，其中 81% 是用不到的逐字 words[]）。
--
-- 这在真机上的后果：
--   1. 前端从上传开始轮询状态，整场几分钟里要做几十次全量 JSON 解析；
--   2. 每次解析还打一条 INFO 日志，把日志刷满，看起来像"卡住了"；
--   3. 这是纯粹的浪费——这两个数字在转写落库那一刻就已经知道了。
--
-- 处理方式：转写落库时顺手写进这两列，status 查询直接读列，
-- **状态查询彻底不再触碰 JSON**。
--
-- 【幂等】
-- MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 Duplicate column name。
-- 先查再改：
--   SELECT COUNT(*) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_interview'
--      AND COLUMN_NAME IN ('sentence_count','speaker_count');
-- 返回 0 才执行下面的语句。
-- ============================================================================

ALTER TABLE `ai_interview`
  ADD COLUMN `sentence_count` int DEFAULT NULL COMMENT '转写句子数（归一化时写入，供状态查询免解析 JSON）' AFTER `transcript_text`,
  ADD COLUMN `speaker_count` int DEFAULT NULL COMMENT '识别出的说话人数（同上）' AFTER `sentence_count`;

-- 存量数据回填（可选）：把已经 TRANSCRIBED 以上的记录补上这两个数。
-- 注意：这里只能用 JSON 函数估算，无法完全等价于 TranscriptNormalizer 的口径
-- （它会把所有 transcripts[].sentences[] 拉平，并且 sentence_id 缺失时有兜底）。
-- 所以下面这段只作为"让老数据的状态接口不为 null"的兜底，新数据一律由应用写入。
--
-- UPDATE `ai_interview`
--    SET `sentence_count` = JSON_LENGTH(`transcript_json` -> '$.transcripts[0].sentences'),
--        `speaker_count`  = 2
--  WHERE `transcript_json` IS NOT NULL
--    AND `sentence_count` IS NULL;
