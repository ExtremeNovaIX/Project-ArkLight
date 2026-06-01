<script setup lang="ts">
import { Gamepad2, Settings2, SlidersHorizontal, X } from 'lucide-vue-next';
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
  <div class="mx-auto flex h-full max-w-6xl flex-col overflow-hidden rounded-[28px] border border-slate-200 bg-[#f8fafc] shadow-[0_24px_80px_rgba(15,23,42,0.18)] lg:flex-row">
    <aside class="w-full shrink-0 border-b border-slate-200 bg-white/90 p-6 backdrop-blur lg:w-[320px] lg:border-b-0 lg:border-r">
      <div class="flex items-start justify-between gap-4">
        <div>
          <p class="text-xs font-semibold uppercase tracking-[0.28em] text-sky-600">系统设置</p>
          <h2 class="mt-3 text-3xl font-semibold text-slate-900">偏好配置</h2>
        </div>
        <div class="flex h-11 w-11 items-center justify-center rounded-2xl bg-sky-50 text-sky-600">
          <SlidersHorizontal :size="18" />
        </div>
      </div>

      <div class="mt-8 space-y-3">
        <button
          type="button"
          @click="emit('update:view', 'frontend')"
          :class="[
            'flex w-full items-center gap-4 rounded-2xl border px-4 py-4 text-left transition',
            props.activeView === 'frontend'
              ? 'border-sky-200 bg-sky-50 text-sky-900 shadow-sm'
              : 'border-slate-200 bg-white text-slate-700 hover:border-slate-300 hover:bg-slate-50'
          ]"
        >
          <div class="flex h-11 w-11 items-center justify-center rounded-2xl" :class="props.activeView === 'frontend' ? 'bg-sky-600 text-white' : 'bg-slate-100 text-slate-500'">
            <Settings2 :size="18" />
          </div>
          <div>
            <p class="text-sm font-semibold">前端设置</p>
          </div>
        </button>

        <button
          type="button"
          @click="emit('update:view', 'backend')"
          :class="[
            'flex w-full items-center gap-4 rounded-2xl border px-4 py-4 text-left transition',
            props.activeView === 'backend'
              ? 'border-sky-200 bg-sky-50 text-sky-900 shadow-sm'
              : 'border-slate-200 bg-white text-slate-700 hover:border-slate-300 hover:bg-slate-50'
          ]"
        >
          <div class="flex h-11 w-11 items-center justify-center rounded-2xl" :class="props.activeView === 'backend' ? 'bg-sky-600 text-white' : 'bg-slate-100 text-slate-500'">
            <Settings2 :size="18" />
          </div>
          <div>
            <p class="text-sm font-semibold">本地配置</p>
          </div>
        </button>

        <button
          type="button"
          @click="emit('update:view', 'game')"
          :class="[
            'flex w-full items-center gap-4 rounded-2xl border px-4 py-4 text-left transition',
            props.activeView === 'game'
              ? 'border-sky-200 bg-sky-50 text-sky-900 shadow-sm'
              : 'border-slate-200 bg-white text-slate-700 hover:border-slate-300 hover:bg-slate-50'
          ]"
        >
          <div class="flex h-11 w-11 items-center justify-center rounded-2xl" :class="props.activeView === 'game' ? 'bg-sky-600 text-white' : 'bg-slate-100 text-slate-500'">
            <Gamepad2 :size="18" />
          </div>
          <div>
            <p class="text-sm font-semibold">游戏模式</p>
          </div>
        </button>
      </div>
    </aside>

    <section class="flex min-h-0 flex-1 flex-col bg-gradient-to-br from-white via-slate-50 to-sky-50/50">
      <header class="flex items-center justify-between border-b border-slate-200/80 px-6 py-5 md:px-8">
        <div>
          <h3 class="mt-2 text-2xl font-semibold text-slate-900">
            {{ props.activeView === 'frontend' ? '前端设置' : props.activeView === 'backend' ? '本地配置' : '游戏模式' }}
          </h3>
        </div>

        <button
          type="button"
          @click="emit('close')"
          class="flex h-11 w-11 items-center justify-center rounded-2xl border border-slate-200 bg-white text-slate-500 transition hover:border-slate-300 hover:bg-slate-50 hover:text-slate-900"
        >
          <X :size="18" />
        </button>
      </header>

      <div class="min-h-0 flex-1 overflow-y-auto p-6 md:p-8">
        <div class="rounded-[24px] border border-white/60 bg-white/75 p-1 shadow-[0_20px_50px_rgba(148,163,184,0.18)] backdrop-blur">
          <div class="rounded-[20px] bg-white px-5 py-5 md:px-6 md:py-6">
            <slot />
          </div>
        </div>
      </div>
    </section>
  </div>
</template>
