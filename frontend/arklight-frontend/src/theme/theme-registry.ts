import type { Component } from 'vue';
import ArkLightTheme from './ArkLightTheme.vue';
import ArkLightSettingsShell from './ArkLightSettingsShell.vue';
import PlainWebTheme from './PlainWebTheme.vue';
import PlainWebSettingsShell from './PlainWebSettingsShell.vue';
import type { FrontendSettings } from '../setting/types';
import type { ThemeId, ThemeOption, ThemeText } from './types';

export interface ThemeDefaults extends Omit<FrontendSettings, 'themeId'> {}

export interface RegisteredTheme extends ThemeOption {
  sceneComponent: Component;
  settingsShellComponent: Component;
  settingsOverlayClass: string;
  defaults: ThemeDefaults;
  text: ThemeText;
}

export const defaultThemeId: ThemeId = 'arklight';

export const registeredThemes: RegisteredTheme[] = [
  {
    id: 'arklight',
    label: '默认主题',
    description: '当前的 ArkLight 界面风格',
    sceneComponent: ArkLightTheme,
    settingsShellComponent: ArkLightSettingsShell,
    settingsOverlayClass: 'bg-[#1A1A1A]/45',
    defaults: {
      characterName: '',
      sessionId: '',
      workspaceName: 'ArkLight Pioneer',
      operatorName: 'Local',
      bootAnimationEnabled: true,
      bootDurationMs: 3500,
      responseDelayMs: 1000,
      shortModeEnabled: true,
      moteCount: 42,
      backendBaseUrl: 'http://localhost:8080',
      gameName: 'STS2MCP',
      gameSessionId: '',
      gameRpSessionId: ''
    },
    text: {
      bootLoadingLabel: '正在加载',
      settingsButtonLabel: '设置',
      inputPlaceholder: '输入内容...',
      sendButtonLabel: '发送',
      typingLabel: '对方正在输入...',
      assistantLabel: 'ArkLight / Remote',
      userLabelPrefix: 'Operator'
    }
  },
  {
    id: 'plain-web',
    label: '普通网页',
    description: '简洁、常规的网页布局',
    sceneComponent: PlainWebTheme,
    settingsShellComponent: PlainWebSettingsShell,
    settingsOverlayClass: 'bg-slate-900/20',
    defaults: {
      characterName: '',
      sessionId: '',
      workspaceName: '控制台',
      operatorName: '用户',
      bootAnimationEnabled: true,
      bootDurationMs: 1200,
      responseDelayMs: 600,
      shortModeEnabled: true,
      moteCount: 28,
      backendBaseUrl: 'http://localhost:8080',
      gameName: 'STS2MCP',
      gameSessionId: '',
      gameRpSessionId: ''
    },
    text: {
      bootLoadingLabel: '正在加载',
      settingsButtonLabel: '设置',
      inputPlaceholder: '请输入消息内容',
      sendButtonLabel: '发送',
      typingLabel: '对方正在输入...',
      assistantLabel: '系统 / ArkLight',
      userLabelPrefix: '用户'
    }
  }
];

export const themeOptions: ThemeOption[] = registeredThemes.map(
  ({ sceneComponent, settingsShellComponent, settingsOverlayClass, defaults, text, ...theme }) => theme
);

export const themeRegistryMap = registeredThemes.reduce<Record<ThemeId, RegisteredTheme>>(
  (accumulator, theme) => {
    accumulator[theme.id] = theme;
    return accumulator;
  },
  {} as Record<ThemeId, RegisteredTheme>
);

export const getThemeDefaults = (themeId: ThemeId): ThemeDefaults =>
  themeRegistryMap[themeId]?.defaults ?? themeRegistryMap[defaultThemeId].defaults;

export const getThemeText = (themeId: ThemeId): ThemeText =>
  themeRegistryMap[themeId]?.text ?? themeRegistryMap[defaultThemeId].text;
