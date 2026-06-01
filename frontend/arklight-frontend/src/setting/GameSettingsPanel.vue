<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Pause, Play, RefreshCw, RotateCcw, Square } from 'lucide-vue-next';
import type { FrontendSettings } from './types';

const props = defineProps<{
  settings: FrontendSettings;
}>();

const emit = defineEmits<{
  'update:settings': [FrontendSettings];
}>();

interface GameLoopStatus {
  running?: boolean;
  gameName?: string;
  sessionId?: string;
  session?: {
    gameName?: string;
    sessionId?: string;
    rpSessionId?: string;
    state?: string;
    startedAt?: string;
    lastActivityAt?: string;
    totalStepCount?: number;
  };
}

const loading = ref(false);
const errorMessage = ref('');
const status = ref<GameLoopStatus | null>(null);
const lastUpdatedAt = ref('');

const normalizedBaseUrl = computed(() =>
  (props.settings.backendBaseUrl.trim() || 'http://localhost:8080').replace(/\/+$/, '')
);

const resolvedGameName = computed(() => props.settings.gameName.trim() || 'STS2MCP');
const resolvedGameSessionId = computed(() =>
  props.settings.gameSessionId.trim() || props.settings.sessionId.trim() || 'default'
);
const resolvedRpSessionId = computed(() =>
  props.settings.gameRpSessionId.trim() || props.settings.sessionId.trim() || resolvedGameSessionId.value
);

const session = computed(() => status.value?.session ?? null);
const currentState = computed(() => session.value?.state ?? (status.value?.running ? 'RUNNING' : 'STOPPED'));

const updateSetting = <K extends keyof FrontendSettings>(key: K, value: FrontendSettings[K]) => {
  emit('update:settings', {
    ...props.settings,
    [key]: value
  });
};

const handleTextInput = (
  key: 'gameName' | 'gameSessionId' | 'gameRpSessionId',
  event: Event
) => {
  const target = event.target as HTMLInputElement | null;
  updateSetting(key, (target?.value ?? '') as FrontendSettings[typeof key]);
};

const loopUrl = (action: 'start' | 'stop' | 'pause' | 'resume' | 'status') =>
  `${normalizedBaseUrl.value}/api/gamer/loop/${action}`;

const requestBody = () => ({
  gameName: resolvedGameName.value,
  sessionId: resolvedGameSessionId.value,
  rpSessionId: resolvedRpSessionId.value
});

