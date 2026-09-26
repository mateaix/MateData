<script setup lang="ts">
import { icons, type IconName } from "../icons";
defineProps<{ name: IconName; spin?: boolean }>();
</script>
<template>
  <el-icon :class="{ 'is-loading': spin }" aria-hidden="true"
    ><component :is="icons[name]"
  /></el-icon>
</template>
