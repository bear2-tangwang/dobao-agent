<script setup lang="ts">
import { computed } from 'vue'
import type { Message, InterviewSession } from '@/types'
import { renderMarkdown } from '@/utils/markdown'
import { reportToMarkdown } from '@/composables/useInterview'
import InterviewPanel from '@/components/InterviewPanel.vue'

const props = defineProps<{
  msg: Message
  isSending: boolean
  isLast: boolean
  /** 面试模式：是否处于处理中（面板里显示转圈与提示） */
  interviewProcessing?: boolean
  /** 面试模式：是否正在提交/加载（禁用按钮） */
  interviewLoading?: boolean
  /** 面试模式：合规勾选状态 */
  interviewAgreed?: boolean
  /** 面试模式：已选中但未提交的录音文件名 */
  interviewPendingFileName?: string | null
}>()

const emit = defineEmits<{
  (e: 'copy', msg: Message): void
  (e: 'toggle-thinking', msgId: string): void
  (e: 'toggle-reference', msgId: string): void
  (e: 'send-recommend', question: string): void
  (e: 'interview-toggle-agree'): void
  (e: 'interview-pick-file'): void
  (e: 'interview-start'): void
  /** 重试某一场的面试：带上这一条消息自己的 session（一个会话里可能有多场） */
  (e: 'interview-retry', session: InterviewSession): void
  /** 重新载入某一场的报告 */
  (e: 'interview-refresh-report', session: InterviewSession): void
}>()

/** 面试报告的结构化数据渲染成 Markdown（与后端 report 的 Markdown 同格式） */
const reportMarkdown = computed(() =>
  props.msg.interview?.report ? reportToMarkdown(props.msg.interview.report) : ''
)

/** 转发面板事件：有待提交录音时"选择文件"=重新选，没有时=开始总结 */
const onPickFile = () => {
  if (props.interviewPendingFileName) {
    emit('interview-pick-file')
  } else {
    emit('interview-start')
  }
}
</script>

<template>
  <div :class="['message', msg.role]">
    <div class="message-content">
      <!-- 用户消息（右对齐，无头像） -->
      <div v-if="msg.role === 'user'" class="user-message">
        <span v-if="msg.file" class="file-attachment">
          <i class="fas fa-paperclip"></i>
          {{ msg.fileName }}
        </span>
        <div>{{ msg.content }}</div>
      </div>

      <!-- Copy 按钮（用户消息气泡右下方） -->
      <div v-if="msg.role === 'user'" class="copy-btn copy-btn-user" @click="$emit('copy', msg)">
        <i v-if="!msg.copied" class="fas fa-copy"></i>
        <i v-else class="fas fa-check"></i>
      </div>

      <!-- AI 消息（左对齐，无头像） -->
      <div v-else class="ai-message">
        <!-- 思考过程：纯文本折叠行，默认不展开 -->
        <div v-show="msg.hasThinking" class="thinking-section">
          <div class="thinking-header" @click="$emit('toggle-thinking', msg.id)">
            <span class="thinking-title">思考过程</span>
            <i :class="['fas', 'fa-chevron-right', 'collapse-arrow', { 'is-open': msg.showThinking }]"></i>
          </div>
          <div v-show="msg.showThinking" class="thinking-content">
            <div class="thinking-text" v-html="renderMarkdown(msg.thinking.join(''))"></div>
          </div>
        </div>

        <!-- 面试总结：进度面板 + 报告 -->
        <template v-if="msg.interview">
          <InterviewPanel
            :session="msg.interview"
            :processing="!!interviewProcessing"
            :loading="!!interviewLoading"
            :agreed="!!interviewAgreed"
            :pending-file-name="interviewPendingFileName || null"
            @toggle-agree="$emit('interview-toggle-agree')"
            @pick-file="onPickFile"
            @start="$emit('interview-start')"
            @retry="$emit('interview-retry', msg.interview)"
            @refresh-report="$emit('interview-refresh-report', msg.interview)"
          />
          <div v-if="reportMarkdown" class="text-content markdown-body interview-report" v-html="renderMarkdown(reportMarkdown)"></div>
          <div v-if="msg.interview.downloadUrl" class="ppt-download">
            <a :href="msg.interview.downloadUrl" class="ppt-link">
              <i class="fas fa-download"></i>
              下载面试总结报告（Markdown）
            </a>
          </div>
        </template>

        <!-- 文本内容 -->
        <div v-else class="text-content markdown-body" v-html="renderMarkdown(msg.content)"></div>

        <!-- 加载提示：渐变流光文字 -->
        <div v-if="isSending && isLast && !msg.interview" class="thinking-loading">探索中莫着急~</div>

        <!-- 参考来源：纯文本折叠行，默认不展开 -->
        <div v-if="msg.reference && msg.reference.length > 0" class="reference-section">
          <div class="reference-header" @click="$emit('toggle-reference', msg.id)">
            <span class="reference-title">参考 {{ msg.reference.length }} 篇资料</span>
            <i :class="['fas', 'fa-chevron-right', 'collapse-arrow', { 'is-open': msg.showReference }]"></i>
          </div>
          <div v-show="msg.showReference" class="reference-content">
            <template v-for="(ref, rIndex) in msg.reference" :key="rIndex">
              <a v-if="ref && ref.url" :href="ref.url" target="_blank" class="reference-link">
                <div class="ref-title-text">{{ ref.title || '无标题' }}</div>
                <div class="ref-url-text">{{ ref.url }}</div>
              </a>
            </template>
          </div>
        </div>

        <!-- 推荐问题：简洁文字胶囊 -->
        <div v-if="msg.recommend && msg.recommend.length > 0" class="recommend-section">
          <div class="recommend-items">
            <div
              v-for="(question, qIndex) in msg.recommend"
              :key="qIndex"
              class="recommend-item"
              @click="$emit('send-recommend', question)"
            >
              <div class="recommend-text">{{ question }}</div>
              <i class="fas fa-arrow-right recommend-arrow"></i>
            </div>
          </div>
        </div>

        <!-- PPT下载 -->
        <div v-if="msg.pptFile" class="ppt-download">
          <a :href="msg.pptFile" download class="ppt-link">
            <i class="fas fa-download"></i>
            下载生成的PPT
          </a>
        </div>

        <!-- Copy 按钮 -->
        <div class="copy-btn" @click="$emit('copy', msg)">
          <i v-if="!msg.copied" class="fas fa-copy"></i>
          <i v-else class="fas fa-check"></i>
        </div>
      </div>
    </div>
  </div>
</template>

