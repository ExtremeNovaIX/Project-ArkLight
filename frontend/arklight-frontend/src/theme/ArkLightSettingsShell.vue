<script setup lang="ts">
import { X } from 'lucide-vue-next';
import type { SettingsView } from './types';

const props = defineProps<{
  activeView: SettingsView;
}>();

const emit = defineEmits<{
  close: [];
  'update:view': [SettingsView];
}>();
</script>

<template>
  <div class="mx-auto flex h-full max-w-7xl flex-col overflow-hidden border-2 border-[#1A1A1A] bg-[#F2EFE6] shadow-[18px_18px_0_rgba(26,26,26,0.18)] lg:flex-row">
    <aside class="w-full border-b border-[#1A1A1A]/10 bg-[#EBE7DC] p-6 lg:w-80 lg:border-b-0 lg:border-r">
      <p class="text-[10px] font-mono uppercase tracking-[0.45em] text-[#4D908E]">系统设置</p>
      <h2 class="mt-4 text-3xl font-black uppercase tracking-[0.12em] text-[#1A1A1A]">设置面板</h2>

      <div class="mt-8 space-y-3">
        <button
          type="button"
          @click="emit('update:view', 'frontend')"
          :class="[
            'flex w-full items-center justify-between border-2 px-4 py-4 text-left transition',
            props.activeView === 'frontend'
              ? 'border-[#1A1A1A] bg-[#1A1A1A] text-white'
              : 'border-[#1A1A1A]/15 bg-white/50 text-[#1A1A1A] hover:border-[#1A1A1A]'
          ]"
        >
          <span>
            <span class="block text-[10px] font-mono uppercase tracking-[0.35em] opacity-60">本地</span>
            <span class="mt-2 block text-sm font-black uppercase tracking-[0.2em]">前端设置</span>
          </span>
        </button>

        <button
          type="button"
          @click="emit('update:view', 'backend')"
          :class="[
            'flex w-full items-center justify-between border-2 px-4 py-4 text-left transition',
            props.activeView === 'backend'
              ? 'border-[#1A1A1A] bg-[#1A1A1A] text-white'
              : 'border-[#1A1A1A]/15 bg-white/50 text-[#1A1A1A] hover:border-[#1A1A1A]'
          ]"
        >
          <span>
            <span class="block text-[10px] font-mono uppercase tracking-[0.35em] opacity-60">Config</span>
            <span class="mt-2 block text-sm font-black uppercase tracking-[0.2em]">本地配置</span>
          </span>
        </button>

        <button
          type="button"
          @click="emit('update:view', 'game')"
          :class="[
            'flex w-full items-center justify-between border-2 px-4 py-4 text-left transition',
            props.activeView === 'game'
              ? 'border-[#1A1A1A] bg-[#1A1A1A] text-white'
              : 'border-[#1A1A1A]/15 bg-white/50 text-[#1A1A1A] hover:border-[#1A1A1A]'
          ]"
        >
          <span>
            <span class="block text-[10px] font-mono uppercase tracking-[0.35em] opacity-60">Game</span>
            <span class="mt-2 block text-sm font-black uppercase tracking-[0.2em]">游戏模式</span>
          </span>
        </button>
      </div>
    </aside>

    <section class="flex min-h-0 flex-1 flex-col">
      <header class="flex items-center justify-between border-b border-[#1A1A1A]/10 bg-white/50 px-6 py-5">
        <div>
          <p class="text-[10px] font-mono uppercase tracking-[0.4em] text-[#E85D04]">
            {{ props.activeView === 'frontend' ? '前端设置' : props.activeView === 'backend' ? '本地配置' : '游戏模式' }}
          </p>
        </div>

        <button
          type="button"
          @click="emit('close')"
          class="flex h-11 w-11 items-center justify-center border-2 border-[#1A1A1A] transition hover:bg-[#1A1A1A] hover:text-white"
        >
          <X :size="20" />
        </button>
      </header>

      <div class="min-h-0 flex-1 overflow-y-auto p-6 md:p-8">
        <slot />
      </div>
    </section>
  </div>
</template>
