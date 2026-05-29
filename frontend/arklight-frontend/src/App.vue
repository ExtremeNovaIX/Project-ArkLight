<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { loadCharacterCatalog } from './chara/catalog';
import { loadInstalledCharacterNames, saveInstalledCharacterNames } from './chara/library';
import { extractSentenceContent, normalizeEmotionToken, parseEmotionPrefix } from './chara/message-parser';
import type { CharacterProfile } from './chara/types';
import BackendSettingsPanel from './setting/BackendSettingsPanel.vue';
import FrontendSettingsPanel from './setting/FrontendSettingsPanel.vue';
import {
  createFrontendSettings,
  applyExternalFrontendDefaults,
  getDefaultFrontendSettings,
  normalizeFrontendSettings,
  saveFrontendSettings
} from './setting/frontend-settings';
import type { FrontendSettings, SettingsView } from './setting/types';
import { defaultThemeId, themeOptions, themeRegistryMap } from './theme/theme-registry';
import type { Message, StardustParticle, ThemeId } from './theme/types';

interface AssistantReplyStep {
  id: number;
  content: string;
  emotion: string | null;
}

const frontendSettings = createFrontendSettings();
const externalDefaultSettings = ref<FrontendSettings | null>(null);

const messages = ref<Message[]>([]);

const userInput = ref('');
const isBooting = ref(true);
const showTitle = ref(false);
const isSettingsOpen = ref(false);
const activeSettingsView = ref<SettingsView>('frontend');
const stardustParticles = ref<StardustParticle[]>([]);
const availableCharacters = ref<CharacterProfile[]>([]);
const installedCharacterNames = ref<string[]>([]);
const activeEmotion = ref('');
const isAssistantTyping = ref(false);
const isSendLocked = ref(false);

let bootTitleTimer: ReturnType<typeof setTimeout> | undefined;
let bootDismissTimer: ReturnType<typeof setTimeout> | undefined;
let liveMessageSource: EventSource | undefined;
let ttsAudioSource: EventSource | undefined;
let ttsAudioContext: AudioContext | undefined;
let ttsPcmWorkletNode: AudioWorkletNode | undefined;
let ttsPcmWorkletReady: Promise<AudioWorkletNode | null> | undefined;
let ttsNextPlayTime = 0;
let ttsAudioConnectionVersion = 0;
let ttsAudioPlaybackChain: Promise<void> = Promise.resolve();
const pendingResponseTimers = new Set<ReturnType<typeof setTimeout>>();
const TTS_AUDIO_START_MARGIN_SECONDS = 0.02;
const TTS_CHUNK_CROSSFADE_SECONDS = 0.012;
const TTS_PLAYBACK_TEMPO = 1.1;

const activeTheme = computed(
  () => themeRegistryMap[frontendSettings.value.themeId] ?? themeRegistryMap[defaultThemeId]
);
const workspaceName = computed(
  () => frontendSettings.value.workspaceName || activeTheme.value.defaults.workspaceName
);
const operatorName = computed(
  () => frontendSettings.value.operatorName || activeTheme.value.defaults.operatorName
);
const activeCharacter = computed(
  () => availableCharacters.value.find((character) => character.name === frontendSettings.value.characterName) ?? null
);
const importedCharacters = computed(() =>
  availableCharacters.value.filter((character) => installedCharacterNames.value.includes(character.name))
);
const activeCharacterEmotion = computed(() => {
  if (!activeCharacter.value) {
    return '';
  }

  if (activeEmotion.value && activeCharacter.value.imageUrls[activeEmotion.value]) {
    return activeEmotion.value;
  }

  return activeCharacter.value.defaultEmotion;
});
const activeCharacterImageUrl = computed(() => {
  if (!activeCharacter.value) {
    return '';
  }

  return activeCharacter.value.imageUrls[activeCharacterEmotion.value]
    ?? Object.values(activeCharacter.value.imageUrls)[0]
    ?? '';
});

