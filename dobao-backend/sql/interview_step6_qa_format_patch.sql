-- ============================================================================
-- 面试总结功能 · 补丁：问答清单去 LLM 化重构（2026-10）
-- ============================================================================
-- 适用场景：库里已经有历史面试记录（report_json 是"分块抽取"时代的旧结构）。
-- 本文件**不改表结构**，也不需要改 —— 它只做两件事：
--   ① 列出需要重新生成报告的历史记录；
--   ② 说明为什么必须重新生成。
--
-- 【为什么需要这个文件】
--
-- 2026-10 重构把问答清单从"分块并发 LLM 抽取"改成"由转写句子列表直接格式化"，
-- report_json 的结构随之变化：
--
--   旧 qaList 元素（QaPair）：
--     { qaId, question, questionBeginMs, answerSummary, answerQuotes[], answerBeginMs, answerEndMs, topics[] }
--   新 qaList 元素（QaItem）：
--     { qaId, question, questionBeginMs, answer, answerBeginMs, answerEndMs }
--
-- 同时 InterviewReport 删除了 failedChunks / skippedChunks 两个顶层字段。
--
-- report_json 是 MySQL JSON 列，**新报告写入即生效，但老记录里的旧 JSON 不会自动迁移**：
-- 前端 reportToMarkdown 读不到 answer 字段，问答清单会渲染成 "-"（后端 /report 与
-- 下载接口同样如此）。表现为"报告能打开，但每条问答的回答都是 -"。
--
-- 【处理方式：重新生成报告，不要重新转写】
--
-- 对下面查出来的每条记录执行一次（**复用已落库的 transcript_json，不重新转写、不重复计费**）：
--
--     POST /interview/{interviewId}/retry
--
-- （v1.8 起原 POST /interview/{interviewId}/analyze 已并入 /retry：有文字稿时它只重跑分析）
--
-- 重新生成后 report_json 即为新结构；音频与文字稿都不动。
--
-- 【幂等 / 安全性】
-- 下面只有 SELECT，重复执行无副作用。
-- ============================================================================

-- ① 需要重新生成报告的历史记录（旧结构特征：qaList 里带 answerSummary）
SELECT `interview_id`,
       `status`,
       `sentence_count`,
       JSON_LENGTH(`report_json` -> '$.qaList') AS qa_count,
       `update_time`
  FROM `ai_interview`
 WHERE `report_json` IS NOT NULL
   AND JSON_EXTRACT(`report_json`, '$.qaList[0].answerSummary') IS NOT NULL
 ORDER BY `update_time` DESC;

-- ② 顺带看一眼顶层是否还残留分块字段（旧记录都会有）
-- SELECT `interview_id`,
--        JSON_EXTRACT(`report_json` -> '$.failedChunks')   AS failed_chunks,
--        JSON_EXTRACT(`report_json` -> '$.skippedChunks')  AS skipped_chunks
--   FROM `ai_interview`
--  WHERE `report_json` IS NOT NULL
--    AND (JSON_EXTRACT(`report_json`, '$.failedChunks') IS NOT NULL
--         OR JSON_EXTRACT(`report_json`, '$.skippedChunks') IS NOT NULL);

-- ③ 总览：新旧结构各有多少条
-- SELECT SUM(JSON_EXTRACT(`report_json`, '$.qaList[0].answerSummary') IS NOT NULL) AS legacy_reports,
--        SUM(JSON_EXTRACT(`report_json`, '$.qaList[0].answer')        IS NOT NULL) AS new_reports
--   FROM `ai_interview`
--  WHERE `report_json` IS NOT NULL;
