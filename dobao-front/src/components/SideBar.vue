<script setup lang="ts">
import type { Chat } from '@/types'

defineProps<{
  chatList: Chat[]
  currentChatId: string | null
  backendUrl: string
}>()

defineEmits<{
  (e: 'create-new-chat'): void
  (e: 'select-chat', chatId: string): void
  (e: 'delete-chat', chatId: string): void
}>()
</script>

<template>
  <div class="sidebar">
    <div class="sidebar-header">
      <div class="app-title">
        <span class="logo-icon">🌱</span>
        <span class="title-text">豆豆</span>
      </div>
      <button class="new-chat-btn" @click="$emit('create-new-chat')">
        <i class="fas fa-plus"></i>
        <span>新对话</span>
      </button>
    </div>
    <div class="chat-list">
      <div
        v-for="chat in chatList"
        :key="chat.id"
        :class="['chat-item', { active: currentChatId === chat.id }]"
        @click="$emit('select-chat', chat.id)"
      >
        <span class="chat-title">{{ chat.title }}</span>
        <span v-if="!chat.isNew" class="delete-btn" @click.stop="$emit('delete-chat', chat.id)">
          <i class="fas fa-trash-alt"></i>
        </span>
      </div>
    </div>
    <div class="sidebar-footer">
      <div class="model-info">
        <i class="fas fa-link"></i>
        <span>{{ backendUrl }}</span>
      </div>
    </div>
  </div>
</template>