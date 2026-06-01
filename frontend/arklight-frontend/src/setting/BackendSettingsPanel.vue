<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Power, RefreshCw, Save } from 'lucide-vue-next';
import { defaultFrontendSettings } from './frontend-settings';

const props = defineProps<{
  baseUrl: string;
}>();

const emit = defineEmits<{
  'update:baseUrl': [string];
}>();

interface EditableConfigField {
  key: string;
  path: string;
  label: string;
  description: string;
  type: 'text' | 'password' | 'number' | 'boolean' | 'select' | 'textarea' | 'list';
  value: string;
  options: string[];
  sensitive: boolean;
  placeholder: string;
}

interface EditableConfigPage {
  fileName: string;
  title: string;
  description: string;
  filePath: string;
  fields: EditableConfigField[];
}

interface ConfigCatalogSnapshot {
  configDir: string;
  restartSupported: boolean;
  configs: EditableConfigPage[];
  readAt: string;
}

type FieldValue = string | boolean;

const CONFIG_HISTORY_STORAGE_KEY = 'arklight.config.field.history.v1';
const MAX_HISTORY_ITEMS = 8;

const loading = ref(false);
const saving = ref(false);
const restarting = ref(false);
const errorMessage = ref('');
const actionMessage = ref('');
const responseStatus = ref('');
const lastFetchedAt = ref('');
const catalog = ref<ConfigCatalogSnapshot | null>(null);
const activeFileName = ref('');
const fieldValues = ref<Record<string, FieldValue>>({});
const fieldHistories = ref<Record<string, string[]>>({});

const normalizedBaseUrl = computed(() => {
  const candidate = props.baseUrl.trim() || defaultFrontendSettings.backendBaseUrl;
  return candidate.replace(/\/+$/, '');
});

const endpointUrl = computed(() => `${normalizedBaseUrl.value}/api/setting`);
const configsUrl = computed(() => `${endpointUrl.value}/configs`);
const restartUrl = computed(() => `${endpointUrl.value}/restart`);
const configPages = computed(() => catalog.value?.configs ?? []);
const activePage = computed(() =>
  configPages.value.find((page) => page.fileName === activeFileName.value) ?? configPages.value[0] ?? null
);

const fieldStorageKey = (page: EditableConfigPage, field: EditableConfigField) =>
  `${page.fileName}:${field.key}`;

const handleBaseUrlInput = (event: Event) => {
  const target = event.target as HTMLInputElement | null;
  emit('update:baseUrl', target?.value ?? '');
};

const loadHistories = () => {
  if (typeof window === 'undefined') {
    return {};
  }
  try {
    const raw = window.localStorage.getItem(CONFIG_HISTORY_STORAGE_KEY);
    return raw ? JSON.parse(raw) as Record<string, string[]> : {};
  } catch {
    return {};
  }
};

const saveHistories = () => {
  if (typeof window === 'undefined') {
    return;
  }
  window.localStorage.setItem(CONFIG_HISTORY_STORAGE_KEY, JSON.stringify(fieldHistories.value));
};

const rememberHistory = (page: EditableConfigPage) => {
  for (const field of page.fields) {
    if (field.type === 'boolean' || field.sensitive) {
      continue;
    }
    const key = fieldStorageKey(page, field);
    const value = String(fieldValues.value[key] ?? '').trim();
    if (!value) {
      continue;
    }
    const current = fieldHistories.value[key] ?? [];
    fieldHistories.value[key] = [value, ...current.filter((item) => item !== value)].slice(0, MAX_HISTORY_ITEMS);
  }
  saveHistories();
};

const parseBoolean = (value: string) => ['true', 'yes', '1', 'on'].includes(value.trim().toLowerCase());

const fallbackFieldValue = (field: EditableConfigField): FieldValue =>
  field.type === 'boolean' ? parseBoolean(field.value) : field.value ?? '';

const setFieldValuesFromCatalog = (nextCatalog: ConfigCatalogSnapshot) => {
  const nextValues: Record<string, FieldValue> = {};
  for (const page of nextCatalog.configs) {
    for (const field of page.fields) {
      const key = fieldStorageKey(page, field);
      nextValues[key] = fallbackFieldValue(field);
    }
  }
  fieldValues.value = nextValues;
};

