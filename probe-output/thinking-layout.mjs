/**
 * One-off probe (not part of the app): renders the exact thinking text that
 * PlanExecuteAgent emits, through dobao-front's real marked config, twice:
 *   LEFT  = the CSS before the fix (white-space: pre-wrap)
 *   RIGHT = the CSS after the fix (white-space: normal + edge margins)
 * Prints the page to stdout so it can be written to an html file and measured
 * in a headless browser via --dump-dom / CDP.
 */
import { readFileSync } from 'node:fs'
const frontDir = new URL('../dobao-front/', import.meta.url)
// marked is resolved from dobao-front/node_modules via an absolute file URL:
// this script deliberately lives outside the frontend package.
const { Marked } = await import(new URL('node_modules/marked/lib/marked.esm.js', frontDir).href)
const appCss = readFileSync(new URL('src/style.css', frontDir), 'utf8')

// Same config as dobao-front/src/utils/markdown.ts (highlight plugin omitted:
// no code fences in this sample).
const marked = new Marked()
marked.setOptions({ breaks: true, gfm: true })

// Reconstructed from PlanExecuteAgent's emitted chunks for the screenshot's run.
const sample =
  '\n[开始研究]\n研究方向：剖析Redis单线程说法的历史背景、底层事件循环机制、网络I/O处理逻辑，以及Redis 7+版本的多核线程演进与实际性能表现。\n\n' +
  '✅ 需求分析完成\n' +
  '✅ 信息充足，准备生成研究主题\n' +
  '📝 正在生成研究主题...\n\n' +
  '1. Redis单线程概念的历史起源与核心定义\n\n' +
  '2. 现代Redis版本的单线程模型演变（如6.0多线程I/O的引入与实际分工）\n\n' +
  '3. 主线程处理流程详解（网络I/O、命令解析、数据操作、事件循环）\n\n' +
  '4. 单线程设计的性能优势与潜在瓶颈分析\n\n' +
  '5. 该架构对开发者日常使用与系统调优的实际指导意义\n' +
  '✅ 研究主题已生成\n' +
  '🔄 第 1 轮研究开始\n' +
  '📋 正在生成执行计划...\n\n'

const html = marked.parse(sample)

// The rejected backend patch (PlanExecuteAgent.trimBlankTail, reverted): it
// stripped every thinking chunk's trailing newline. Model chunks do not end in
// a newline, so consecutive chunks got glued together into one line.
const chunkEnded = sample.split(/(?<=。|\n)/)
const glued = chunkEnded.map((c) => c.replace(/[\r\n]+$/, '')).join('')
const htmlNormalized = marked.parse(glued)

const page = `<!doctype html>
<html lang="zh"><head><meta charset="utf-8"><title>thinking layout probe</title>
<style>
${appCss}
body { height: auto; overflow: visible; padding: 20px; }
.panels { display: flex; gap: 24px; align-items: flex-start; }
.panel { width: 420px; background: #1a1a1a; padding: 12px; }
.panel-title { font-size: 13px; color: #f8fafc; margin-bottom: 8px; }
/* Simulate the PRE-FIX rule exactly (style.css used to say pre-wrap here).
   The post-fix panel uses the real stylesheet untouched, so it exercises the
   actual fixed CSS rather than a copy of it. */
.panel.current .thinking-text { white-space: pre-wrap; }
</style></head>
<body>
<div class="panels">
  <div class="panel current">
    <div class="panel-title">CURRENT: white-space: pre-wrap</div>
    <div class="ai-message"><div class="thinking-section"><div class="thinking-content">
      <div class="thinking-text markdown-body" id="current">${html}</div>
    </div></div></div>
  </div>
  <div class="panel fixed">
    <div class="panel-title">FINAL: CSS fix only (backend reverted)</div>
    <div class="ai-message"><div class="thinking-section"><div class="thinking-content">
      <div class="thinking-text markdown-body" id="fixed">${html}</div>
    </div></div></div>
  </div>
  <div class="panel fixed-norm">
    <div class="panel-title">REJECTED: trimBlankTail (lines glued together)</div>
    <div class="ai-message"><div class="thinking-section"><div class="thinking-content">
      <div class="thinking-text markdown-body" id="fixedNorm">${htmlNormalized}</div>
    </div></div></div>
  </div>
</div>
</body></html>`

process.stdout.write(page)
