<script setup lang="ts">
import { ref } from 'vue'
import type { Chat } from '@/types'
import BrandMark from '@/components/BrandMark.vue'

defineProps<{
  chatList: Chat[]
  currentChatId: string | null
  /** 当前登录用户（未登录时传 null，不渲染用户区） */
  user?: {
    nickname: string | null
    login: string | null
    avatarUrl: string | null
  } | null
}>()

defineEmits<{
  (e: 'create-new-chat'): void
  (e: 'select-chat', chatId: string): void
  (e: 'delete-chat', chatId: string): void
  (e: 'logout'): void
}>()

/** 头像加载失败时退化成首字母，不让用户区塌掉 */
const avatarBroken = ref(false)
</script>

<template>
  <div class="sidebar">
    <div class="sidebar-header">
      <div class="app-title">
        <BrandMark class="logo-icon" />
        <span class="title-text">通用智能体平台</span>
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
      <!-- 用户区：数据是按 user_id 隔离的，界面上明确显示"当前是谁"，
           避免多人共用一台机器时误以为数据丢了 -->
      <div v-if="user" class="user-box">
        <img
          v-if="user.avatarUrl && !avatarBroken"
          class="user-avatar"
          :src="user.avatarUrl"
          :alt="user.nickname || user.login || 'avatar'"
          @error="avatarBroken = true"
        />
        <span v-else class="user-avatar fallback">
          {{ (user.nickname || user.login || '?').charAt(0).toUpperCase() }}
        </span>
        <span class="user-name">{{ user.nickname || user.login }}</span>
        <button class="logout-btn" type="button" title="退出登录" @click="$emit('logout')">
          <i class="fas fa-right-from-bracket"></i>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.user-box {
  display: flex;
  align-items: center;
  gap: 9px;
}

.user-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  object-fit: cover;
  flex: 0 0 auto;
  border: 1px solid var(--border-color);
}

.user-avatar.fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #3a3a3a;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}

.user-name {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logout-btn {
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition:
    color 0.15s ease,
    border-color 0.15s ease;
}

.logout-btn:hover {
  color: #ef4444;
  border-color: rgba(239, 68, 68, 0.5);
}
</style>
