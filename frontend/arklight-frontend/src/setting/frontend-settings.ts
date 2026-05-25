import { ref, watch } from 'vue';
import type { FrontendSettings } from './types';
import { loadExternalFrontendSettings } from './external-config';
import { defaultThemeId, getThemeDefaults, themeOptions } from '../theme/theme-registry';
import type { ThemeId } from '../theme/types';

export const FRONTEND_SETTINGS_STORAGE_KEY = 'arklight.frontend.settings';

const clamp = (value: number, min: number, max: number) => {
  if (Number.isNaN(value)) {
    return min;
  }

  return Math.min(Math.max(value, min), max);
};

const normalizeThemeId = (value?: string | null): ThemeId => {
  const matchedTheme = themeOptions.find((theme) => theme.id === value);
  return matchedTheme?.id ?? defaultThemeId;
};

export const getDefaultFrontendSettings = (themeId: ThemeId = defaultThemeId): FrontendSettings => ({
  themeId,
  ...getThemeDefaults(themeId)
});

export const defaultFrontendSettings: FrontendSettings = getDefaultFrontendSettings();

export const normalizeFrontendSettings = (
  value?: Partial<FrontendSettings> | null
): FrontendSettings => {
  const themeId = normalizeThemeId(value?.themeId);
  const themeDefaults = getDefaultFrontendSettings(themeId);
  const normalizedMoteCount = Number(value?.moteCount ?? themeDefaults.moteCount);

  return {
    themeId,
    characterName: value?.characterName?.trim() || themeDefaults.characterName,
    sessionId: value?.sessionId?.trim() || themeDefaults.sessionId,
    workspaceName: value?.workspaceName?.trim() || themeDefaults.workspaceName,
    operatorName: value?.operatorName?.trim() || themeDefaults.operatorName,
    bootAnimationEnabled: value?.bootAnimationEnabled ?? themeDefaults.bootAnimationEnabled,
    bootDurationMs: clamp(Number(value?.bootDurationMs ?? themeDefaults.bootDurationMs), 0, 10000),
    responseDelayMs: clamp(Number(value?.responseDelayMs ?? themeDefaults.responseDelayMs), 0, 10000),
    shortModeEnabled: value?.shortModeEnabled ?? themeDefaults.shortModeEnabled,
    moteCount: clamp(normalizedMoteCount <= 0 ? themeDefaults.moteCount : normalizedMoteCount, 12, 120),
    backendBaseUrl: value?.backendBaseUrl?.trim() || themeDefaults.backendBaseUrl
  };
};

export const loadFrontendSettings = () => {
  if (typeof window === 'undefined') {
    return defaultFrontendSettings;
  }

  try {
    const rawValue = window.localStorage.getItem(FRONTEND_SETTINGS_STORAGE_KEY);
    if (!rawValue) {
      return defaultFrontendSettings;
    }

    return normalizeFrontendSettings(JSON.parse(rawValue));
  } catch {
    return defaultFrontendSettings;
  }
};

export const hasSavedFrontendSettings = () => {
  if (typeof window === 'undefined') {
    return false;
  }
  return Boolean(window.localStorage.getItem(FRONTEND_SETTINGS_STORAGE_KEY));
};

export const loadExternalDefaultFrontendSettings = async () => {
  const externalSettings = await loadExternalFrontendSettings();
  if (!externalSettings) {
    return null;
  }

  return normalizeFrontendSettings({
    ...defaultFrontendSettings,
    ...externalSettings
  });
};

export const saveFrontendSettings = (settings: FrontendSettings) => {
  if (typeof window === 'undefined') {
    return;
  }

  window.localStorage.setItem(
    FRONTEND_SETTINGS_STORAGE_KEY,
    JSON.stringify(normalizeFrontendSettings(settings))
  );
};

export const createFrontendSettings = () => {
  const settings = ref<FrontendSettings>(loadFrontendSettings());

  watch(
    settings,
    (value) => {
      saveFrontendSettings(value);
    },
    { deep: true }
  );

  return settings;
};

export const applyExternalFrontendDefaults = async (
  settings: ReturnType<typeof createFrontendSettings>
) => {
  const externalDefaults = await loadExternalDefaultFrontendSettings();
  if (!externalDefaults) {
    return null;
  }

  if (hasSavedFrontendSettings()) {
    return externalDefaults;
  }

  settings.value = externalDefaults;
  return externalDefaults;
};
