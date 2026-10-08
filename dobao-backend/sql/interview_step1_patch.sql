-- ============================================================================
-- 面试总结功能 · 步骤 1 补丁（把「按最初方案建好的库」对齐到当前设计）
--
-- 适用场景：你的库已按最初方案建过 ai_interview / ai_interview_segment。
--
-- !! 全新环境请勿执行本文件 !!
--    请直接用 sql/ai_db.sql 里的最终 DDL（只含 ai_interview，且已包含以下全部修正），
--    否则会报 Duplicate column name 'audio_hash'。
--
-- 本补丁做三件事：
--   ① 删除 ai_interview_segment 表 —— 设计变更，理由见下
--   ② ai_interview 增加 audio_hash 列 + 索引 —— 步骤 6「同一音频重复提交复用结果」的幂等键
--   ③ create_time / update_time 补默认值与自动更新 —— 与项目其他表风格一致
--
-- ① 为什么删掉 ai_interview_segment：
--    该表存的是「一句话一行」的转写明细（speaker_id / begin_ms / end_ms / text），
--    而这些数据 100% 已存在于 ai_interview.transcript_json（ASR 原始结果）中，
--    解析一次即可在内存里得到同样的句子列表。它唯一独有的字段 speaker_role 也可由
--      speaker_role = (speaker_id == interviewer_speaker_id) ? interviewer : candidate
--    推导出来（主表已存 interviewer_speaker_id），因此不构成独立信息。
--    实测体量：5.5 分钟音频 72 句、原始 JSON 79,842 字节，其中 81% 是逐字级 words[]
--    （功能未使用）；句子级投影仅 15,023 字节，折算 40 分钟约 0.1MB。
--    结论：这张表是「对最没有独立价值的一层做规范化」。删掉它不损失任何能力——
--          步骤 3/4 直接解析 transcript_json 即可（见编码方案 §4 步骤 2/4）。
--    若将来确实需要按结构化数据检索（例如「我以前被问过哪些题」），
--    应该新增的是「问答对表」（question / answer / topics），而不是句子表。
--
-- 已被评估但刻意不做的改动（保持与冻结规格一致）：
--   - error_msg 改 TEXT（规格为 VARCHAR(1024)）
--   - transcript_json / report_json 改 LONGTEXT（规格为 JSON；MySQL 5.7 下已验证可用）
--   - ai_interview.file_id 改 VARCHAR(255)（规格为 VARCHAR(64)，UUID 36 字符够用）
-- ============================================================================

-- ① 删除转写稿片段表（设计变更）
DROP TABLE IF EXISTS `ai_interview_segment`;

-- ② 补 audio_hash（音频内容 SHA-256，幂等去重用）
--    注意：不做成唯一索引——同一音频允许作为两次面试分别留存，靠查哈希提示复用即可。
ALTER TABLE `ai_interview`
  ADD COLUMN `audio_hash` CHAR(64) DEFAULT NULL COMMENT '音频内容 SHA-256，幂等去重用' AFTER `file_id`,
  ADD KEY `idx_audio_hash` (`audio_hash`);

-- ③ 时间列补默认值与自动更新（与 ai_file_info / ai_session 等表风格一致）
ALTER TABLE `ai_interview`
  MODIFY COLUMN `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
