<script setup lang="ts">
import type { Message } from '@/types'
import { renderMarkdown } from '@/utils/markdown'

defineProps<{
  msg: Message
  isSending: boolean
  isLast: boolean
}>()

defineEmits<{
  (e: 'copy', msg: Message): void
  (e: 'toggle-thinking', msgId: string): void
  (e: 'toggle-reference', msgId: string): void
  (e: 'send-recommend', question: string): void
}>()
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

        <!-- 文本内容 -->
        <div class="text-content markdown-body" v-html="renderMarkdown(msg.content)"></div>

        <!-- 加载提示：渐变流光文字 -->
        <div v-if="isSending && isLast" class="thinking-loading">探索中莫着急~</div>

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
