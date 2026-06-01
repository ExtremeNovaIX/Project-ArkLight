<script setup lang="ts">
import type { FrontendSettings } from './types';
import type { CharacterProfile } from '../chara/types';
import type { ThemeId, ThemeOption } from '../theme/types';

const props = defineProps<{
  settings: FrontendSettings;
  themeOptions: ThemeOption[];
  availableCharacters: CharacterProfile[];
  importedCharacters: CharacterProfile[];
}>();

const emit = defineEmits<{
  'update:settings': [FrontendSettings];
  'change-theme': [ThemeId];
  'install-character': [string];
  reset: [];
}>();

const updateSetting = <K extends keyof FrontendSettings>(
  key: K,
  value: FrontendSettings[K]
) => {
  emit('update:settings', {
    ...props.settings,
    [key]: value
  });
};

const handleTextInput = (
  key: 'backendBaseUrl' | 'sessionId',
  event: Event
) => {
  const target = event.target as HTMLInputElement | null;
  updateSetting(key, (target?.value ?? '') as FrontendSettings[typeof key]);
};

const handleBootAnimationChange = (event: Event) => {
  handleBooleanSettingChange('bootAnimationEnabled', event);
};

const handleShortModeChange = (event: Event) => {
  handleBooleanSettingChange('shortModeEnabled', event);
};

const handleBooleanSettingChange = (
  key: 'bootAnimationEnabled' | 'shortModeEnabled',
  event: Event
) => {
  const target = event.target as HTMLInputElement | null;
  updateSetting(key, Boolean(target?.checked) as FrontendSettings[typeof key]);
};

const handleThemeChange = (event: Event) => {
  const target = event.target as HTMLSelectElement | null;
  const nextThemeId = target?.value as ThemeId | undefined;

  if (!nextThemeId || nextThemeId === props.settings.themeId) {
    return;
  }

  emit('change-theme', nextThemeId);
};

const handleCharacterChange = (event: Event) => {
  const target = event.target as HTMLSelectElement | null;
  updateSetting('characterName', (target?.value ?? '') as FrontendSettings['characterName']);
};

const isImportedCharacter = (characterName: string) =>
  props.importedCharacters.some((character) => character.name === characterName);

const getCharacterPrimaryEmotion = (character: CharacterProfile) => character.defaultEmotion;

const getCharacterPrimaryImage = (character: CharacterProfile) =>
  character.imageUrls[character.defaultEmotion] ?? character.iconUrl;
</script>