const resolveCharacterEmotion = (emotion: string | null | undefined) => {
  if (!emotion || !activeCharacter.value) {
    return null;
  }

  if (activeCharacter.value.imageUrls[emotion]) {
    return emotion;
  }

  const normalizedEmotion = normalizeEmotionToken(emotion);
  const matchedEmotion = Object.keys(activeCharacter.value.imageUrls).find(
    (emotionName) => normalizeEmotionToken(emotionName) === normalizedEmotion
  );

  return matchedEmotion ?? null;
};

const createStardustParticles = (count: number) =>
  Array.from({ length: count }, (_, index) => ({
    id: `stardust-${index}`,
    top: Math.random() * 88 + 4,
    left: Math.random() * 118 - 12,
    size: Number((Math.random() * 1.8 + 1.6).toFixed(2)),
    glow: Number((Math.random() * 18 + 18).toFixed(2)),
    duration: Number((Math.random() * 10 + 9).toFixed(2)),
    delay: Number((Math.random() * 4.5).toFixed(2)),
    driftX: Math.round(Math.random() * 72 + 28),
    driftY: -Math.round(Math.random() * 56 + 14),
    opacity: Number((Math.random() * 0.22 + 0.58).toFixed(2))
  }));

const clampNumber = (value: number, min: number, max: number) =>
  Math.min(Math.max(value, min), max);

const getChatApiUrl = () => {
  const normalizedBaseUrl = frontendSettings.value.backendBaseUrl.trim().replace(/\/+$/, '');
  return `${normalizedBaseUrl}/api/chat/send`;
};

const getChatTypingUrl = () => {
  const normalizedBaseUrl = frontendSettings.value.backendBaseUrl.trim().replace(/\/+$/, '');
  const typingUrl = new URL(`${normalizedBaseUrl}/api/chat/typing`);
  typingUrl.searchParams.set('sessionId', frontendSettings.value.sessionId);
  return typingUrl.toString();
};

const getChatLiveUrl = () => {
  const normalizedBaseUrl = frontendSettings.value.backendBaseUrl.trim().replace(/\/+$/, '');
  const liveUrl = new URL(`${normalizedBaseUrl}/api/chat/live`);
  liveUrl.searchParams.set('sessionId', frontendSettings.value.sessionId);
  liveUrl.searchParams.set('characterName', getRoleNameForRequest());
  liveUrl.searchParams.set('shortMode', String(frontendSettings.value.shortModeEnabled));
  return liveUrl.toString();
};

const getTtsLiveUrl = () => {
  const normalizedBaseUrl = frontendSettings.value.backendBaseUrl.trim().replace(/\/+$/, '');
  const liveUrl = new URL(`${normalizedBaseUrl}/api/tts/live`);
  liveUrl.searchParams.set('sessionId', frontendSettings.value.sessionId);
  return liveUrl.toString();
};

const getRoleNameForRequest = () =>
  String(frontendSettings.value.characterName || activeCharacter.value?.name || '').trim();

const createAssistantReplyStep = (sentence: string): AssistantReplyStep | null => {
  const parsedSentence = parseEmotionPrefix(sentence);
  if (!parsedSentence.content && !parsedSentence.emotion) {
    return null;
  }

  return {
    id: Date.now() + Math.floor(Math.random() * 1000),
    content: parsedSentence.content,
    emotion: parsedSentence.emotion
  };
};

const createAssistantMessage = (sentence: string): Message | null => {
  const replyStep = createAssistantReplyStep(sentence);
  if (!replyStep?.content) {
    return null;
  }

  return {
    id: replyStep.id,
    role: 'ai',
    content: replyStep.content,
    emotion: replyStep.emotion
  };
};

const calculateHumanDelay = (messageContent: string) => {
  const textLength = Math.max(Array.from(messageContent).length, 1);
  const lowerBound = clampNumber(
    Math.max(frontendSettings.value.responseDelayMs, 350) + textLength * 18,
    500,
    2600
  );
  const upperBound = clampNumber(
    lowerBound + 220 + textLength * 12,
    lowerBound + 120,
    3600
  );

  return Math.floor(lowerBound + Math.random() * (upperBound - lowerBound));
};