const refreshStatus = async () => {
  loading.value = true;
  errorMessage.value = '';
  const url = new URL(loopUrl('status'));
  url.searchParams.set('gameName', resolvedGameName.value);
  url.searchParams.set('sessionId', resolvedGameSessionId.value);

  try {
    const response = await fetch(url.toString(), {
      headers: {
        Accept: 'application/json'
      }
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(typeof payload?.error === 'string' ? payload.error : `${response.status} ${response.statusText}`);
    }
    status.value = payload as GameLoopStatus;
    lastUpdatedAt.value = new Date().toLocaleString();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '无法读取游戏循环状态';
  } finally {
    loading.value = false;
  }
};

const sendLoopCommand = async (action: 'start' | 'stop' | 'pause' | 'resume') => {
  loading.value = true;
  errorMessage.value = '';

  try {
    const response = await fetch(loopUrl(action), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(requestBody())
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(typeof payload?.error === 'string' ? payload.error : `${response.status} ${response.statusText}`);
    }
    await refreshStatus();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '游戏循环操作失败';
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  void refreshStatus();
});
</script>

<template>
  <section class="space-y-8">
    <div class="space-y-2 border-b border-[#1A1A1A]/10 pb-6">
      <p class="text-[11px] font-mono uppercase tracking-[0.4em] text-[#4D908E]">Game Mode</p>
      <h3 class="text-3xl font-black uppercase tracking-[0.12em] text-[#1A1A1A]">游戏控制</h3>
    </div>

    <div class="grid gap-6 xl:grid-cols-3">
      <label class="space-y-3 border-2 border-[#1A1A1A] bg-white/70 p-5">
        <span class="block text-[11px] font-black uppercase tracking-[0.3em] text-[#1A1A1A]">游戏名</span>
        <input
          :value="props.settings.gameName"
          type="text"
          class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-4 py-3 text-sm outline-none transition focus:border-[#E85D04]"
          @input="handleTextInput('gameName', $event)"
        />
      </label>

      <label class="space-y-3 border-2 border-[#1A1A1A] bg-white/70 p-5">
        <span class="block text-[11px] font-black uppercase tracking-[0.3em] text-[#1A1A1A]">游戏 Session</span>
        <input
          :value="props.settings.gameSessionId"
          type="text"
          class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-4 py-3 text-sm outline-none transition focus:border-[#E85D04]"
          @input="handleTextInput('gameSessionId', $event)"
        />
        <p class="text-xs text-[#1A1A1A]/55">留空时使用聊天 Session。</p>
      </label>

      <label class="space-y-3 border-2 border-[#1A1A1A] bg-white/70 p-5">
        <span class="block text-[11px] font-black uppercase tracking-[0.3em] text-[#1A1A1A]">RP Session</span>
        <input
          :value="props.settings.gameRpSessionId"
          type="text"
          class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-4 py-3 text-sm outline-none transition focus:border-[#E85D04]"
          @input="handleTextInput('gameRpSessionId', $event)"
        />
        <p class="text-xs text-[#1A1A1A]/55">留空时使用聊天 Session。</p>
      </label>
    </div>

    <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
      <div class="border-2 border-[#1A1A1A] bg-[#1A1A1A] p-5 text-white">
        <p class="text-[10px] font-mono uppercase tracking-[0.35em] text-white/45">状态</p>
        <p class="mt-3 text-2xl font-black">{{ currentState }}</p>
      </div>
      <div class="border-2 border-[#1A1A1A] bg-white/70 p-5">
        <p class="text-[10px] font-mono uppercase tracking-[0.35em] text-[#1A1A1A]/45">游戏</p>
        <p class="mt-3 break-all font-mono text-sm">{{ session?.gameName ?? status?.gameName ?? resolvedGameName }}</p>
      </div>
      <div class="border-2 border-[#1A1A1A] bg-white/70 p-5">
        <p class="text-[10px] font-mono uppercase tracking-[0.35em] text-[#1A1A1A]/45">步数</p>
        <p class="mt-3 text-2xl font-black">{{ session?.totalStepCount ?? 0 }}</p>
      </div>
      <div class="border-2 border-[#1A1A1A] bg-white/70 p-5">
        <p class="text-[10px] font-mono uppercase tracking-[0.35em] text-[#1A1A1A]/45">刷新</p>
        <p class="mt-3 text-sm">{{ lastUpdatedAt || '未刷新' }}</p>
      </div>
    </div>

    <div class="border-2 border-[#1A1A1A] bg-white/70 p-5">
      <div class="grid gap-3 font-mono text-xs leading-6 md:grid-cols-2">
        <p>gameSessionId={{ session?.sessionId ?? resolvedGameSessionId }}</p>
        <p>rpSessionId={{ session?.rpSessionId ?? resolvedRpSessionId }}</p>
        <p>startedAt={{ session?.startedAt ?? '-' }}</p>
        <p>lastActivityAt={{ session?.lastActivityAt ?? '-' }}</p>
      </div>
    </div>

    <div v-if="errorMessage" class="border border-[#E85D04]/50 bg-[#E85D04]/10 p-4 text-sm leading-7 text-[#9A3412]">
      {{ errorMessage }}
    </div>

    <div class="flex flex-wrap gap-3">
      <button
        type="button"
        :disabled="loading"
        class="inline-flex items-center gap-2 border-2 border-[#1A1A1A] bg-[#1A1A1A] px-4 py-3 text-[11px] font-black uppercase tracking-[0.25em] text-white transition hover:bg-[#E85D04] disabled:opacity-45"
        @click="sendLoopCommand('start')"
      >
        <Play :size="16" />
        启动
      </button>
      <button
        type="button"
        :disabled="loading"
        class="inline-flex items-center gap-2 border-2 border-[#1A1A1A] px-4 py-3 text-[11px] font-black uppercase tracking-[0.25em] transition hover:bg-[#1A1A1A] hover:text-white disabled:opacity-45"
        @click="sendLoopCommand('pause')"
      >
        <Pause :size="16" />
        暂停
      </button>
      <button
        type="button"
        :disabled="loading"
        class="inline-flex items-center gap-2 border-2 border-[#1A1A1A] px-4 py-3 text-[11px] font-black uppercase tracking-[0.25em] transition hover:bg-[#1A1A1A] hover:text-white disabled:opacity-45"
        @click="sendLoopCommand('resume')"
      >
        <RotateCcw :size="16" />
        恢复
      </button>
      <button
        type="button"
        :disabled="loading"
        class="inline-flex items-center gap-2 border-2 border-[#1A1A1A] px-4 py-3 text-[11px] font-black uppercase tracking-[0.25em] transition hover:bg-[#1A1A1A] hover:text-white disabled:opacity-45"
        @click="sendLoopCommand('stop')"
      >
        <Square :size="16" />
        停止
      </button>
      <button
        type="button"
        :disabled="loading"
        class="inline-flex items-center gap-2 border-2 border-[#4D908E] px-4 py-3 text-[11px] font-black uppercase tracking-[0.25em] text-[#4D908E] transition hover:bg-[#4D908E] hover:text-white disabled:opacity-45"
        @click="refreshStatus"
      >
        <RefreshCw :size="16" />
        刷新
      </button>
    </div>
  </section>
</template>
