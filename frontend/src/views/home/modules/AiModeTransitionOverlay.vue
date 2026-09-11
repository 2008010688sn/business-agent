<template>
  <Teleport to="body">
    <Transition name="ai-mode-transition">
      <div v-if="visible" class="ai-mode-transition-overlay">
        <div class="singularity-lens" aria-hidden="true"></div>
        <div class="singularity-grid" aria-hidden="true"></div>
        <div class="workspace-fragments" aria-hidden="true">
          <span v-for="item in 10" :key="item" :style="{ '--i': item }"></span>
        </div>
        <div class="menu-absorb-cards" aria-hidden="true">
          <span v-for="card in menuAbsorbCards" :key="card.id" class="menu-absorb-card" :style="card.style">
            {{ card.label }}
          </span>
        </div>
        <div class="gravity-streams" aria-hidden="true">
          <span v-for="item in 22" :key="item" :style="{ '--i': item }"></span>
        </div>
        <div class="gravity-particles" aria-hidden="true">
          <span v-for="item in 36" :key="item" :style="{ '--i': item }"></span>
        </div>
        <div class="singularity-core-stage" aria-hidden="true">
          <div class="event-horizon-shadow"></div>
          <div class="event-horizon-ring ring-a"></div>
          <div class="event-horizon-ring ring-b"></div>
          <div class="event-horizon-ring ring-c"></div>
          <div class="event-horizon-core"></div>
        </div>
        <div class="singularity-burst" aria-hidden="true"></div>

        <div class="singularity-copy">
          <div class="transition-label">AI DATA SINGULARITY</div>
          <div class="transition-title">{{ timeout ? '智能体空间仍在准备中' : '正在进入智能体空间' }}</div>
          <div class="transition-subtitle">
            {{ timeout ? '初始化耗时较长，请确认后端服务和网络连接状态' : '正在加载可用 Agent 和会话信息' }}
          </div>
          <div class="transition-progress" aria-hidden="true">
            <span></span>
          </div>
          <div class="transition-step-text">
            <span>获取能力</span>
            <span>数据汇聚</span>
            <span>智能空间展开</span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { useRouteStore } from '@/store/modules/route';

defineOptions({ name: 'AiModeTransitionOverlay' });

const props = withDefaults(
  defineProps<{
    visible: boolean;
    duration?: number;
    autoFinish?: boolean;
    timeout?: boolean;
  }>(),
  {
    duration: 5000,
    autoFinish: true,
    timeout: false
  }
);

const emit = defineEmits<{
  finished: [];
}>();

type MenuAbsorbCard = {
  id: string;
  label: string;
  style: Record<string, string>;
};

const routeStore = useRouteStore();
const menuAbsorbCards = ref<MenuAbsorbCard[]>([]);

let timer: number | undefined;

const clearTimer = () => {
  if (timer !== undefined) {
    window.clearTimeout(timer);
    timer = undefined;
  }
};

const collectMenuLabels = (menus: App.Global.Menu[], labels: string[] = []) => {
  menus.forEach(menu => {
    const label = String(menu.label || '').trim();
    if (label && !labels.includes(label)) {
      labels.push(label);
    }
    if (menu.children?.length) {
      collectMenuLabels(menu.children, labels);
    }
  });

  return labels;
};

const collectDomMenuLabels = () => {
  const labels = Array.from(document.querySelectorAll<HTMLElement>('.el-menu-item, .el-sub-menu__title'))
    .map(item => item.textContent?.trim() || '')
    .filter(Boolean);

  return Array.from(new Set(labels));
};

const shuffle = <T,>(items: T[]) => {
  const copied = [...items];
  for (let index = copied.length - 1; index > 0; index -= 1) {
    const randomIndex = Math.floor(Math.random() * (index + 1));
    [copied[index], copied[randomIndex]] = [copied[randomIndex], copied[index]];
  }
  return copied;
};