const calculateAssistantMessageDelay = (messageContent: string) => {
  if (!frontendSettings.value.shortModeEnabled) {
    return clampNumber(frontendSettings.value.responseDelayMs, 0, 10000);
  }

  return calculateHumanDelay(messageContent);
};

const mergeAssistantReplySteps = (nextSteps: AssistantReplyStep[]): AssistantReplyStep | null => {
  const availableSteps = nextSteps.filter((step) => step.content.trim());
  if (!availableSteps.length) {
    return null;
  }

  return {
    id: availableSteps[0].id,
    content: availableSteps.map((step) => step.content.trim()).join('\n'),
    emotion: availableSteps.find((step) => step.emotion)?.emotion ?? null
  };
};

const appendAssistantStep = (step: AssistantReplyStep) => {
  const matchedEmotion = resolveCharacterEmotion(step.emotion);
  if (matchedEmotion) {
    activeEmotion.value = matchedEmotion;
  }

  if (!step.content) {
    return;
  }

  messages.value.push({
    id: step.id,
    role: 'ai',
    content: step.content,
    emotion: matchedEmotion ?? step.emotion
  });
};

const clearBootTimers = () => {
  if (bootTitleTimer) {
    clearTimeout(bootTitleTimer);
  }

  if (bootDismissTimer) {
    clearTimeout(bootDismissTimer);
  }
};

const startBootSequence = () => {
  clearBootTimers();

  if (!frontendSettings.value.bootAnimationEnabled) {
    showTitle.value = true;
    isBooting.value = false;
    return;
  }

  isBooting.value = true;
  showTitle.value = false;

  const titleDelay = Math.min(
    Math.max(Math.floor(frontendSettings.value.bootDurationMs * 0.18), 150),
    800
  );

  bootTitleTimer = setTimeout(() => {
    showTitle.value = true;
  }, titleDelay);

  bootDismissTimer = setTimeout(() => {
    isBooting.value = false;
  }, frontendSettings.value.bootDurationMs);
};

const applyFrontendSettings = (nextSettings: FrontendSettings) => {
  const normalizedSettings = normalizeFrontendSettings(nextSettings);
  const themeChanged = normalizedSettings.themeId !== frontendSettings.value.themeId;

  frontendSettings.value = normalizedSettings;

  if (themeChanged && typeof window !== 'undefined') {
    saveFrontendSettings(normalizedSettings);
    window.location.reload();
  }
};

const syncCharacterEmotion = () => {
  if (!activeCharacter.value) {
    activeEmotion.value = '';
    return;
  }

  if (activeEmotion.value && activeCharacter.value.imageUrls[activeEmotion.value]) {
    return;
  }

  activeEmotion.value = activeCharacter.value.defaultEmotion;
};

const loadCharacters = async () => {
  availableCharacters.value = await loadCharacterCatalog();
  installedCharacterNames.value = loadInstalledCharacterNames()
    .filter((characterName) => availableCharacters.value.some((character) => character.name === characterName));

  if (
    frontendSettings.value.characterName &&
    availableCharacters.value.some((character) => character.name === frontendSettings.value.characterName) &&
    !installedCharacterNames.value.includes(frontendSettings.value.characterName)
  ) {
    installedCharacterNames.value = [...installedCharacterNames.value, frontendSettings.value.characterName];
    saveInstalledCharacterNames(installedCharacterNames.value);
  }

  syncCharacterEmotion();

  if (
    frontendSettings.value.characterName &&
    !availableCharacters.value.some((character) => character.name === frontendSettings.value.characterName)
  ) {
    frontendSettings.value = normalizeFrontendSettings({
      ...frontendSettings.value,
      characterName: ''
    });
  }
};

const updateFrontendSettings = (nextSettings: FrontendSettings) => {
  applyFrontendSettings(nextSettings);
};

const resetFrontendSettings = () => {
  applyFrontendSettings(externalDefaultSettings.value ?? getDefaultFrontendSettings(frontendSettings.value.themeId));
};

const updateBackendBaseUrl = (baseUrl: string) => {
  frontendSettings.value = normalizeFrontendSettings({
    ...frontendSettings.value,
    backendBaseUrl: baseUrl
  });
};

