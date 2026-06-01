export type ThemeId = 'arklight' | 'plain-web';

export interface ThemeOption {
  id: ThemeId;
  label: string;
  description: string;
}

export type SettingsView = 'frontend' | 'backend' | 'game';

export interface ThemeText {
  bootLoadingLabel: string;
  settingsButtonLabel: string;
  inputPlaceholder: string;
  sendButtonLabel: string;
  typingLabel: string;
  assistantLabel: string;
  userLabelPrefix: string;
}

export interface Message {
  id: number;
  role: 'ai' | 'user';
  content: string;
  emotion?: string | null;
}

export interface StardustParticle {
  id: string;
  top: number;
  left: number;
  size: number;
  glow: number;
  duration: number;
  delay: number;
  driftX: number;
  driftY: number;
  opacity: number;
}