const createMenuAbsorbCards = () => {
  const labels = collectMenuLabels(routeStore.menus);
  const sourceLabels = labels.length ? labels : collectDomMenuLabels();
  const selectedLabels = shuffle(sourceLabels).slice(0, 8);

  menuAbsorbCards.value = selectedLabels.map((label, index) => {
    const side = Math.random() > 0.5 ? 1 : -1;
    const x = side * (24 + Math.random() * 38);
    const y = -34 + Math.random() * 68;
    const rotate = -32 + Math.random() * 64;
    const midRotate = rotate * 0.55 + 22;
    const nearRotate = rotate * 0.2 + 96;
    const finalRotate = rotate * 0.1 + 180;
    const delay = index * 0.06 + Math.random() * 0.18;
    const duration = 1.72 + Math.random() * 0.42;

    return {
      id: `${label}-${index}-${Date.now()}`,
      label,
      style: {
        '--card-delay': `${delay}s`,
        '--card-duration': `${duration}s`,
        '--card-final-rotate': `${finalRotate}deg`,
        '--card-mid-rotate': `${midRotate}deg`,
        '--card-mid-x': `${x * 0.28}vw`,
        '--card-mid-y': `${y * 0.24}vh`,
        '--card-near-rotate': `${nearRotate}deg`,
        '--card-near-x': `${x * 0.06}vw`,
        '--card-near-y': `${y * 0.05}vh`,
        '--card-rotate': `${rotate}deg`,
        '--card-x': `${x}vw`,
        '--card-y': `${y}vh`
      }
    };
  });
};

watch(
  () => props.visible,
  visible => {
    clearTimer();
    if (visible) {
      createMenuAbsorbCards();
    } else {
      menuAbsorbCards.value = [];
    }
    if (visible && props.autoFinish) {
      timer = window.setTimeout(() => {
        emit('finished');
      }, props.duration);
    }
  },
  { immediate: true }
);

onBeforeUnmount(() => {
  clearTimer();
  menuAbsorbCards.value = [];
});
</script>

<style scoped>
.ai-mode-transition-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  color: var(--el-text-color-primary);
  background:
    radial-gradient(circle at 50% 50%, rgba(6, 18, 30, 0.34), transparent 24%),
    radial-gradient(circle at 50% 50%, rgba(27, 118, 255, 0.2), transparent 44%),
    linear-gradient(145deg, rgba(247, 252, 255, 0.95), rgba(232, 244, 255, 0.88) 52%, rgba(244, 253, 248, 0.94));
  backdrop-filter: blur(10px);
  perspective: 1100px;
}