const updateTheme = (themeId: ThemeId) => {
  applyFrontendSettings({
    ...frontendSettings.value,
    themeId
  });
};

const installCharacter = (characterName: string) => {
  if (installedCharacterNames.value.includes(characterName)) {
    frontendSettings.value = normalizeFrontendSettings({
      ...frontendSettings.value,
      characterName
    });
    return;
  }

  installedCharacterNames.value = [...installedCharacterNames.value, characterName];
  saveInstalledCharacterNames(installedCharacterNames.value);
  frontendSettings.value = normalizeFrontendSettings({
    ...frontendSettings.value,
    characterName
  });
};

const openSettings = () => {
  activeSettingsView.value = 'frontend';
  isSettingsOpen.value = true;
};

const closeSettings = () => {
  isSettingsOpen.value = false;
};

const scrollChatToBottom = () => {
  if (typeof document === 'undefined') {
    return;
  }

  const chatScroller = document.querySelector<HTMLElement>('[data-chat-scroller="main"]');
  if (!chatScroller) {
    return;
  }

  chatScroller.scrollTo({
    top: chatScroller.scrollHeight,
    behavior: 'smooth'
  });
};

const scheduleAssistantMessages = (nextSteps: AssistantReplyStep[]) =>
  new Promise<void>((resolve) => {
    if (!nextSteps.length) {
      isAssistantTyping.value = false;
      resolve();
      return;
    }

    if (!frontendSettings.value.shortModeEnabled) {
      const mergedStep = mergeAssistantReplySteps(nextSteps);
      if (!mergedStep) {
        isAssistantTyping.value = false;
        resolve();
        return;
      }

      const responseTimer = setTimeout(() => {
        appendAssistantStep(mergedStep);
        pendingResponseTimers.delete(responseTimer);
        isAssistantTyping.value = false;
        resolve();
      }, calculateAssistantMessageDelay(mergedStep.content || '...'));

      pendingResponseTimers.add(responseTimer);
      return;
    }

    let accumulatedDelay = 0;

    nextSteps.forEach((step, index) => {
      accumulatedDelay += calculateAssistantMessageDelay(step.content || '...');

      const responseTimer = setTimeout(() => {
        appendAssistantStep(step);
        pendingResponseTimers.delete(responseTimer);

        if (index === nextSteps.length - 1) {
          isAssistantTyping.value = false;
          resolve();
        }
      }, accumulatedDelay);

      pendingResponseTimers.add(responseTimer);
    });
  });

const extractReplySteps = (payload: unknown) => {
  const replySource = payload && typeof payload === 'object' && Array.isArray((payload as Record<string, unknown>).reply)
    ? (payload as Record<string, unknown>).reply
    : payload;
  const items = Array.isArray(replySource) ? replySource : [replySource];

  return items
    .map((item) => extractSentenceContent(item))
    .filter(Boolean)
    .map((sentence) => createAssistantReplyStep(sentence))
    .filter((step): step is AssistantReplyStep => Boolean(step));
};

const closeLiveMessages = () => {
  liveMessageSource?.close();
  liveMessageSource = undefined;
};

const closeTtsAudio = () => {
  ttsAudioConnectionVersion += 1;
  ttsAudioSource?.close();
  ttsAudioSource = undefined;
  ttsPcmWorkletNode?.port.postMessage({ type: 'reset' });
  ttsNextPlayTime = 0;
  ttsAudioPlaybackChain = Promise.resolve();
};

const connectLiveMessages = () => {
  if (typeof window === 'undefined' || typeof window.EventSource === 'undefined') {
    return;
  }

  closeLiveMessages();
  const source = new EventSource(getChatLiveUrl());
  source.addEventListener('rp-message', (event) => {
    try {
      const payload = JSON.parse((event as MessageEvent<string>).data) as { content?: unknown; reply?: unknown };
      const replySteps = extractReplySteps(payload.reply ?? payload.content ?? payload);
      if (!replySteps.length) {
        return;
      }
      isAssistantTyping.value = true;
      void scheduleAssistantMessages(replySteps);
    } catch {
      // EventSource 会自动重连；单条格式异常不应打断后续主动消息。
    }
  });
  liveMessageSource = source;
};