<template>
  <section class="space-y-8">
    <div class="flex items-start justify-between gap-6 border-b border-[#1A1A1A]/10 pb-6">
      <div class="space-y-2">
        <p class="text-[11px] font-mono uppercase tracking-[0.4em] text-[#4D908E]">前端设置</p>
        <h3 class="text-3xl font-black uppercase tracking-[0.12em] text-[#1A1A1A]">当前会话</h3>
      </div>

      <button
        type="button"
        @click="emit('reset')"
        class="shrink-0 border-2 border-[#1A1A1A] px-4 py-3 text-[11px] font-black uppercase tracking-[0.3em] text-[#1A1A1A] transition hover:bg-[#1A1A1A] hover:text-white"
      >
        重置
      </button>
    </div>

    <div class="grid gap-6 xl:grid-cols-2">
      <label class="space-y-3 rounded-none border-2 border-[#1A1A1A] bg-white/70 p-5">
        <span class="block text-[11px] font-black uppercase tracking-[0.3em] text-[#1A1A1A]">界面主题</span>
        <select
          :value="props.settings.themeId"
          class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-4 py-3 text-sm outline-none transition focus:border-[#E85D04]"
          @change="handleThemeChange"
        >
          <option
            v-for="theme in props.themeOptions"
            :key="theme.id"
            :value="theme.id"
          >
            {{ theme.label }}
          </option>
        </select>
        <p class="text-xs text-[#1A1A1A]/55">
          切换主题后会自动刷新
        </p>
      </label>

      <label class="space-y-3 rounded-none border-2 border-[#1A1A1A] bg-white/70 p-5">
        <span class="block text-[11px] font-black uppercase tracking-[0.3em] text-[#1A1A1A]">当前角色</span>
        <select
          :value="props.settings.characterName"
          class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-4 py-3 text-sm outline-none transition focus:border-[#E85D04]"
          @change="handleCharacterChange"
        >
          <option value="">未选择</option>
          <option
            v-for="character in props.importedCharacters"
            :key="character.name"
            :value="character.name"
          >
            {{ character.name }}
          </option>
        </select>
        <p class="text-xs text-[#1A1A1A]/55">已导入：{{ props.importedCharacters.length }} 个角色</p>
      </label>

      <label class="space-y-3 rounded-none border-2 border-[#1A1A1A] bg-white/70 p-5">
        <span class="block text-[11px] font-black uppercase tracking-[0.3em] text-[#1A1A1A]">当前后端地址</span>
        <input
          :value="props.settings.backendBaseUrl"
          type="text"
          class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-4 py-3 text-sm outline-none transition focus:border-[#E85D04]"
          @input="handleTextInput('backendBaseUrl', $event)"
        />
        <p class="text-xs text-[#1A1A1A]/55">只影响当前浏览器保存的连接地址；默认值在“本地配置”里修改。</p>
      </label>

      <label class="space-y-3 rounded-none border-2 border-[#1A1A1A] bg-white/70 p-5">
        <span class="block text-[11px] font-black uppercase tracking-[0.3em] text-[#1A1A1A]">Session ID</span>
        <input
          :value="props.settings.sessionId"
          type="text"
          class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-4 py-3 text-sm outline-none transition focus:border-[#E85D04]"
          @input="handleTextInput('sessionId', $event)"
        />
        <p class="text-xs text-[#1A1A1A]/55">聊天请求会使用这个会话标识</p>
      </label>
    </div>

    <div class="grid gap-4 md:grid-cols-2">
      <label class="flex items-center justify-between gap-4 border-2 border-[#1A1A1A] bg-[#1A1A1A] px-5 py-4 text-white">
        <div>
          <span class="block text-[11px] font-black uppercase tracking-[0.3em]">短句模式</span>
          <p class="mt-2 text-xs text-white/60">默认开启。关闭后会让后端的 shortMode=false，并停用按句长调整的显示间隔。</p>
        </div>

        <input
          :checked="props.settings.shortModeEnabled"
          type="checkbox"
          class="h-5 w-5 accent-[#E85D04]"
          @change="handleShortModeChange"
        />
      </label>

    <label class="flex items-center justify-between gap-4 border-2 border-[#1A1A1A] bg-[#1A1A1A] px-5 py-4 text-white">
      <div>
        <span class="block text-[11px] font-black uppercase tracking-[0.3em]">启动动画</span>
        <p class="mt-2 text-xs text-white/60">开启或关闭启动动画</p>
      </div>

      <input
        :checked="props.settings.bootAnimationEnabled"
        type="checkbox"
        class="h-5 w-5 accent-[#E85D04]"
        @change="handleBootAnimationChange"
      />
    </label>
    </div>

    <section class="space-y-4 border-t border-[#1A1A1A]/10 pt-6">
      <div class="space-y-2">
        <p class="text-[11px] font-mono uppercase tracking-[0.4em] text-[#4D908E]">角色选择</p>
        <h4 class="text-2xl font-black uppercase tracking-[0.12em] text-[#1A1A1A]">Chara</h4>
      </div>

      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        <article
          v-for="character in props.availableCharacters"
          :key="character.name"
          class="border-2 border-[#1A1A1A]/10 bg-white p-4 transition hover:border-[#1A1A1A]"
        >
          <div class="relative overflow-hidden border border-[#1A1A1A]/10 bg-[#F8F5EC]">
            <img
              :src="getCharacterPrimaryImage(character)"
              :alt="character.name"
              class="h-52 w-full object-contain"
            />
            <div class="absolute left-3 top-3 bg-white/90 px-2 py-1 text-[10px] font-mono uppercase tracking-[0.2em] text-[#4D908E]">
              {{ getCharacterPrimaryEmotion(character) }}
            </div>
          </div>

          <div class="mt-4 flex items-start justify-between gap-3">
            <div>
              <p class="text-lg font-black text-[#1A1A1A]">{{ character.name }}</p>
              <p class="mt-1 text-xs text-[#1A1A1A]/55">表情数：{{ character.emotions.length }}</p>
            </div>

            <button
              type="button"
              @click="emit('install-character', character.name)"
              :class="[
                'border-2 px-3 py-2 text-[11px] font-black uppercase tracking-[0.28em] transition',
                isImportedCharacter(character.name)
                  ? 'border-[#4D908E] bg-[#4D908E] text-white hover:bg-[#3F7A79]'
                  : 'border-[#1A1A1A] text-[#1A1A1A] hover:bg-[#1A1A1A] hover:text-white'
              ]"
            >
              {{ isImportedCharacter(character.name) ? '使用' : '导入' }}
            </button>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>
