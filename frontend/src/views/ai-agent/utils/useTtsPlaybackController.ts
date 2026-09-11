import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import AudioService from '@/views/ai-agent/services/audio';

type PlaybackTask = {
  id: number;
  text: string;
};

const STORAGE_KEY = 'aiAgentAnswerTtsEnabled';
const SENTENCE_ENDING_PATTERN = /([。！？!?；;]\s*|\n{2,})/;
const MIN_SEGMENT_LENGTH = 18;

const normalizeSpeechText = (text: string) => {
  return text
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!\[[^\]]*]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/[#>*_\-|]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
};

const splitReadySentences = (buffer: string) => {
  const ready: string[] = [];
  let remaining = buffer;
  let match = SENTENCE_ENDING_PATTERN.exec(remaining);
  while (match?.index !== undefined) {
    const endIndex = match.index + match[0].length;
    const sentence = remaining.slice(0, endIndex).trim();
    remaining = remaining.slice(endIndex);
    if (sentence.length >= MIN_SEGMENT_LENGTH || /[。！？!?；;]/.test(sentence)) {
      ready.push(sentence);
    } else {
      remaining = `${sentence}${remaining}`;
      break;
    }
    match = SENTENCE_ENDING_PATTERN.exec(remaining);
  }
  return {
    ready,
    remaining
  };
};

export function useTtsPlaybackController() {
  const enabled = ref(window.localStorage.getItem(STORAGE_KEY) === 'true');
  const playing = ref(false);
  const loading = ref(false);
  const queue = ref<PlaybackTask[]>([]);
  const buffer = ref('');
  const currentAudio = ref<HTMLAudioElement | null>(null);
  const currentUrl = ref('');
  const taskId = ref(0);
  const generation = ref(0);
  const warningShown = ref(false);

  const statusLabel = computed(() => {
    if (!enabled.value) {
      return '';
    }
    if (playing.value) {
      return '正在播报回答';
    }
    if (loading.value || queue.value.length > 0) {
      return '正在准备语音';
    }
    return '回答时语音播报已开启';
  });

  const revokeCurrentUrl = () => {
    if (currentUrl.value) {
      URL.revokeObjectURL(currentUrl.value);
      currentUrl.value = '';
    }
  };

  const setEnabled = (value: boolean) => {
    enabled.value = value;
    window.localStorage.setItem(STORAGE_KEY, String(value));
    if (!value) {
      stop();
    }
  };

  const stop = () => {
    generation.value += 1;
    queue.value = [];
    buffer.value = '';
    loading.value = false;
    playing.value = false;
    if (currentAudio.value) {
      currentAudio.value.pause();
      currentAudio.value.src = '';
      currentAudio.value = null;
    }
    revokeCurrentUrl();
  };

  const playNext = async (currentGeneration: number): Promise<void> => {
    if (!enabled.value || currentGeneration !== generation.value || playing.value) {
      return;
    }
    const task = queue.value.shift();
    if (!task) {
      loading.value = false;
      return;
    }
    loading.value = true;
    try {
      const blob = await AudioService.speech({ text: task.text });
      if (currentGeneration !== generation.value || !enabled.value) {
        return;
      }
      revokeCurrentUrl();
      const url = URL.createObjectURL(blob);
      currentUrl.value = url;
      const audio = new Audio(url);
      currentAudio.value = audio;
      playing.value = true;
      loading.value = false;
      await new Promise<void>((resolve, reject) => {
        audio.onended = () => resolve();
        audio.onerror = () => reject(new Error('语音播放失败'));
        audio.play().catch(reject);
      });
    } catch (error) {
      if (!warningShown.value && currentGeneration === generation.value) {
        warningShown.value = true;
        ElMessage.warning('语音播报暂不可用，文字回答不受影响。');
      }
      console.warn('TTS playback failed:', error);
    } finally {
      if (currentGeneration === generation.value) {
        playing.value = false;
        currentAudio.value = null;
        revokeCurrentUrl();
        void playNext(currentGeneration);
      }
    }
  };

  const enqueue = (text: string) => {
    const normalized = normalizeSpeechText(text);
    if (!enabled.value || !normalized) {
      return;
    }
    queue.value.push({
      id: taskId.value + 1,
      text: normalized
    });
    taskId.value += 1;
    void playNext(generation.value);
  };

  const appendText = (text: string) => {
    if (!enabled.value || !text) {
      return;
    }
    buffer.value += text;
    const segments = splitReadySentences(buffer.value);
    buffer.value = segments.remaining;
    segments.ready.forEach(enqueue);
  };

  const flush = () => {
    if (!enabled.value) {
      buffer.value = '';
      return;
    }
    const text = buffer.value.trim();
    buffer.value = '';
    if (text) {
      enqueue(text);
    }
  };

  const resetForNewAnswer = () => {
    warningShown.value = false;
    stop();
  };

  return {
    enabled,
    playing,
    loading,
    statusLabel,
    setEnabled,
    appendText,
    flush,
    stop,
    resetForNewAnswer
  };
}
