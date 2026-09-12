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
    <div class="message-avatar">
      {{ msg.role === 'user' ? '👤' : '🤖' }}
    </div>
    <div class="message-content">
      <!-- 用户消息 -->
      <div v-if="msg.role === 'user'" class="user-message">
        <span v-if="msg.file" class="file-attachment">
          <i class="fas fa-paperclip"></i>
          {{ msg.fileName }}
        </span>
        <div>{{ msg.content }}</div>
      </div>

      <!-- Copy 按钮 -->
      <div v-if="msg.role === 'user'" class="copy-btn copy-btn-user" @click="$emit('copy', msg)">
        <i v-if="!msg.copied" class="fas fa-copy"></i>
        <i v-else class="fas fa-check"></i>
      </div>

      <!-- AI 消息 -->
      <div v-else class="ai-message">
        <!-- 思考过程 -->
        <div v-show="msg.hasThinking" class="thinking-section">
          <div class="thinking-header" @click="$emit('toggle-thinking', msg.id)">
            <div class="thinking-icon-wrapper">
              <i class="fas fa-brain thinking-icon"></i>
            </div>
            <span class="thinking-title">思考过程</span>
            <i :class="['fas', msg.showThinking ? 'fa-chevron-down' : 'fa-chevron-right']"></i>
          </div>
          <div v-show="msg.showThinking" class="thinking-content">
            <div class="thinking-text" v-html="renderMarkdown(msg.thinking.join(''))"></div>
          </div>
        </div>

        <!-- 文本内容 -->
        <div class="text-content markdown-body" v-html="renderMarkdown(msg.content)"></div>

        <!-- 加载动画 -->
        <div v-if="isSending && isLast" class="thinking-loading">
          <span class="dot"></span>
          <span class="dot"></span>
          <span class="dot"></span>
        </div>

        <!-- 参考链接 -->
        <div v-if="msg.reference && msg.reference.length > 0" class="reference-section">
          <div class="reference-header" @click="$emit('toggle-reference', msg.id)">
            <div class="reference-icon-wrapper">
              <i class="fas fa-book reference-icon"></i>
            </div>
            <span class="reference-title">参考来源 ({{ msg.reference.length }})</span>
            <i :class="['fas', msg.showReference ? 'fa-chevron-down' : 'fa-chevron-right']"></i>
          </div>
          <div v-show="msg.showReference" class="reference-content">
            <template v-for="(ref, rIndex) in msg.reference" :key="rIndex">
              <a v-if="ref && ref.url" :href="ref.url" target="_blank" class="reference-link">
                <div class="ref-icon">
                  <i class="fas fa-external-link-alt"></i>
                </div>
                <div class="ref-info">
                  <div class="ref-title-text">{{ ref.title || '无标题' }}</div>
                  <div class="ref-url-text">{{ ref.url }}</div>
                </div>
              </a>
            </template>
          </div>
        </div>

        <!-- 推荐问题 -->
        <div v-if="msg.recommend && msg.recommend.length > 0" class="recommend-section">
          <div class="recommend-items">
            <div
              v-for="(question, qIndex) in msg.recommend"
              :key="qIndex"
              class="recommend-item"
              @click="$emit('send-recommend', question)"
            >
              <div class="recommend-icon">
                <i class="fas fa-lightbulb"></i>
              </div>
              <div class="recommend-text">{{ question }}</div>
              <div class="recommend-arrow">
                <i class="fas fa-arrow-right"></i>
              </div>
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