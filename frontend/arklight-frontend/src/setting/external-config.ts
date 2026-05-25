import type { FrontendSettings } from './types';

type ScalarConfig = Record<string, string>;

const CONFIG_URL = '/config/application.yaml';

const normalizeKey = (value: string) => value.trim().replace(/_/g, '-').toLowerCase();

const stripInlineComment = (value: string) => {
  let quote: string | null = null;

  for (let index = 0; index < value.length; index += 1) {
    const char = value[index];
    if ((char === '"' || char === "'") && value[index - 1] !== '\\') {
      quote = quote === char ? null : quote ?? char;
      continue;
    }
    if (char === '#' && quote === null && (index === 0 || /\s/.test(value[index - 1]))) {
      return value.slice(0, index).trim();
    }
  }

  return value.trim();
};

const normalizeScalar = (value: string) => {
  const trimmed = stripInlineComment(value);
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
};

export const parseYamlScalars = (content: string): ScalarConfig => {
  const result: ScalarConfig = {};
  const stack: Array<{ indent: number; key: string }> = [];

  for (const rawLine of content.split(/\r?\n/)) {
    if (!rawLine.trim() || rawLine.trimStart().startsWith('#')) {
      continue;
    }

    const match = rawLine.match(/^(\s*)([A-Za-z0-9_-]+):(?:\s*(.*))?$/);
    if (!match) {
      continue;
    }

    const indent = match[1].length;
    const key = normalizeKey(match[2]);
    const rawValue = match[3] ?? '';

    while (stack.length && stack[stack.length - 1].indent >= indent) {
      stack.pop();
    }

    const path = [...stack.map((item) => item.key), key].join('.');
    const value = normalizeScalar(rawValue);
    if (value) {
      result[path] = value;
    }

    if (!value) {
      stack.push({ indent, key });
    }
  }

  return result;
};

const readString = (config: ScalarConfig, paths: string[]) => {
  for (const path of paths) {
    const value = config[path];
    if (typeof value === 'string' && value.trim()) {
      return value.trim();
    }
  }
  return undefined;
};

const readBoolean = (config: ScalarConfig, paths: string[]) => {
  const value = readString(config, paths);
  if (value === undefined) {
    return undefined;
  }
  if (['true', 'yes', '1', 'on'].includes(value.toLowerCase())) {
    return true;
  }
  if (['false', 'no', '0', 'off'].includes(value.toLowerCase())) {
    return false;
  }
  return undefined;
};

const readNumber = (config: ScalarConfig, paths: string[]) => {
  const value = readString(config, paths);
  if (value === undefined) {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
};

export const buildFrontendSettingsFromConfig = (
  config: ScalarConfig
): Partial<FrontendSettings> => {
  const themeId = readString(config, ['frontend.web.settings.theme-id', 'frontend.settings.theme-id']);
  return {
    themeId: themeId as FrontendSettings['themeId'] | undefined,
    characterName: readString(config, [
      'frontend.web.settings.character-name',
      'frontend.settings.character-name'
    ]),
    sessionId: readString(config, ['frontend.web.settings.session-id', 'frontend.settings.session-id']),
    workspaceName: readString(config, [
      'frontend.web.settings.workspace-name',
      'frontend.settings.workspace-name'
    ]),
    operatorName: readString(config, [
      'frontend.web.settings.operator-name',
      'frontend.settings.operator-name'
    ]),
    bootAnimationEnabled: readBoolean(config, [
      'frontend.web.settings.boot-animation-enabled',
      'frontend.settings.boot-animation-enabled'
    ]),
    bootDurationMs: readNumber(config, [
      'frontend.web.settings.boot-duration-ms',
      'frontend.settings.boot-duration-ms'
    ]),
    responseDelayMs: readNumber(config, [
      'frontend.web.settings.response-delay-ms',
      'frontend.settings.response-delay-ms'
    ]),
    shortModeEnabled: readBoolean(config, [
      'frontend.web.settings.short-mode-enabled',
      'frontend.settings.short-mode-enabled'
    ]),
    moteCount: readNumber(config, ['frontend.web.settings.mote-count', 'frontend.settings.mote-count']),
    backendBaseUrl: readString(config, [
      'frontend.web.settings.backend-base-url',
      'frontend.settings.backend-base-url'
    ])
  };
};

export const loadExternalFrontendSettings = async () => {
  if (typeof window === 'undefined') {
    return null;
  }

  try {
    const response = await fetch(CONFIG_URL, {
      headers: {
        Accept: 'application/yaml, text/yaml, text/plain;q=0.9, */*;q=0.8'
      }
    });
    if (!response.ok) {
      return null;
    }
    const content = await response.text();
    return buildFrontendSettingsFromConfig(parseYamlScalars(content));
  } catch {
    return null;
  }
};
