<script setup lang="ts">
/**
 * 应用壳。
 *
 * 只负责三件事：注册路由出口、渲染全局光晕背景、挂载路由。
 * 主界面在 components/ChatApp.vue，登录页在 views/LoginView.vue。
 */
</script>

<template>
  <!-- 科技感光晕效果（全局背景，登录页与主界面共用） -->
  <div class="glow-effect glow-effect-1"></div>
  <div class="glow-effect glow-effect-2"></div>
  <div class="glow-effect glow-effect-3"></div>

  <RouterView />
</template>
