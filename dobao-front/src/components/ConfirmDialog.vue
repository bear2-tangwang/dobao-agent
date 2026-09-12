<script setup lang="ts">
defineProps<{
  show: boolean
  title: string
  message: string
}>()

defineEmits<{
  (e: 'ok'): void
  (e: 'cancel'): void
}>()
</script>

<template>
  <div v-if="show" class="confirm-dialog-overlay">
    <div class="confirm-dialog">
      <div class="confirm-icon">
        <i class="fas fa-exclamation-circle"></i>
      </div>
      <h3>{{ title }}</h3>
      <p>{{ message }}</p>
      <div class="confirm-buttons">
        <button class="confirm-btn cancel" @click="$emit('cancel')">取消</button>
        <button class="confirm-btn danger" @click="$emit('ok')">确认删除</button>
      </div>
    </div>
  </div>
</template>