.ai-mode-transition-overlay::before,
.ai-mode-transition-overlay::after,
.singularity-lens,
.singularity-grid,
.workspace-fragments,
.menu-absorb-cards,
.gravity-streams,
.gravity-particles,
.singularity-core-stage,
.singularity-burst {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.ai-mode-transition-overlay::before {
  background:
    linear-gradient(90deg, rgba(27, 118, 255, 0.06) 1px, transparent 1px),
    linear-gradient(rgba(27, 118, 255, 0.06) 1px, transparent 1px);
  background-size: 46px 46px;
  content: '';
  transform-origin: center;
  animation: page-field-warp 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
  mask-image: radial-gradient(circle at center, transparent 0 11%, #000 26%, #000 78%, transparent 100%);
}

.ai-mode-transition-overlay::after {
  background:
    radial-gradient(circle at 50% 50%, transparent 0 13%, rgba(255, 255, 255, 0.18) 17%, transparent 31%),
    conic-gradient(
      from 0deg,
      transparent,
      rgba(27, 118, 255, 0.12),
      rgba(38, 198, 111, 0.16),
      transparent,
      rgba(27, 118, 255, 0.12),
      transparent
    );
  content: '';
  opacity: 0;
  transform: scale(0.5) rotate(0deg);
  animation: lens-vortex 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
  mix-blend-mode: multiply;
}

.singularity-lens {
  background:
    radial-gradient(circle at 50% 50%, rgba(2, 8, 16, 0.88) 0 5%, rgba(4, 16, 29, 0.56) 9%, transparent 18%),
    radial-gradient(circle at 50% 50%, rgba(27, 118, 255, 0.22), transparent 28%),
    radial-gradient(circle at 50% 50%, transparent 0 16%, rgba(255, 255, 255, 0.34) 20%, transparent 28%);
  filter: saturate(1.18);
  opacity: 0;
  transform: scale(0.36);
  animation: singularity-lens-grow 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.singularity-grid {
  background:
    linear-gradient(90deg, transparent 0 16%, rgba(27, 118, 255, 0.12) 48%, transparent 84%),
    linear-gradient(115deg, transparent 0 34%, rgba(38, 198, 111, 0.12) 50%, transparent 66% 100%);
  opacity: 0;
  transform: scale(1.18);
  animation: singularity-grid-pull 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.workspace-fragments {
  z-index: 1;
  transform-style: preserve-3d;
}

.workspace-fragments span {
  position: absolute;
  top: calc(10% + (var(--i) % 5) * 16%);
  left: calc(6% + (var(--i) % 4) * 24%);
  width: calc(104px + (var(--i) % 3) * 32px);
  height: calc(48px + (var(--i) % 4) * 12px);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.76), rgba(255, 255, 255, 0.22)),
    linear-gradient(90deg, rgba(27, 118, 255, 0.14) 10px, transparent 10px 100%);
  border: 1px solid rgba(27, 118, 255, 0.15);
  border-radius: 6px;
  box-shadow:
    0 18px 46px rgba(31, 45, 61, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.72);
  opacity: 0;
  transform: translate3d(calc((var(--i) - 5) * 26px), 0, calc(var(--i) * -32px)) rotateY(-24deg)
    rotateZ(calc((var(--i) - 5) * 4deg));
  animation: fragment-absorb 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
  animation-delay: calc(var(--i) * 70ms);
}

.workspace-fragments span:nth-child(2n) {
  right: calc(7% + (var(--i) % 4) * 17%);
  left: auto;
  transform: translate3d(calc((var(--i) - 5) * -26px), 0, calc(var(--i) * -32px)) rotateY(24deg)
    rotateZ(calc((5 - var(--i)) * 4deg));
}

.menu-absorb-cards {
  z-index: 2;
  transform-style: preserve-3d;
}

.menu-absorb-card {
  position: absolute;
  top: 50%;
  left: 50%;
  display: inline-flex;
  align-items: center;
  min-width: 92px;
  max-width: 168px;
  height: 38px;
  padding: 0 16px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(245, 250, 255, 0.72)),
    linear-gradient(90deg, rgba(27, 118, 255, 0.16), rgba(38, 198, 111, 0.12));
  border: 1px solid rgba(27, 118, 255, 0.18);
  border-radius: 6px;
  box-shadow:
    0 16px 36px rgba(31, 45, 61, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.78);
  opacity: 0;
  transform: translate3d(var(--card-x), var(--card-y), 80px) rotateZ(var(--card-rotate)) scale(1);
  transform-origin: center;
  animation: menu-card-absorb var(--card-duration) cubic-bezier(0.84, 0, 0.16, 1) forwards;
  animation-delay: var(--card-delay);
  will-change: transform, opacity, filter;
}

.gravity-streams {
  z-index: 2;
  transform-style: preserve-3d;
}

.gravity-streams span {
  position: absolute;
  top: 50%;
  left: 50%;
  width: calc(64px + (var(--i) % 5) * 18px);
  height: calc(24px + (var(--i) % 4) * 10px);
  background:
    linear-gradient(90deg, transparent 0 18%, rgba(27, 118, 255, 0.38) 18% 62%, transparent 62%),
    linear-gradient(90deg, transparent 0 42%, rgba(38, 198, 111, 0.34) 42% 78%, transparent 78%),
    linear-gradient(0deg, transparent 0 22%, rgba(27, 118, 255, 0.28) 22% 70%, transparent 70%);
  background-position:
    0 0,
    16px calc(100% - 2px),
    calc(100% - 2px) 7px;
  background-size:
    100% 2px,
    72% 2px,
    2px 72%;
  background-repeat: no-repeat;
  opacity: 0;
  filter: drop-shadow(0 0 5px rgba(27, 118, 255, 0.24));
  transform: rotate(calc(var(--i) * 16.36deg)) translateX(min(42vw, 480px)) rotate(calc((var(--i) - 10) * 7deg))
    scale(0.94);
  animation: gravity-line-absorb 5s cubic-bezier(0.16, 1, 0.3, 1) infinite;
  animation-delay: calc(var(--i) * 64ms);
}

.gravity-streams span::before,
.gravity-streams span::after {
  position: absolute;
  background: currentColor;
  border-radius: 999px;
  content: '';
}

.gravity-streams span::before {
  right: 8px;
  bottom: 0;
  width: calc(18px + (var(--i) % 3) * 10px);
  height: 2px;
  color: rgba(38, 198, 111, 0.44);
}

.gravity-streams span::after {
  top: 3px;
  right: calc(20px + (var(--i) % 3) * 8px);
  width: 2px;
  height: calc(12px + (var(--i) % 4) * 7px);
  color: rgba(27, 118, 255, 0.34);
}

.gravity-streams span:nth-child(3n) {
  color: rgba(38, 198, 111, 0.42);
  filter: drop-shadow(0 0 5px rgba(38, 198, 111, 0.22));
}

.gravity-particles {
  z-index: 2;
}

.gravity-particles span {
  position: absolute;
  top: 50%;
  left: 50%;
  width: calc(3px + (var(--i) % 3) * 1px);
  height: calc(3px + (var(--i) % 3) * 1px);
  background: var(--el-color-primary);
  border-radius: 50%;
  box-shadow: 0 0 10px rgba(27, 118, 255, 0.55);
  opacity: 0;
  transform: rotate(calc(var(--i) * 37deg)) translateX(calc(150px + (var(--i) % 9) * 48px))
    translateY(calc((var(--i) % 7 - 3) * 18px)) scale(0.8);
  animation: gravity-particle-absorb 3.2s cubic-bezier(0.16, 1, 0.3, 1) both;
  animation-delay: calc(var(--i) * 42ms);
}

.gravity-particles span:nth-child(2n) {
  background: #22c96f;
  box-shadow: 0 0 10px rgba(38, 198, 111, 0.5);
}

.gravity-particles span:nth-child(5n) {
  width: 2px;
  height: 2px;
  opacity: 0;
}

.singularity-core-stage {
  z-index: 3;
  display: grid;
  place-items: center;
  transform-style: preserve-3d;
}

.event-horizon-shadow,
.event-horizon-ring,
.event-horizon-core {
  position: absolute;
  border-radius: 50%;
}

.event-horizon-shadow {
  width: min(54vw, 560px);
  height: min(54vw, 560px);
  background: radial-gradient(circle, rgba(2, 8, 16, 0.44), rgba(27, 118, 255, 0.16) 42%, transparent 68%);
  filter: blur(18px);
  opacity: 0;
  transform: translateY(28px) rotateX(68deg) scale(0.5);
  animation: event-shadow-rise 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.event-horizon-ring {
  width: min(46vw, 460px);
  height: min(46vw, 460px);
  opacity: 0;
}

.ring-a {
  background: conic-gradient(
    from 24deg,
    transparent 0 8%,
    rgba(27, 118, 255, 0.78) 15%,
    rgba(38, 198, 111, 0.7) 22%,
    transparent 34% 55%,
    rgba(27, 118, 255, 0.42) 68%,
    transparent 82% 100%
  );
  filter: blur(0.2px);
  mask-image: radial-gradient(circle, transparent 0 42%, #000 45% 54%, transparent 58%);
  animation:
    horizon-ring-grow 5s cubic-bezier(0.16, 1, 0.3, 1) forwards,
    horizon-spin 1.65s linear infinite;
}

.ring-b {
  width: min(34vw, 340px);
  height: min(34vw, 340px);
  border: 1px solid rgba(38, 198, 111, 0.36);
  box-shadow:
    0 0 34px rgba(38, 198, 111, 0.22),
    inset 0 0 24px rgba(27, 118, 255, 0.16);
  animation:
    horizon-ring-grow 5s cubic-bezier(0.16, 1, 0.3, 1) forwards,
    horizon-spin 2.4s linear infinite reverse;
}

.ring-c {
  width: min(24vw, 240px);
  height: min(24vw, 240px);
  border: 1px dashed rgba(27, 118, 255, 0.46);
  animation:
    horizon-ring-grow 5s cubic-bezier(0.16, 1, 0.3, 1) forwards,
    horizon-spin 3.2s linear infinite;
}

.event-horizon-core {
  width: min(17vw, 168px);
  height: min(17vw, 168px);
  background:
    radial-gradient(
      circle at 50% 50%,
      #01060d 0 46%,
      rgba(2, 11, 22, 0.94) 58%,
      rgba(27, 118, 255, 0.34) 72%,
      transparent 76%
    ),
    radial-gradient(circle at 46% 42%, rgba(255, 255, 255, 0.08), transparent 36%);
  box-shadow:
    0 0 28px rgba(2, 8, 16, 0.62),
    0 0 64px rgba(27, 118, 255, 0.3),
    0 0 92px rgba(38, 198, 111, 0.18);
  opacity: 0;
  transform: scale(0.12);
  animation: horizon-core-collapse 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.singularity-burst {
  z-index: 5;
  background: radial-gradient(
    circle at center,
    rgba(255, 255, 255, 0.98) 0 8%,
    rgba(38, 198, 111, 0.3) 18%,
    transparent 46%
  );
  opacity: 0;
  transform: scale(0.1);
  animation: singularity-burst-open 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.singularity-copy {
  position: relative;
  z-index: 6;
  width: min(680px, calc(100vw - 48px));
  margin-top: 368px;
  text-align: center;
  animation: copy-gravity-shift 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.transition-label {
  color: #19bf65;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0;
}

.transition-title {
  margin-top: 8px;
  color: var(--el-text-color-primary);
  font-size: 30px;
  font-weight: 700;
  line-height: 1.24;
}

.transition-subtitle {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  line-height: 1.5;
}

.transition-progress {
  width: min(460px, 100%);
  height: 3px;
  margin: 22px auto 0;
  overflow: hidden;
  background: rgba(27, 118, 255, 0.1);
  border-radius: 999px;
}

.transition-progress span {
  display: block;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, #22c96f, var(--el-color-primary), #22c96f);
  transform: translateX(-100%);
  animation: transition-progress 5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.transition-step-text {
  display: flex;
  justify-content: center;
  gap: 28px;
  margin-top: 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.transition-step-text span {
  position: relative;
  padding-left: 12px;
}

.transition-step-text span::before {
  position: absolute;
  top: 50%;
  left: 0;
  width: 5px;
  height: 5px;
  background: #22c96f;
  border-radius: 50%;
  content: '';
  transform: translateY(-50%);
}

.ai-mode-transition-enter-active,
.ai-mode-transition-leave-active {
  transition: opacity 0.24s cubic-bezier(0.16, 1, 0.3, 1);
}

.ai-mode-transition-enter-from,
.ai-mode-transition-leave-to {
  opacity: 0;
}

@keyframes page-field-warp {
  0% {
    opacity: 0.78;
    transform: scale(1) rotate(0deg);
    filter: blur(0);
  }

  46% {
    opacity: 0.72;
    transform: scale(1.08) rotate(4deg);
    filter: blur(0.6px);
  }

  82%,
  100% {
    opacity: 0;
    transform: scale(0.42) rotate(18deg);
    filter: blur(5px);
  }
}

@keyframes lens-vortex {
  0% {
    opacity: 0;
    transform: scale(0.46) rotate(0deg);
  }

  22%,
  72% {
    opacity: 1;
  }

  100% {
    opacity: 0;
    transform: scale(1.9) rotate(230deg);
  }
}

@keyframes singularity-lens-grow {
  0% {
    opacity: 0;
    transform: scale(0.18);
    filter: blur(4px);
  }

  18% {
    opacity: 1;
    transform: scale(0.62);
    filter: blur(0);
  }

  72% {
    opacity: 1;
    transform: scale(1.26);
    filter: blur(0);
  }

  100% {
    opacity: 0;
    transform: scale(2.6);
    filter: blur(8px);
  }
}

@keyframes singularity-grid-pull {
  0% {
    opacity: 0;
    transform: scale(1.18) rotate(0deg);
  }

  30%,
  68% {
    opacity: 1;
  }

  100% {
    opacity: 0;
    transform: scale(0.3) rotate(26deg);
  }
}

@keyframes fragment-absorb {
  0% {
    opacity: 0;
    filter: blur(5px);
  }

  18% {
    opacity: 0.78;
    filter: blur(0);
  }

  72% {
    opacity: 0.64;
  }

  100% {
    opacity: 0;
    filter: blur(6px);
    transform: translate3d(calc((5 - var(--i)) * 8px), calc((5 - var(--i)) * -6px), 220px) rotateY(0deg) rotateZ(220deg)
      scale(0.08);
  }
}

@keyframes menu-card-absorb {
  0% {
    opacity: 0;
    filter: blur(4px);
    transform: translate3d(var(--card-x), var(--card-y), 80px) rotateZ(var(--card-rotate)) scale(1.08);
  }

  16% {
    opacity: 0.94;
    filter: blur(0);
    transform: translate3d(var(--card-x), var(--card-y), 120px) rotateZ(var(--card-rotate)) scale(1);
  }

  58% {
    opacity: 0.72;
    filter: blur(0.6px);
    transform: translate3d(var(--card-mid-x), var(--card-mid-y), 210px) rotateZ(var(--card-mid-rotate)) scale(0.62);
  }

  82% {
    opacity: 0.28;
    filter: blur(3px);
    transform: translate3d(var(--card-near-x), var(--card-near-y), 300px) rotateZ(var(--card-near-rotate)) scale(0.18);
  }

  100% {
    opacity: 0;
    filter: blur(7px);
    transform: translate3d(0, 0, 360px) rotateZ(var(--card-final-rotate)) scale(0.02);
  }
}

@keyframes gravity-line-absorb {
  0% {
    opacity: 0;
    filter: blur(5px);
    transform: rotate(calc(var(--i) * 16.36deg)) translateX(min(50vw, 560px))
      translateY(calc((var(--i) % 7 - 3) * 20px)) rotate(calc((var(--i) - 10) * 9deg)) scale(0.78);
  }

  30%,
  70% {
    opacity: 0.72;
    filter: blur(0);
  }

  100% {
    opacity: 0;
    filter: blur(4px);
    transform: rotate(calc(var(--i) * 16.36deg + 92deg)) translateX(42px) translateY(calc((var(--i) % 5 - 2) * 4px))
      rotate(calc((var(--i) - 10) * 22deg)) scale(0.12);
  }
}

@keyframes gravity-particle-absorb {
  0% {
    opacity: 0;
    filter: blur(4px);
  }

  22%,
  70% {
    opacity: 0.9;
    filter: blur(0);
  }

  100% {
    opacity: 0;
    filter: blur(2px);
    transform: rotate(calc(var(--i) * 37deg + 128deg)) translateX(18px) translateY(calc((var(--i) % 5 - 2) * 3px))
      scale(0.3);
  }
}

@keyframes event-shadow-rise {
  0% {
    opacity: 0;
    transform: translateY(54px) rotateX(68deg) scale(0.28);
  }

  26%,
  74% {
    opacity: 0.72;
    transform: translateY(28px) rotateX(68deg) scale(1);
  }

  100% {
    opacity: 0;
    transform: translateY(18px) rotateX(68deg) scale(1.8);
  }
}

@keyframes horizon-ring-grow {
  0% {
    opacity: 0;
    transform: scale(0.12) rotateX(58deg);
  }

  22%,
  74% {
    opacity: 1;
    transform: scale(1) rotateX(58deg);
  }

  100% {
    opacity: 0;
    transform: scale(2.45) rotateX(58deg);
  }
}

@keyframes horizon-spin {
  to {
    rotate: 360deg;
  }
}

@keyframes horizon-core-collapse {
  0% {
    opacity: 0;
    transform: scale(0.08);
  }

  18% {
    opacity: 1;
    transform: scale(0.72);
  }

  72% {
    opacity: 1;
    transform: scale(1.08);
  }

  86% {
    opacity: 1;
    transform: scale(0.42);
  }

  100% {
    opacity: 0;
    transform: scale(2.8);
  }
}

@keyframes singularity-burst-open {
  0%,
  72% {
    opacity: 0;
    transform: scale(0.1);
  }

  84% {
    opacity: 1;
    transform: scale(1.25);
  }

  100% {
    opacity: 0;
    transform: scale(3.2);
  }
}

@keyframes copy-gravity-shift {
  0%,
  72% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }

  86% {
    opacity: 0.4;
    transform: translateY(-18px) scale(0.96);
  }

  100% {
    opacity: 0;
    transform: translateY(-36px) scale(0.92);
  }
}

@keyframes transition-progress {
  to {
    transform: translateX(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .ai-mode-transition-overlay::before,
  .ai-mode-transition-overlay::after,
  .singularity-lens,
  .singularity-grid,
  .workspace-fragments span,
  .menu-absorb-card,
  .gravity-streams span,
  .gravity-particles span,
  .event-horizon-shadow,
  .event-horizon-ring,
  .event-horizon-core,
  .singularity-burst,
  .singularity-copy,
  .transition-progress span {
    animation: none;
  }
}
</style>
