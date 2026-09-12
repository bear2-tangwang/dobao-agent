<script setup lang="ts">
defineEmits<{
  (e: 'quick-prompt', prompt: string): void
}>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-icon-wrapper">
      <div class="empty-icon">🤖</div>
      <div class="icon-glow"></div>
    </div>
    <h2>你好，我是豆豆</h2>
    <p>有什么可以帮助你的吗？</p>
    <div class="quick-actions">
      <div class="quick-action" @click="$emit('quick-prompt', '介绍一下你自己')">
        <i class="fas fa-user"></i>
        <span>介绍一下你自己</span>
      </div>
      <div class="quick-action" @click="$emit('quick-prompt', '帮我写一个Python示例')">
        <i class="fas fa-code"></i>
        <span>帮我写代码</span>
      </div>
      <div class="quick-action" @click="$emit('quick-prompt', '分析一段文本')">
        <i class="fas fa-file-alt"></i>
        <span>分析文本</span>
      </div>
    </div>
  </div>
</template>