/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { computed, onBeforeUnmount, ref } from 'vue';
import realtimeVoiceService, {
  type RealtimeVoiceReadiness,
  type RealtimeVoiceSession
} from '@/views/ai-agent/services/realtimeVoice';
import type { ModelConfigId } from '@/views/ai-agent/services/modelConfig';

type RealtimeWsEvent = {
  event?: string;
  data?: Record<string, any>;
};

type RealtimeVoiceConversationOptions = {
  getAgentId: () => ModelConfigId | undefined | null;
  getThreadId: () => ModelConfigId | undefined | null;
  getChatModelConfigId?: () => ModelConfigId | undefined | null;
  onCompleted?: () => void | Promise<void>;
};

const VAD_INTERVAL_MS = 120;
const VAD_SPEECH_RMS = 0.018;
const VAD_SILENCE_COMMIT_MS = 900;
const VAD_MAX_TURN_MS = 9000;
const MIN_COMMIT_BYTES = 4096;

export function useRealtimeVoiceConversation(options: RealtimeVoiceConversationOptions) {
  const active = ref(false);
  const connecting = ref(false);
  const processing = ref(false);
  const readinessLoading = ref(false);
  const readiness = ref<RealtimeVoiceReadiness>({ ready: false, missingItems: [] });
  const session = ref<RealtimeVoiceSession | null>(null);
  const transcriptText = ref('');
  const answerText = ref('');
  const errorText = ref('');
  const statusText = ref('未开始实时对话');

  const mediaStream = ref<MediaStream | null>(null);
  const mediaRecorder = ref<MediaRecorder | null>(null);
  const ws = ref<WebSocket | null>(null);
  const audioContext = ref<AudioContext | null>(null);
  const analyser = ref<AnalyserNode | null>(null);
  const pcmSource = ref<MediaStreamAudioSourceNode | null>(null);
  const pcmProcessor = ref<ScriptProcessorNode | null>(null);
  const vadTimer = ref<number | null>(null);
  const currentAudio = ref<HTMLAudioElement | null>(null);
  const currentPcmSource = ref<AudioBufferSourceNode | null>(null);

  let pendingBytes = 0;
  let pendingSpeech = false;
  let turnStartedAt = 0;
  let lastSpeechAt = 0;
  let playbackQueue: Blob[] = [];
  let playbackRunning = false;
  let stoppedByUser = false;

  const missingItems = computed(() => readiness.value.missingItems ?? []);
  const ready = computed(() => Boolean(readiness.value.ready));
  const busy = computed(() => connecting.value || processing.value);

  const loadReadiness = async () => {
    const agentId = options.getAgentId();
    readinessLoading.value = true;
    try {
      readiness.value = await realtimeVoiceService.readiness(agentId ?? undefined);
      return readiness.value;
    } finally {
      readinessLoading.value = false;
    }
  };

  const generateDefaultConfig = async () => {
    const agentId = options.getAgentId();
    readinessLoading.value = true;
    try {
      readiness.value = await realtimeVoiceService.generateDefault(agentId ?? undefined);
      return readiness.value;
    } finally {
      readinessLoading.value = false;
    }
  };

  const start = async () => {
    if (active.value || connecting.value) {
      return true;
    }
    errorText.value = '';
    transcriptText.value = '';
    answerText.value = '';
    stoppedByUser = false;
    connecting.value = true;
    statusText.value = '正在检查实时语音配置';
    try {
      const currentReadiness = await loadReadiness();
      if (!currentReadiness.ready) {
        statusText.value = '实时语音配置未就绪';
        return false;
      }
      if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
        errorText.value = '当前浏览器不支持实时语音采集';
        statusText.value = errorText.value;
        return false;
      }
      const agentId = options.getAgentId();
      const threadId = options.getThreadId();
      const realtimeSession = await realtimeVoiceService.create({
        agentId: agentId ?? undefined,
        threadId: threadId ?? undefined,
        realtimeConfigId: currentReadiness.realtimeConfigId,
        runtimeMode: currentReadiness.runtimeMode || 'PIPELINE',
        mode: 'CONTINUOUS_VOICE',
        transport: 'WEBSOCKET',
        chatModelConfigId: options.getChatModelConfigId?.() ?? undefined
      });
      session.value = realtimeSession;
      await unlockAudioContext();
      await openWebSocket(realtimeSession);
      await startCapture();
      active.value = true;
      statusText.value = '正在聆听';
      return true;
    } catch (error) {
      errorText.value = getErrorMessage(error, '启动实时对话失败');
      statusText.value = errorText.value;
      await stop();
      return false;
    } finally {
      connecting.value = false;
    }
  };

  const stop = async () => {
    stoppedByUser = true;
    active.value = false;
    processing.value = false;
    stopVad();
    stopCapture();
    stopPlayback();
    const socket = ws.value;
    ws.value = null;
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ event: 'session.close' }));
      window.setTimeout(() => socket.close(), 80);
    } else if (socket && socket.readyState === WebSocket.CONNECTING) {
      socket.close();
    }
    if (session.value?.id) {
      realtimeVoiceService.delete(session.value.id).catch(() => undefined);
    }
    session.value = null;
    statusText.value = '实时对话已结束';
  };

  const interrupt = () => {
    processing.value = false;
    pendingBytes = 0;
    pendingSpeech = false;
    turnStartedAt = 0;
    stopPlayback();
    const socket = ws.value;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ event: 'response.cancel' }));
    }
    statusText.value = active.value ? '已打断，继续聆听' : '已打断';
  };

  const commitCurrentSegment = () => {
    const socket = ws.value;
    const recorder = mediaRecorder.value;
    if (!active.value || processing.value || !socket || socket.readyState !== WebSocket.OPEN) {
      return;
    }
    if (pendingBytes < MIN_COMMIT_BYTES) {
      pendingBytes = 0;
      pendingSpeech = false;
      turnStartedAt = 0;
      return;
    }
    const turnId = createTurnId();
    processing.value = true;
    statusText.value = '正在识别本轮语音';
    socket.send(
      JSON.stringify({
        event: 'input_audio.commit',
        turnId,
        contentType: isRealtimeSession() ? `audio/pcm;rate=${inputSampleRate()}` : recorder?.mimeType || 'audio/webm'
      })
    );
    pendingBytes = 0;
    pendingSpeech = false;
    turnStartedAt = 0;
  };

  const openWebSocket = async (realtimeSession: RealtimeVoiceSession) => {
    await new Promise<void>((resolve, reject) => {
      const socket = new WebSocket(realtimeVoiceService.buildWebSocketUrl(realtimeSession));
      socket.binaryType = 'blob';
      ws.value = socket;
      const timer = window.setTimeout(() => reject(new Error('实时语音 WebSocket 连接超时')), 10000);
      socket.onopen = () => {
        window.clearTimeout(timer);
        socket.send(JSON.stringify({ event: 'session.start' }));
        resolve();
      };
      socket.onerror = () => {
        window.clearTimeout(timer);
        reject(new Error('实时语音 WebSocket 连接失败'));
      };
      socket.onmessage = event => {
        Promise.resolve(handleWsMessage(event)).catch(() => undefined);
      };
      socket.onclose = () => {
        if (!stoppedByUser) {
          active.value = false;
          processing.value = false;
          statusText.value = '实时语音连接已关闭';
        }
      };
    });
  };

  const startCapture = async () => {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true
      }
    });
    mediaStream.value = stream;
    if (isRealtimeSession()) {
      startPcmCapture(stream);
      startVad(stream);
      return;
    }
    const mimeType = resolveAudioMimeType();
    const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
    recorder.ondataavailable = event => {
      if (event.data.size <= 0 || !active.value) {
        return;
      }
      const socket = ws.value;
      if (socket?.readyState === WebSocket.OPEN) {
        pendingBytes += event.data.size;
        socket.send(event.data);
      }
    };
    mediaRecorder.value = recorder;
    recorder.start(250);
    startVad(stream);
  };

  const startVad = (stream: MediaStream) => {
    const context = audioContext.value;
    if (!context) {
      return;
    }
    const source = context.createMediaStreamSource(stream);
    const nextAnalyser = context.createAnalyser();
    nextAnalyser.fftSize = 2048;
    source.connect(nextAnalyser);
    analyser.value = nextAnalyser;
    const samples = new Uint8Array(nextAnalyser.fftSize);
    vadTimer.value = window.setInterval(() => {
      nextAnalyser.getByteTimeDomainData(samples);
      const rms = computeRms(samples);
      const now = Date.now();
      if (rms >= VAD_SPEECH_RMS) {
        pendingSpeech = true;
        lastSpeechAt = now;
        if (!turnStartedAt) {
          turnStartedAt = now;
        }
        statusText.value = processing.value ? '正在处理上一轮，继续接收语音' : '检测到说话';
      }
      if (!pendingSpeech || processing.value) {
        return;
      }
      if (now - lastSpeechAt >= VAD_SILENCE_COMMIT_MS || now - turnStartedAt >= VAD_MAX_TURN_MS) {
        commitCurrentSegment();
      }
    }, VAD_INTERVAL_MS);
  };

  const handleWsMessage = async (event: MessageEvent) => {
    if (event.data instanceof Blob) {
      enqueuePlayback(event.data);
      return;
    }
    if (typeof event.data !== 'string') {
      return;
    }
    const payload = JSON.parse(event.data) as RealtimeWsEvent;
    const data = payload.data ?? {};
    switch (payload.event) {
      case 'session.created':
      case 'session.started':
        statusText.value = '实时语音已连接';
        break;
      case 'input_audio.processing':
        processing.value = true;
        statusText.value = '正在处理语音';
        break;
      case 'asr.final':
        transcriptText.value = String(data.text || '');
        statusText.value = '已识别，正在生成回答';
        break;
      case 'agent.text.done':
        answerText.value = String(data.text || '');
        statusText.value = '回答已生成，正在播放';
        break;
      case 'agent.text.delta':
        answerText.value = String(data.text || '');
        statusText.value = '正在生成回答';
        break;
      case 'audio.output.done':
        processing.value = false;
        statusText.value = active.value ? '正在聆听' : '播放完成';
        await options.onCompleted?.();
        break;
      case 'response.cancelled':
        processing.value = false;
        statusText.value = '已打断，继续聆听';
        break;
      case 'error':
        processing.value = false;
        errorText.value = String(data.message || data.detail || '实时语音处理失败');
        statusText.value = errorText.value;
        break;
      default:
        break;
    }
  };

  const enqueuePlayback = (blob: Blob) => {
    playbackQueue.push(blob);
    Promise.resolve(pumpPlayback()).catch(() => undefined);
  };

  const pumpPlayback = async () => {
    if (playbackRunning) {
      return;
    }
    playbackRunning = true;
    try {
      const queue = playbackQueue;
      playbackQueue = [];
      await queue.reduce<Promise<void>>((chain, blob) => {
        return chain.then(() => playBlob(blob));
      }, Promise.resolve());
    } finally {
      playbackRunning = false;
      if (playbackQueue.length > 0) {
        Promise.resolve(pumpPlayback()).catch(() => undefined);
      }
    }
  };

  const playBlob = async (blob: Blob) => {
    if (isRealtimeSession()) {
      await playPcm16Blob(blob);
      return;
    }
    const url = URL.createObjectURL(blob);
    try {
      await new Promise<void>((resolve, reject) => {
        const audio = new Audio(url);
        currentAudio.value = audio;
        audio.onended = () => resolve();
        audio.onerror = () => reject(new Error('实时语音播放失败'));
        audio.play().catch(reject);
      });
    } finally {
      URL.revokeObjectURL(url);
      currentAudio.value = null;
    }
  };

  const stopPlayback = () => {
    playbackQueue = [];
    const audio = currentAudio.value;
    if (audio) {
      audio.pause();
      audio.src = '';
      currentAudio.value = null;
    }
    const pcmAudio = currentPcmSource.value;
    if (pcmAudio) {
      currentPcmSource.value = null;
      try {
        pcmAudio.stop();
      } catch {
        // ignore already stopped node
      }
      pcmAudio.disconnect();
    }
  };

  const unlockAudioContext = async () => {
    const AudioContextCtor = window.AudioContext || (window as any).webkitAudioContext;
    if (!AudioContextCtor) {
      return;
    }
    const context = audioContext.value ?? new AudioContextCtor();
    audioContext.value = context;
    if (context.state === 'suspended') {
      await context.resume();
    }
  };

  const stopCapture = () => {
    stopPcmCapture();
    const recorder = mediaRecorder.value;
    if (recorder && recorder.state !== 'inactive') {
      recorder.stop();
    }
    mediaRecorder.value = null;
    mediaStream.value?.getTracks().forEach(track => track.stop());
    mediaStream.value = null;
  };

  const startPcmCapture = (stream: MediaStream) => {
    const context = audioContext.value;
    const socket = ws.value;
    if (!context || !socket) {
      return;
    }
    const source = context.createMediaStreamSource(stream);
    const processor = context.createScriptProcessor(2048, 1, 1);
    processor.onaudioprocess = event => {
      event.outputBuffer.getChannelData(0).fill(0);
      if (!active.value || socket.readyState !== WebSocket.OPEN) {
        return;
      }
      const input = event.inputBuffer.getChannelData(0);
      const resampled = resampleFloat32(input, context.sampleRate, inputSampleRate());
      const pcm = float32ToPcm16(resampled);
      if (pcm.byteLength > 0) {
        pendingBytes += pcm.byteLength;
        socket.send(pcm);
      }
    };
    source.connect(processor);
    processor.connect(context.destination);
    pcmSource.value = source;
    pcmProcessor.value = processor;
  };

  const stopPcmCapture = () => {
    const processor = pcmProcessor.value;
    if (processor) {
      processor.disconnect();
      processor.onaudioprocess = null;
      pcmProcessor.value = null;
    }
    const source = pcmSource.value;
    if (source) {
      source.disconnect();
      pcmSource.value = null;
    }
  };

  const stopVad = () => {
    if (vadTimer.value !== null) {
      window.clearInterval(vadTimer.value);
      vadTimer.value = null;
    }
    analyser.value = null;
  };

  const resolveAudioMimeType = () => {
    if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
      return undefined;
    }
    return ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'].find(type =>
      MediaRecorder.isTypeSupported(type)
    );
  };

  const playPcm16Blob = async (blob: Blob) => {
    const context = audioContext.value;
    if (!context) {
      return;
    }
    if (context.state === 'suspended') {
      await context.resume();
    }
    const arrayBuffer = await blob.arrayBuffer();
    if (arrayBuffer.byteLength === 0) {
      return;
    }
    const int16 = new Int16Array(arrayBuffer);
    const audioBuffer = context.createBuffer(1, int16.length, outputSampleRate());
    const channel = audioBuffer.getChannelData(0);
    for (let index = 0; index < int16.length; index += 1) {
      channel[index] = Math.max(-1, Math.min(1, int16[index] / 32768));
    }
    const playbackSource = context.createBufferSource();
    await new Promise<void>(resolve => {
      const source = playbackSource;
      source.buffer = audioBuffer;
      source.connect(context.destination);
      currentPcmSource.value = source;
      source.onended = () => {
        if (currentPcmSource.value === source) {
          currentPcmSource.value = null;
        }
        resolve();
      };
      source.start();
    });
    playbackSource.disconnect();
  };

  const resampleFloat32 = (samples: Float32Array, sourceRate: number, targetRate: number) => {
    if (!targetRate || sourceRate === targetRate) {
      return samples;
    }
    const ratio = sourceRate / targetRate;
    const length = Math.max(1, Math.round(samples.length / ratio));
    const result = new Float32Array(length);
    for (let index = 0; index < length; index += 1) {
      const sourceIndex = index * ratio;
      const left = Math.floor(sourceIndex);
      const right = Math.min(samples.length - 1, left + 1);
      const weight = sourceIndex - left;
      result[index] = samples[left] * (1 - weight) + samples[right] * weight;
    }
    return result;
  };

  const float32ToPcm16 = (samples: Float32Array) => {
    const buffer = new ArrayBuffer(samples.length * 2);
    const view = new DataView(buffer);
    for (let index = 0; index < samples.length; index += 1) {
      const sample = Math.max(-1, Math.min(1, samples[index]));
      view.setInt16(index * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
    }
    return buffer;
  };

  const inputSampleRate = () => readiness.value.config?.inputSampleRate || 24000;

  const outputSampleRate = () => readiness.value.config?.outputSampleRate || 24000;

  const isRealtimeSession = () => session.value?.runtimeMode === 'REALTIME';

  const computeRms = (samples: Uint8Array) => {
    let sum = 0;
    for (const sample of samples) {
      const value = (sample - 128) / 128;
      sum += value * value;
    }
    return Math.sqrt(sum / samples.length);
  };

  const createTurnId = () => {
    return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `turn-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  };

  const getErrorMessage = (error: unknown, fallback: string) => {
    return error instanceof Error && error.message ? error.message : fallback;
  };

  onBeforeUnmount(() => {
    stop().catch(() => undefined);
    audioContext.value?.close().catch(() => undefined);
    audioContext.value = null;
  });

  return {
    active,
    connecting,
    processing,
    busy,
    ready,
    readiness,
    readinessLoading,
    missingItems,
    session,
    transcriptText,
    answerText,
    errorText,
    statusText,
    loadReadiness,
    generateDefaultConfig,
    start,
    stop,
    interrupt,
    commitCurrentSegment
  };
}