const ensureTtsAudioContext = async () => {
  if (typeof window === 'undefined') {
    return null;
  }
  const AudioContextCtor = window.AudioContext
    || (window as Window & typeof globalThis & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!AudioContextCtor) {
    return null;
  }
  if (!ttsAudioContext) {
    ttsAudioContext = new AudioContextCtor();
  }
  if (ttsAudioContext.state === 'suspended') {
    await ttsAudioContext.resume().catch(() => undefined);
  }
  return ttsAudioContext;
};

const ensureTtsPcmWorklet = async (audioContext: AudioContext) => {
  if (!audioContext.audioWorklet) {
    return null;
  }
  if (ttsPcmWorkletNode) {
    return ttsPcmWorkletNode;
  }
  if (!ttsPcmWorkletReady) {
    const baseUrl = import.meta.env.BASE_URL.endsWith('/')
      ? import.meta.env.BASE_URL
      : `${import.meta.env.BASE_URL}/`;
    ttsPcmWorkletReady = audioContext.audioWorklet
      .addModule(`${baseUrl}tts-pcm-worklet.js`)
      .then(() => {
        const node = new AudioWorkletNode(audioContext, 'tts-pcm-player', {
          numberOfInputs: 0,
          numberOfOutputs: 1,
          outputChannelCount: [2],
          processorOptions: {
            tempo: TTS_PLAYBACK_TEMPO
          }
        });
        node.connect(audioContext.destination);
        ttsPcmWorkletNode = node;
        return node;
      })
      .catch(() => null);
  }
  return ttsPcmWorkletReady;
};

const base64ToArrayBuffer = (base64: string) => {
  const binary = window.atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes.buffer;
};

const decodePcmS16Le = (audioBase64: string) => {
  const bytes = new Uint8Array(base64ToArrayBuffer(audioBase64));
  const sampleCount = Math.floor(bytes.byteLength / 2);
  const samples = new Float32Array(sampleCount);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (let index = 0; index < sampleCount; index += 1) {
    samples[index] = view.getInt16(index * 2, true) / 32768;
  }
  return samples;
};

const resamplePcm = (samples: Float32Array, sourceSampleRate: number, targetSampleRate: number) => {
  if (!sourceSampleRate || sourceSampleRate === targetSampleRate || samples.length <= 1) {
    return samples;
  }
  const outputLength = Math.max(1, Math.round(samples.length * targetSampleRate / sourceSampleRate));
  const output = new Float32Array(outputLength);
  const ratio = sourceSampleRate / targetSampleRate;
  for (let index = 0; index < outputLength; index += 1) {
    const sourceIndex = index * ratio;
    const leftIndex = Math.floor(sourceIndex);
    const rightIndex = Math.min(leftIndex + 1, samples.length - 1);
    const fraction = sourceIndex - leftIndex;
    output[index] = samples[leftIndex] * (1 - fraction) + samples[rightIndex] * fraction;
  }
  return output;
};

const audioBufferToMonoPcm = (audioBuffer: AudioBuffer) => {
  const samples = new Float32Array(audioBuffer.length);
  if (audioBuffer.numberOfChannels <= 1) {
    audioBuffer.copyFromChannel(samples, 0);
    return samples;
  }

  const channelSamples = new Float32Array(audioBuffer.length);
  for (let channel = 0; channel < audioBuffer.numberOfChannels; channel += 1) {
    audioBuffer.copyFromChannel(channelSamples, channel);
    for (let index = 0; index < samples.length; index += 1) {
      samples[index] += channelSamples[index] / audioBuffer.numberOfChannels;
    }
  }
  return samples;
};

const pushTtsPcmSamples = async (
  samples: Float32Array,
  sourceSampleRate: number,
  connectionVersion: number
) => {
  if (!samples.length || connectionVersion !== ttsAudioConnectionVersion) {
    return false;
  }

  const audioContext = await ensureTtsAudioContext();
  if (!audioContext || connectionVersion !== ttsAudioConnectionVersion) {
    return false;
  }

  const workletNode = await ensureTtsPcmWorklet(audioContext);
  if (!workletNode || connectionVersion !== ttsAudioConnectionVersion) {
    return false;
  }

  const outputSamples = resamplePcm(samples, sourceSampleRate || audioContext.sampleRate, audioContext.sampleRate);
  workletNode.port.postMessage({ type: 'push', samples: outputSamples }, [outputSamples.buffer]);
  return true;
};

