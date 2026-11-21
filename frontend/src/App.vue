<template>
  <router-view />
  <!-- 全局授权到期浮层（非模态，不影响操作；详见组件注释） -->
  <LicenseNotice />
</template>

<script setup>
import LicenseNotice from './components/LicenseNotice.vue'
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
  background: #f0f2f5;
  color: #1a1a2e;
}
</style>
