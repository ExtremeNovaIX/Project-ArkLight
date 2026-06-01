import type { SettingsView, ThemeId } from '../theme/types';
export type { SettingsView } from '../theme/types';

export interface FrontendSettings {
  themeId: ThemeId;
  characterName: string;
  sessionId: string;
  workspaceName: string;
  operatorName: string;
  bootAnimationEnabled: boolean;
  bootDurationMs: number;
  responseDelayMs: number;
  shortModeEnabled: boolean;
  moteCount: number;
  backendBaseUrl: string;
  gameName: string;
  gameSessionId: string;
  gameRpSessionId: string;
}