const flushTtsPcmAudio = async (connectionVersion: number) => {
  if (connectionVersion !== ttsAudioConnectionVersion) {
    return;
  }

  const audioContext = await ensureTtsAudioContext();
  if (!audioContext || connectionVersion !== ttsAudioConnectionVersion) {
    return;
  }

  const workletNode = await ensureTtsPcmWorklet(audioContext);
  if (workletNode && connectionVersion === ttsAudioConnectionVersion) {
    workletNode.port.postMessage({ type: 'flush' });
  }
};

const calculateTtsFadeDuration = (duration: number) => {
  if (!Number.isFinite(duration) || duration <= 0) {
    return 0;
  }
  return Math.min(TTS_CHUNK_CROSSFADE_SECONDS, duration / 4);
};

const playTtsAudioBuffer = async (audioBuffer: AudioBuffer, connectionVersion: number) => {
  if (connectionVersion !== ttsAudioConnectionVersion) {
    return;
  }
  const audioContext = await ensureTtsAudioContext();
  if (!audioContext || connectionVersion !== ttsAudioConnectionVersion) {
    return;
  }

  const source = audioContext.createBufferSource();
  const gainNode = audioContext.createGain();
  source.buffer = audioBuffer;
  source.connect(gainNode);
  gainNode.connect(audioContext.destination);

  const fadeDuration = calculateTtsFadeDuration(audioBuffer.duration);
  const startAt = Math.max(audioContext.currentTime + TTS_AUDIO_START_MARGIN_SECONDS, ttsNextPlayTime);
  const endAt = startAt + audioBuffer.duration;
  if (fadeDuration > 0) {
    const fadeInEnd = Math.min(startAt + fadeDuration, endAt);
    const fadeOutStart = Math.max(startAt, endAt - fadeDuration);
    gainNode.gain.setValueAtTime(0, startAt);
    gainNode.gain.linearRampToValueAtTime(1, fadeInEnd);
    if (fadeOutStart > fadeInEnd) {
      gainNode.gain.setValueAtTime(1, fadeOutStart);
    }
    gainNode.gain.linearRampToValueAtTime(0, endAt);
  } else {
    gainNode.gain.setValueAtTime(1, startAt);
  }
  source.start(startAt);
  ttsNextPlayTime = Math.max(startAt, endAt - fadeDuration);
};

const playTtsAudio = async (audioBase64: string, connectionVersion: number) => {
  if (!audioBase64 || connectionVersion !== ttsAudioConnectionVersion) {
    return;
  }
  const audioContext = await ensureTtsAudioContext();
  if (!audioContext || connectionVersion !== ttsAudioConnectionVersion) {
    return;
  }

  const audioBuffer = await audioContext.decodeAudioData(base64ToArrayBuffer(audioBase64));
  if (await pushTtsPcmSamples(audioBufferToMonoPcm(audioBuffer), audioBuffer.sampleRate, connectionVersion)) {
    return;
  }
  await playTtsAudioBuffer(audioBuffer, connectionVersion);
};

const queueTtsAudio = (audioBase64: string, connectionVersion: number) => {
  // decodeAudioData 是异步的；串行化后才能保证后到的音频不会抢先排进播放时间线。
  ttsAudioPlaybackChain = ttsAudioPlaybackChain
    .then(() => playTtsAudio(audioBase64, connectionVersion))
    .catch(() => undefined);
};