const fetchConfigs = async () => {
  loading.value = true;
  errorMessage.value = '';
  actionMessage.value = '';

  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), 6000);

  try {
    const response = await fetch(configsUrl.value, {
      headers: {
        Accept: 'application/json'
      },
      signal: controller.signal
    });
    const payload = await response.json().catch(() => null);
    responseStatus.value = `${response.status} ${response.statusText}`.trim();
    lastFetchedAt.value = new Date().toLocaleString();
    if (!response.ok || !payload) {
      throw new Error(responseStatus.value || '配置接口返回异常');
    }
    catalog.value = payload as ConfigCatalogSnapshot;
    setFieldValuesFromCatalog(catalog.value);
    if (!activeFileName.value || !configPages.value.some((page) => page.fileName === activeFileName.value)) {
      activeFileName.value = configPages.value[0]?.fileName ?? '';
    }
  } catch (error) {
    responseStatus.value = '请求失败';
    lastFetchedAt.value = new Date().toLocaleString();
    errorMessage.value = error instanceof Error
      ? `无法读取本地配置。${error.message}`
      : '无法读取本地配置。';
  } finally {
    window.clearTimeout(timeoutId);
    loading.value = false;
  }
};

const updateField = (page: EditableConfigPage | null, field: EditableConfigField, value: FieldValue) => {
  if (!page) {
    return;
  }
  fieldValues.value = {
    ...fieldValues.value,
    [fieldStorageKey(page, field)]: value
  };
};

const handleFieldInput = (page: EditableConfigPage | null, field: EditableConfigField, event: Event) => {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement | null;
  updateField(page, field, target?.value ?? '');
};

const handleBooleanInput = (page: EditableConfigPage | null, field: EditableConfigField, event: Event) => {
  const target = event.target as HTMLInputElement | null;
  updateField(page, field, Boolean(target?.checked));
};

const applyHistory = (page: EditableConfigPage | null, field: EditableConfigField, event: Event) => {
  const target = event.target as HTMLSelectElement | null;
  const value = target?.value ?? '';
  if (!page || !value) {
    return;
  }
  updateField(page, field, value);
  target.value = '';
};