const enqueueTtsPcmAudio = async (
  payload: { audioBase64?: string; sampleRate?: number },
  connectionVersion: number
) => {
  if (!payload.audioBase64 || connectionVersion !== ttsAudioConnectionVersion) {
    return;
  }
  const audioContext = await ensureTtsAudioContext();
  if (!audioContext || connectionVersion !== ttsAudioConnectionVersion) {
    return;
  }

  const sourceSampleRate = payload.sampleRate || audioContext.sampleRate;
  const decodedSamples = decodePcmS16Le(payload.audioBase64);
  if (await pushTtsPcmSamples(decodedSamples, sourceSampleRate, connectionVersion)) {
    return;
  }

  const samples = resamplePcm(decodedSamples, sourceSampleRate, audioContext.sampleRate);
  const audioBuffer = audioContext.createBuffer(1, samples.length, audioContext.sampleRate);
  audioBuffer.copyToChannel(samples, 0);
  await playTtsAudioBuffer(audioBuffer, connectionVersion);
};

const queueTtsPcmAudio = (
  payload: { audioBase64?: string; sampleRate?: number },
  connectionVersion: number
) => {
  ttsAudioPlaybackChain = ttsAudioPlaybackChain
    .then(() => enqueueTtsPcmAudio(payload, connectionVersion))
    .catch(() => undefined);
};

const queueTtsPcmFlush = (connectionVersion: number) => {
  ttsAudioPlaybackChain = ttsAudioPlaybackChain
    .then(() => flushTtsPcmAudio(connectionVersion))
    .catch(() => undefined);
};

const isPcmTtsAudio = (mediaType?: string) =>
  (mediaType ?? '').toLowerCase().startsWith('audio/pcm');

const connectTtsAudio = () => {
  if (typeof window === 'undefined' || typeof window.EventSource === 'undefined') {
    return;
  }

  closeTtsAudio();
  const connectionVersion = ttsAudioConnectionVersion;
  const source = new EventSource(getTtsLiveUrl());
  source.addEventListener('tts-audio', (event) => {
    try {
      const payload = JSON.parse((event as MessageEvent<string>).data) as {
        audioBase64?: string;
        mediaType?: string;
        sampleRate?: number;
        finalChunk?: boolean;
      };
      if (payload.finalChunk) {
        queueTtsPcmFlush(connectionVersion);
        return;
      }
      if (isPcmTtsAudio(payload.mediaType)) {
        queueTtsPcmAudio(payload, connectionVersion);
        return;
      }
      queueTtsAudio(payload.audioBase64 ?? '', connectionVersion);
    } catch {
      // 音频 SSE 单帧异常不影响后续播放。
    }
  });
  ttsAudioSource = source;
};

const sendMessage = async () => {
  const content = userInput.value.trim();
  if (!content || isSendLocked.value) {
    return;
  }

  void ensureTtsAudioContext();
  isSendLocked.value = true;
  messages.value.push({
    id: Date.now(),
    role: 'user',
    content
  });
  userInput.value = '';
  isAssistantTyping.value = true;

  try {
    const response = await fetch(getChatApiUrl(), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({
        message: content,
        sessionId: frontendSettings.value.sessionId,
        characterName: getRoleNameForRequest(),
        roleName: getRoleNameForRequest(),
        shortMode: frontendSettings.value.shortModeEnabled
      })
    });

    const responsePayload = await response.json().catch(() => ({}));

    if (!response.ok) {
      const errorMessage = createAssistantMessage(
        typeof (responsePayload as Record<string, unknown>)?.message === 'string'
          ? (responsePayload as Record<string, string>).message
          : `请求失败：${response.status} ${response.statusText}`.trim()
      );

      if (errorMessage) {
        messages.value.push(errorMessage);
      }
      isAssistantTyping.value = false;
      return;
    }

    const replySteps = extractReplySteps(responsePayload);

    if (!replySteps.length) {
      const emptyReplyMessage = createAssistantMessage('未收到有效回复。');
      if (emptyReplyMessage) {
        messages.value.push(emptyReplyMessage);
      }
      isAssistantTyping.value = false;
      return;
    }

    await scheduleAssistantMessages(replySteps);
  } catch (error) {
    const errorMessage = createAssistantMessage(
      error instanceof Error
        ? `请求失败：${error.message}`
        : '请求失败：无法连接到后端。'
    );

    if (errorMessage) {
      messages.value.push(errorMessage);
    }
    isAssistantTyping.value = false;
  } finally {
    isSendLocked.value = false;
  }
};

const reportTypingActivity = () => {
  if (typeof window === 'undefined') {
    return;
  }

  try {
    void fetch(getChatTypingUrl(), {
      method: 'POST',
      headers: {
        Accept: 'application/json'
      },
      keepalive: true
    }).catch(() => {
      // typing 心跳失败时保留正常聊天流程，下一次输入会重新上报。
    });
  } catch {
    // 后端地址尚未配置或格式无效时，不让 typing 心跳影响输入框。
  }
};

const updateUserInput = (value: string) => {
  userInput.value = value;
  reportTypingActivity();
};

watch(
  () => frontendSettings.value.moteCount,
  (count) => {
    stardustParticles.value = createStardustParticles(count);
  },
  { immediate: true }
);

watch(
  () => frontendSettings.value.characterName,
  () => {
    syncCharacterEmotion();
  }
);

watch(
  [
    () => frontendSettings.value.backendBaseUrl,
    () => frontendSettings.value.sessionId,
    () => frontendSettings.value.characterName
  ],
  () => {
    connectLiveMessages();
    connectTtsAudio();
  }
);

watch(
  [() => messages.value.length, isAssistantTyping],
  () => {
    void nextTick(() => {
      requestAnimationFrame(() => {
        scrollChatToBottom();
      });
    });
  },
  { flush: 'post' }
);

onMounted(async () => {
  externalDefaultSettings.value = await applyExternalFrontendDefaults(frontendSettings);
  startBootSequence();
  await loadCharacters();
  connectLiveMessages();
  connectTtsAudio();
});

onBeforeUnmount(() => {
  closeLiveMessages();
  closeTtsAudio();
  clearBootTimers();
  pendingResponseTimers.forEach((timerId) => clearTimeout(timerId));
  pendingResponseTimers.clear();
});
</script>

<template>
  <div class="relative min-h-screen">
    <component
      :is="activeTheme.sceneComponent"
      :workspace-name="workspaceName"
      :operator-name="operatorName"
      :backend-base-url="frontendSettings.backendBaseUrl"
      :messages="messages"
      :user-input="userInput"
      :is-booting="isBooting"
      :show-title="showTitle"
      :stardust-particles="stardustParticles"
      :character-name="activeCharacter?.name ?? ''"
      :character-image-url="activeCharacterImageUrl"
      :active-character-emotion="activeCharacterEmotion"
      :is-assistant-typing="isAssistantTyping"
      :is-send-disabled="isSendLocked"
      :theme-text="activeTheme.text"
      @open-settings="openSettings"
      @update:user-input="updateUserInput"
      @send-message="sendMessage"
    />

    <Transition name="settings-fade">
      <div
        v-if="isSettingsOpen"
        :class="['fixed inset-0 z-[120] p-4 backdrop-blur-sm md:p-8', activeTheme.settingsOverlayClass]"
        @click.self="closeSettings"
      >
        <component
          :is="activeTheme.settingsShellComponent"
          :active-view="activeSettingsView"
          @close="closeSettings"
          @update:view="activeSettingsView = $event"
        >
          <FrontendSettingsPanel
            v-if="activeSettingsView === 'frontend'"
            :settings="frontendSettings"
            :theme-options="themeOptions"
            :available-characters="availableCharacters"
            :imported-characters="importedCharacters"
            @update:settings="updateFrontendSettings"
            @change-theme="updateTheme"
            @install-character="installCharacter"
            @reset="resetFrontendSettings"
          />
          <BackendSettingsPanel
            v-else
            :base-url="frontendSettings.backendBaseUrl"
            @update:base-url="updateBackendBaseUrl"
          />
        </component>
      </div>
    </Transition>
  </div>
</template>

<style>
body {
  margin: 0;
  overflow: hidden;
}

.settings-fade-enter-active,
.settings-fade-leave-active {
  transition: opacity 0.3s ease;
}

.settings-fade-enter-from,
.settings-fade-leave-to {
  opacity: 0;
}

::selection {
  background: #E85D04;
  color: white;
}
</style>