const saveCurrentConfig = async () => {
  const page = activePage.value;
  if (!page) {
    return;
  }
  saving.value = true;
  errorMessage.value = '';
  actionMessage.value = '';

  const values: Record<string, FieldValue> = {};
  for (const field of page.fields) {
    values[field.key] = fieldValues.value[fieldStorageKey(page, field)] ?? fallbackFieldValue(field);
  }

  try {
    const response = await fetch(`${configsUrl.value}/${encodeURIComponent(page.fileName)}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({ values })
    });
    const payload = await response.json().catch(() => null);
    responseStatus.value = `${response.status} ${response.statusText}`.trim();
    lastFetchedAt.value = new Date().toLocaleString();
    if (!response.ok || !payload) {
      throw new Error(responseStatus.value || '保存失败');
    }
    rememberHistory(page);
    const savedPage = payload as EditableConfigPage;
    catalog.value = catalog.value
      ? {
          ...catalog.value,
          configs: catalog.value.configs.map((item) => item.fileName === savedPage.fileName ? savedPage : item),
          readAt: new Date().toISOString()
        }
      : null;
    if (catalog.value) {
      setFieldValuesFromCatalog(catalog.value);
    }
    actionMessage.value = `${page.title} 已写入 config，重启后端后生效。`;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存配置失败';
  } finally {
    saving.value = false;
  }
};

const restartBackend = async () => {
  if (!window.confirm('后端会在短暂延迟后退出。确认请求重启？')) {
    return;
  }

  restarting.value = true;
  errorMessage.value = '';
  actionMessage.value = '';

  try {
    const response = await fetch(restartUrl.value, {
      method: 'POST',
      headers: {
        Accept: 'application/json'
      }
    });
    const payload = await response.json().catch(() => ({}));
    responseStatus.value = `${response.status} ${response.statusText}`.trim();
    lastFetchedAt.value = new Date().toLocaleString();
    if (!response.ok) {
      throw new Error(typeof payload?.message === 'string' ? payload.message : responseStatus.value);
    }
    actionMessage.value = typeof payload?.message === 'string'
      ? payload.message
      : '已发送后端重启请求。';
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '后端重启请求失败';
  } finally {
    restarting.value = false;
  }
};

const valueFor = (page: EditableConfigPage | null, field: EditableConfigField) =>
  page ? fieldValues.value[fieldStorageKey(page, field)] ?? fallbackFieldValue(field) : fallbackFieldValue(field);
const historyFor = (page: EditableConfigPage | null, field: EditableConfigField) =>
  page ? fieldHistories.value[fieldStorageKey(page, field)] ?? [] : [];

onMounted(() => {
  fieldHistories.value = loadHistories();
  void fetchConfigs();
});
</script>

<template>
  <section class="space-y-5">
    <div class="flex flex-col gap-4 border-b border-[#1A1A1A]/10 pb-5 xl:flex-row xl:items-center xl:justify-between">
      <div>
        <p class="text-xs font-semibold text-[#E85D04]">本地配置</p>
        <h3 class="mt-1 text-2xl font-black text-[#1A1A1A]">配置编辑</h3>
      </div>

      <div class="flex flex-wrap gap-2">
        <button
          type="button"
          :disabled="loading"
          class="inline-flex items-center gap-2 border-2 border-[#1A1A1A] px-3 py-2 text-xs font-semibold transition hover:bg-[#1A1A1A] hover:text-white disabled:opacity-45"
          @click="fetchConfigs"
        >
          <RefreshCw :size="15" />
          {{ loading ? '读取中' : '重新读取' }}
        </button>
        <button
          type="button"
          :disabled="saving || !activePage"
          class="inline-flex items-center gap-2 border-2 border-[#4D908E] bg-[#4D908E] px-3 py-2 text-xs font-semibold text-white transition hover:bg-transparent hover:text-[#4D908E] disabled:opacity-45"
          @click="saveCurrentConfig"
        >
          <Save :size="15" />
          {{ saving ? '保存中' : '保存当前页' }}
        </button>
        <button
          type="button"
          :disabled="restarting"
          class="inline-flex items-center gap-2 border-2 border-[#E85D04] bg-[#E85D04] px-3 py-2 text-xs font-semibold text-white transition hover:bg-transparent hover:text-[#E85D04] disabled:opacity-45"
          @click="restartBackend"
        >
          <Power :size="15" />
          {{ restarting ? '请求中' : '重启后端' }}
        </button>
      </div>
    </div>

    <div class="grid gap-3 xl:grid-cols-[minmax(0,1fr)_minmax(260px,340px)]">
      <label class="block space-y-2 border border-[#1A1A1A]/20 bg-white/70 p-3">
        <span class="block text-xs font-semibold text-[#1A1A1A]">当前连接地址</span>
        <input
          :value="props.baseUrl"
          type="text"
          class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-3 py-2 text-sm outline-none transition focus:border-[#E85D04]"
          @input="handleBaseUrlInput"
        />
      </label>

      <div class="border border-[#1A1A1A]/20 bg-[#101010] p-3 text-white">
        <p class="text-xs text-white/45">配置目录</p>
        <p class="mt-2 break-all font-mono text-[11px] leading-5 text-white/80">{{ catalog?.configDir ?? '尚未读取' }}</p>
      </div>
    </div>

    <div v-if="errorMessage" class="border border-[#E85D04]/50 bg-[#E85D04]/10 p-3 text-sm leading-6 text-[#9A3412]">
      {{ errorMessage }}
    </div>

    <div v-if="actionMessage" class="border border-[#4D908E]/60 bg-[#4D908E]/10 p-3 text-sm leading-6 text-[#25615F]">
      {{ actionMessage }}
    </div>

    <div v-if="configPages.length" class="space-y-4">
      <nav class="flex gap-2 overflow-x-auto border-y border-[#1A1A1A]/10 py-3">
        <button
          v-for="page in configPages"
          :key="page.fileName"
          type="button"
          :class="[
            'shrink-0 border px-3 py-2 text-left transition',
            activeFileName === page.fileName
              ? 'border-[#1A1A1A] bg-[#1A1A1A] text-white'
              : 'border-[#1A1A1A]/15 bg-white/70 text-[#1A1A1A] hover:border-[#1A1A1A]'
          ]"
          @click="activeFileName = page.fileName"
        >
          <span class="block whitespace-nowrap text-xs font-semibold">{{ page.title }}</span>
          <span class="mt-1 block whitespace-nowrap font-mono text-[10px] opacity-60">{{ page.fileName }}</span>
        </button>
      </nav>

      <section v-if="activePage" class="space-y-4">
        <div class="flex flex-col gap-2 border border-[#1A1A1A]/15 bg-white/70 px-4 py-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h4 class="text-lg font-black text-[#1A1A1A]">{{ activePage.title }}</h4>
            <p class="mt-1 text-sm leading-6 text-[#1A1A1A]/60">{{ activePage.description }}</p>
          </div>
          <div class="text-left lg:text-right">
            <p class="font-mono text-[11px] text-[#1A1A1A]/45">{{ activePage.fileName }}</p>
            <p class="mt-1 font-mono text-[10px] text-[#1A1A1A]/35">{{ activePage.fields.length }} 项 · {{ lastFetchedAt || '未读取' }}</p>
          </div>
        </div>

        <div class="grid gap-3 xl:grid-cols-2 2xl:grid-cols-3">
          <label
            v-for="field in activePage.fields"
            :key="field.key"
            class="relative space-y-3 border border-[#1A1A1A]/15 bg-white/80 p-4 transition hover:border-[#1A1A1A]"
          >
            <div class="space-y-1">
              <span class="block text-xs font-semibold text-[#1A1A1A]">{{ field.label }}</span>
              <span v-if="field.description" class="block text-[11px] leading-5 text-[#1A1A1A]/48">
                {{ field.description }}
              </span>
            </div>

            <select
              v-if="field.type === 'select'"
              :value="valueFor(activePage, field)"
              class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-3 py-2 text-sm outline-none transition focus:border-[#E85D04]"
              @change="handleFieldInput(activePage, field, $event)"
            >
              <option value="" disabled>请选择</option>
              <option
                v-for="option in field.options"
                :key="option"
                :value="option"
              >
                {{ option }}
              </option>
            </select>

            <label
              v-else-if="field.type === 'boolean'"
              class="flex items-center justify-between gap-4 border border-[#1A1A1A]/10 bg-[#1A1A1A] px-3 py-2 text-white"
            >
              <span class="text-sm">{{ valueFor(activePage, field) ? '已开启' : '已关闭' }}</span>
              <input
                :checked="Boolean(valueFor(activePage, field))"
                type="checkbox"
                class="h-5 w-5 accent-[#E85D04]"
                @change="handleBooleanInput(activePage, field, $event)"
              />
            </label>

            <textarea
              v-else-if="field.type === 'textarea' || field.type === 'list'"
              :value="valueFor(activePage, field)"
              :rows="field.type === 'list' ? 5 : 4"
              :placeholder="field.type === 'list' ? '每行一条' : field.placeholder"
              class="w-full resize-y border border-[#1A1A1A]/20 bg-[#F8F5EC] px-3 py-2 text-sm leading-6 outline-none transition focus:border-[#E85D04]"
              @input="handleFieldInput(activePage, field, $event)"
            />

            <input
              v-else
              :value="valueFor(activePage, field)"
              :type="field.type === 'password' ? 'password' : field.type === 'number' ? 'number' : 'text'"
              :placeholder="field.placeholder"
              class="w-full border border-[#1A1A1A]/20 bg-[#F8F5EC] px-3 py-2 text-sm outline-none transition focus:border-[#E85D04]"
              @input="handleFieldInput(activePage, field, $event)"
            />

            <select
              v-if="field.type !== 'boolean' && !field.sensitive && historyFor(activePage, field).length"
              class="w-full border border-[#1A1A1A]/15 bg-white px-3 py-2 text-xs text-[#1A1A1A]/70 outline-none transition focus:border-[#4D908E]"
              @change="applyHistory(activePage, field, $event)"
            >
              <option value="">历史填入</option>
              <option v-for="item in historyFor(activePage, field)" :key="item" :value="item">{{ item }}</option>
            </select>

            <p class="break-all font-mono text-[11px] text-[#1A1A1A]/40">{{ field.path }}</p>
          </label>
        </div>
      </section>
    </div>

    <div v-else-if="!loading" class="border border-[#1A1A1A]/15 bg-white/70 p-5 text-sm text-[#1A1A1A]/60">
      没有找到可编辑的本地配置文件。请确认 config 目录存在。
    </div>
  </section>
</template>
