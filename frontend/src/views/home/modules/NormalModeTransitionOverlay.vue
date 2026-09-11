<template>
  <Teleport to="body">
    <Transition name="normal-mode-transition">
      <div v-if="visible" class="normal-mode-transition-overlay">
        <div class="normal-mode-grid" aria-hidden="true"></div>
        <div class="normal-mode-workbench-blueprint" aria-hidden="true">
          <div class="normal-mode-workbench-frame">
            <aside class="normal-mode-workbench-sidebar">
              <span class="normal-mode-sidebar-logo"></span>
              <span v-for="item in 6" :key="`side-${item}`" class="normal-mode-sidebar-item"></span>
            </aside>
            <section class="normal-mode-workbench-main">
              <header class="normal-mode-workbench-header">
                <span class="normal-mode-header-breadcrumb"></span>
                <span class="normal-mode-header-search"></span>
                <span class="normal-mode-header-action"></span>
                <span class="normal-mode-header-action is-small"></span>
              </header>
              <div class="normal-mode-workbench-content">
                <div class="normal-mode-content-hero">
                  <span></span>
                  <span></span>
                </div>
                <div class="normal-mode-stat-grid">
                  <span v-for="item in 3" :key="`stat-${item}`"></span>
                </div>
                <div class="normal-mode-table-shell">
                  <span class="normal-mode-table-head"></span>
                  <span v-for="item in 4" :key="`row-${item}`" class="normal-mode-table-row"></span>
                </div>
              </div>
            </section>
          </div>
          <span
            v-for="segment in blueprintSegments"
            :key="segment.id"
            class="normal-mode-blueprint-segment"
            :class="segment.className"
            :style="segment.style"
          ></span>
        </div>
        <div class="normal-mode-panels">
          <span v-for="panel in panels" :key="panel.id" :style="panel.style">
            {{ panel.label }}
          </span>
        </div>
        <div class="normal-mode-core" aria-hidden="true">
          <span class="normal-mode-core-ring ring-a"></span>
          <span class="normal-mode-core-ring ring-b"></span>
          <img src="@/assets/imgs/logo.svg" alt="" />
        </div>
        <div class="normal-mode-copy">
          <div class="normal-mode-label">AI WORKSPACE CLOSING</div>
          <div class="normal-mode-title">正在切回普通模式</div>
          <div class="normal-mode-subtitle">智能能力已收束，业务工作台正在恢复</div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { useRouteStore } from '@/store/modules/route';

defineOptions({ name: 'NormalModeTransitionOverlay' });

const props = withDefaults(
  defineProps<{
    visible: boolean;
    duration?: number;
  }>(),
  {
    duration: 2600
  }
);

const emit = defineEmits<{
  finished: [];
}>();

type NormalModePanel = {
  id: string;
  label: string;
  style: Record<string, string>;
};

const routeStore = useRouteStore();
const panels = ref<NormalModePanel[]>([]);

const blueprintSegments = [
  { id: 'header', className: 'is-bar', x: 28, y: 24, w: 504, h: 38, delay: 1.18 },
  { id: 'logo', className: 'is-dot', x: 44, y: 38, w: 10, h: 10, delay: 1.24 },
  { id: 'tab-a', className: 'is-line', x: 72, y: 42, w: 88, h: 4, delay: 1.3 },
  { id: 'tab-b', className: 'is-line', x: 178, y: 42, w: 64, h: 4, delay: 1.34 },
  { id: 'sidebar', className: 'is-panel', x: 28, y: 78, w: 132, h: 216, delay: 1.38 },
  { id: 'side-line-a', className: 'is-line', x: 52, y: 108, w: 80, h: 4, delay: 1.44 },
  { id: 'side-line-b', className: 'is-line', x: 52, y: 142, w: 64, h: 4, delay: 1.5 },
  { id: 'side-line-c', className: 'is-line', x: 52, y: 176, w: 74, h: 4, delay: 1.56 },
  { id: 'main-card-a', className: 'is-panel', x: 184, y: 84, w: 150, h: 78, delay: 1.62 },
  { id: 'main-card-b', className: 'is-panel', x: 358, y: 84, w: 150, h: 78, delay: 1.68 },
  { id: 'main-wide', className: 'is-panel', x: 184, y: 184, w: 324, h: 84, delay: 1.74 }
].map(segment => ({
  id: segment.id,
  className: segment.className,
  style: {
    '--segment-delay': `${segment.delay}s`,
    '--segment-height': `${segment.h}px`,
    '--segment-left': `${segment.x}px`,
    '--segment-top': `${segment.y}px`,
    '--segment-width': `${segment.w}px`
  }
}));

const fallbackMenuLabels = ['工作台', '消息中心', '待办任务', '项目看板', '数据报表', '系统管理', '个人中心'];

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

const createPanels = () => {
  const sourceLabels = collectMenuLabels(routeStore.menus);
  const selectedLabels = shuffle(sourceLabels.length ? sourceLabels : collectDomMenuLabels()).slice(0, 7);
  const labels = selectedLabels.length ? selectedLabels : fallbackMenuLabels;

  panels.value = Array.from({ length: 7 }, (_, index) => {
    const value = index + 1;
    const label = labels[index % labels.length];

    return {
      id: `${label}-${value}-${Date.now()}`,
      label,
      style: {
        '--panel-top': `${14 + (value % 3) * 26}%`,
        '--panel-left': `${8 + (value % 4) * 24}%`,
        '--panel-width': `${130 + (value % 3) * 42}px`,
        '--panel-height': `${54 + (value % 2) * 18}px`,
        '--panel-delay': `${1.16 + value * 0.07}s`
      }
    };
  });
};

let timer: number | undefined;

const clearTimer = () => {
  if (timer !== undefined) {
    window.clearTimeout(timer);
    timer = undefined;
  }
};

watch(
  () => props.visible,
  visible => {
    clearTimer();
    if (visible) {
      createPanels();
      timer = window.setTimeout(() => {
        emit('finished');
      }, props.duration);
    } else {
      panels.value = [];
    }
  },
  { immediate: true }
);

onBeforeUnmount(clearTimer);
</script>

<style scoped>
.normal-mode-transition-overlay {
  position: fixed;
  inset: 0;
  z-index: 3200;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  color: var(--el-text-color-primary);
  background:
    radial-gradient(circle at 50% 48%, rgba(27, 118, 255, 0.16), transparent 34%),
    linear-gradient(145deg, rgba(248, 251, 255, 0.96), rgba(240, 247, 255, 0.92));
}

.normal-mode-transition-overlay::before,
.normal-mode-grid,
.normal-mode-workbench-blueprint,
.normal-mode-panels {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.normal-mode-transition-overlay::before {
  background: var(--minimal-modal-overlay, rgba(0, 0, 0, 0.24));
  content: '';
  opacity: 0.12;
  animation: normal-mode-mask-soften 2.6s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

:global(html.theme-style-enterprise) .normal-mode-transition-overlay::before {
  background: var(--enterprise-modal-overlay);
}

.normal-mode-grid {
  background:
    linear-gradient(90deg, rgba(27, 118, 255, 0.08) 1px, transparent 1px),
    linear-gradient(rgba(27, 118, 255, 0.08) 1px, transparent 1px);
  background-size: 52px 52px;
  mask-image: radial-gradient(circle at center, transparent 0 12%, #000 24%, #000 68%, transparent 100%);
  opacity: 0.78;
  transform-origin: center;
  animation: normal-mode-grid-restore 2.6s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.normal-mode-workbench-blueprint {
  z-index: 1;
  display: grid;
  place-items: center;
  opacity: 0;
  animation: normal-mode-blueprint-stage 2.6s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.normal-mode-workbench-blueprint::before {
  width: 560px;
  height: 320px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 24%, transparent);
  background: transparent;
  border-radius: 10px;
  box-shadow: 0 0 24px color-mix(in srgb, var(--el-color-primary) 10%, transparent);
  content: '';
  opacity: 0.72;
  transform: scaleX(0.08);
  transform-origin: center;
  animation: normal-mode-blueprint-frame 2.6s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.normal-mode-workbench-frame {
  position: absolute;
  top: calc(50% - 160px);
  left: calc(50% - 280px);
  display: grid;
  grid-template-columns: 128px minmax(0, 1fr);
  width: 560px;
  height: 320px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 14%, rgba(255, 255, 255, 0.28));
  border-radius: 10px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.44), rgba(248, 252, 255, 0.18)),
    color-mix(in srgb, var(--el-color-primary) 2%, transparent);
  box-shadow:
    0 18px 48px rgba(31, 45, 61, 0.05),
    inset 0 1px 0 rgba(255, 255, 255, 0.52);
  opacity: 0;
  transform: translateY(18px) scaleX(0.18) scaleY(0.72);
  transform-origin: center;
  animation: normal-mode-workbench-frame 2.6s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.normal-mode-workbench-sidebar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 14px;
  border-right: 1px solid color-mix(in srgb, var(--el-color-primary) 10%, transparent);
  background:
    linear-gradient(
      180deg,
      color-mix(in srgb, var(--el-color-primary) 4%, rgba(255, 255, 255, 0.42)),
      rgba(255, 255, 255, 0.14)
    ),
    linear-gradient(90deg, color-mix(in srgb, var(--el-color-primary) 7%, transparent), transparent);
}

.normal-mode-sidebar-logo {
  width: 72px;
  height: 12px;
  margin-bottom: 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-color-primary) 22%, rgba(255, 255, 255, 0.45));
  box-shadow: 0 0 10px color-mix(in srgb, var(--el-color-primary) 8%, transparent);
}

.normal-mode-sidebar-item {
  position: relative;
  width: 86px;
  height: 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.42);
}

.normal-mode-sidebar-item::before {
  position: absolute;
  top: 1px;
  left: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--el-color-primary) 16%, rgba(255, 255, 255, 0.45));
  content: '';
}

.normal-mode-sidebar-item::after {
  position: absolute;
  top: 3px;
  left: 16px;
  width: 52px;
  height: 4px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-text-color-secondary) 12%, transparent);
  content: '';
}

.normal-mode-sidebar-item:nth-child(3),
.normal-mode-sidebar-item:nth-child(6) {
  width: 72px;
}

.normal-mode-workbench-main {
  display: grid;
  grid-template-rows: 46px minmax(0, 1fr);
  min-width: 0;
}

.normal-mode-workbench-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  border-bottom: 1px solid color-mix(in srgb, var(--el-color-primary) 8%, transparent);
  background: rgba(255, 255, 255, 0.34);
}

.normal-mode-header-breadcrumb,
.normal-mode-header-search,
.normal-mode-header-action {
  height: 10px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-color-primary) 13%, rgba(255, 255, 255, 0.45));
}

.normal-mode-header-breadcrumb {
  width: 110px;
}

.normal-mode-header-search {
  flex: 1;
  max-width: 150px;
  margin-left: auto;
  background: rgba(255, 255, 255, 0.5);
}

.normal-mode-header-action {
  width: 34px;
}

.normal-mode-header-action.is-small {
  width: 22px;
}

.normal-mode-workbench-content {
  display: grid;
  grid-template-rows: 64px 58px minmax(0, 1fr);
  gap: 12px;
  padding: 14px 16px 16px;
}

.normal-mode-content-hero,
.normal-mode-stat-grid span,
.normal-mode-table-shell {
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 8%, transparent);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.34);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.4);
}

.normal-mode-content-hero {
  display: flex;
  flex-direction: column;
  gap: 10px;
  justify-content: center;
  padding: 0 18px;
}

.normal-mode-content-hero span {
  display: block;
  height: 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-text-color-primary) 10%, transparent);
}

.normal-mode-content-hero span:first-child {
  width: 120px;
  background: color-mix(in srgb, var(--el-color-primary) 16%, rgba(255, 255, 255, 0.45));
}

.normal-mode-content-hero span:last-child {
  width: 220px;
}

.normal-mode-stat-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.normal-mode-table-shell {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
}

.normal-mode-table-head,
.normal-mode-table-row {
  display: block;
  height: 8px;
  border-radius: 999px;
}

.normal-mode-table-head {
  width: 86%;
  background: color-mix(in srgb, var(--el-color-primary) 12%, rgba(255, 255, 255, 0.48));
}

.normal-mode-table-row {
  width: 100%;
  background: color-mix(in srgb, var(--el-text-color-secondary) 8%, transparent);
}

.normal-mode-table-row:nth-child(3) {
  width: 92%;
}

.normal-mode-table-row:nth-child(4) {
  width: 78%;
}

.normal-mode-blueprint-segment {
  position: absolute;
  top: calc(50% - 160px + var(--segment-top));
  left: calc(50% - 280px + var(--segment-left));
  width: var(--segment-width);
  height: var(--segment-height);
  background: color-mix(in srgb, var(--el-color-primary) 20%, rgba(255, 255, 255, 0.34));
  border-radius: 999px;
  box-shadow: 0 0 10px color-mix(in srgb, var(--el-color-primary) 10%, transparent);
  opacity: 0;
  transform: translateY(14px) scaleX(0.1);
  transform-origin: left center;
  animation: normal-mode-blueprint-segment 2.6s cubic-bezier(0.16, 1, 0.3, 1) forwards;
  animation-delay: var(--segment-delay);
}

.normal-mode-blueprint-segment.is-bar {
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--el-color-primary) 8%, transparent),
    rgba(255, 255, 255, 0.48)
  );
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 8%, transparent);
  border-radius: 8px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.normal-mode-blueprint-segment.is-panel {
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.5), color-mix(in srgb, var(--el-color-primary) 6%, transparent)),
    linear-gradient(90deg, color-mix(in srgb, var(--el-color-primary) 8%, transparent) 10px, transparent 10px);
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 10%, transparent);
  border-radius: 8px;
  box-shadow: 0 10px 22px rgba(31, 45, 61, 0.04);
  transform-origin: center;
}

.normal-mode-blueprint-segment.is-dot {
  border-radius: 50%;
  transform-origin: center;
}

.normal-mode-panels span {
  position: absolute;
  top: var(--panel-top);
  left: var(--panel-left);
  display: inline-flex;
  align-items: center;
  justify-content: flex-start;
  width: var(--panel-width);
  height: var(--panel-height);
  min-width: 0;
  padding: 0 14px 0 24px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.88), rgba(247, 250, 255, 0.6)),
    linear-gradient(90deg, rgba(27, 118, 255, 0.16) 12px, transparent 12px);
  border: 1px solid rgba(27, 118, 255, 0.14);
  border-radius: 6px;
  box-shadow: 0 14px 32px rgba(31, 45, 61, 0.1);
  opacity: 0;
  transform: translate3d(0, 48px, 0) scale(0.8);
  animation: normal-mode-panel-unfold 2.6s cubic-bezier(0.16, 1, 0.3, 1) forwards;
  animation-delay: var(--panel-delay);
}

.normal-mode-core {
  position: relative;
  z-index: 2;
  display: grid;
  width: 116px;
  height: 116px;
  place-items: center;
  border-radius: 50%;
  filter: drop-shadow(0 18px 32px rgba(31, 45, 61, 0.2));
  transform-origin: center;
  animation: normal-mode-core-return 2.6s linear forwards;
}

.normal-mode-core img {
  position: relative;
  z-index: 2;
  width: 54px;
  height: 54px;
  border-radius: 50%;
  object-fit: cover;
}

.normal-mode-core-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
}

.normal-mode-core-ring.ring-a {
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 48%, transparent);
  animation: normal-mode-ring-contract 2.6s linear forwards;
}

.normal-mode-core-ring.ring-b {
  inset: 18px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.82), rgba(27, 118, 255, 0.14) 56%, transparent 68%);
  animation: normal-mode-ring-pulse 2.6s linear forwards;
}

.normal-mode-copy {
  position: absolute;
  z-index: 3;
  top: calc(50% + 108px);
  left: 50%;
  width: min(520px, calc(100vw - 48px));
  text-align: center;
  transform: translateX(-50%);
  animation: normal-mode-copy-shift 2.6s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

.normal-mode-label {
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0;
}

.normal-mode-title {
  margin-top: 8px;
  color: var(--el-text-color-primary);
  font-size: 26px;
  font-weight: 700;
  line-height: 1.24;
}

.normal-mode-subtitle {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  line-height: 1.5;
}

.normal-mode-transition-enter-active,
.normal-mode-transition-leave-active {
  transition: opacity 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.normal-mode-transition-enter-from,
.normal-mode-transition-leave-to {
  opacity: 0;
}

@keyframes normal-mode-mask-soften {
  0%,
  58% {
    opacity: 0.18;
  }

  100% {
    opacity: 0.05;
  }
}

@keyframes normal-mode-grid-restore {
  0% {
    opacity: 0;
    transform: scale(1.35) rotate(8deg);
    filter: blur(4px);
  }

  42% {
    opacity: 0.74;
    transform: scale(0.86) rotate(0deg);
    filter: blur(0);
  }

  100% {
    opacity: 0.28;
    transform: scale(1) rotate(0deg);
    filter: blur(1px);
  }
}

@keyframes normal-mode-blueprint-stage {
  0%,
  46% {
    opacity: 0;
    transform: scale(0.76);
  }

  72% {
    opacity: 0.92;
    transform: scale(1);
  }

  100% {
    opacity: 0;
    transform: scale(1.03);
  }
}

@keyframes normal-mode-blueprint-frame {
  0%,
  46% {
    opacity: 0;
    transform: scaleX(0.08) scaleY(0.64);
  }

  74% {
    opacity: 0.72;
    transform: scaleX(1) scaleY(1);
  }

  100% {
    opacity: 0;
    transform: scaleX(1.02) scaleY(1.02);
  }
}

@keyframes normal-mode-workbench-frame {
  0%,
  46% {
    opacity: 0;
    transform: translateY(18px) scaleX(0.18) scaleY(0.72);
  }

  70% {
    opacity: 0.62;
    transform: translateY(0) scaleX(1) scaleY(1);
  }

  88% {
    opacity: 0.56;
    transform: translateY(0) scaleX(1) scaleY(1);
  }

  100% {
    opacity: 0;
    transform: translateY(-10px) scaleX(1.02) scaleY(1.02);
  }
}

@keyframes normal-mode-blueprint-segment {
  0% {
    opacity: 0;
    transform: translateY(14px) scaleX(0.1);
  }

  38% {
    opacity: 0;
  }

  72% {
    opacity: 0.42;
    transform: translateY(0) scaleX(1);
  }

  100% {
    opacity: 0;
    transform: translateY(-8px) scaleX(1);
  }
}

@keyframes normal-mode-panel-unfold {
  0%,
  48% {
    opacity: 0;
    transform: translate3d(0, 36px, 0) scale(0.72);
  }

  82% {
    opacity: 0.8;
    transform: translate3d(0, 0, 0) scale(1);
  }

  100% {
    opacity: 0;
    transform: translate3d(0, -8px, 0) scale(1.02);
  }
}

@keyframes normal-mode-core-return {
  0% {
    opacity: 1;
    transform: scale(1) rotate(0deg);
  }

  72% {
    opacity: 0.9;
    transform: scale(0.46) rotate(0deg);
  }

  100% {
    opacity: 0;
    transform: scale(0.12) rotate(0deg);
  }
}

@keyframes normal-mode-ring-contract {
  0% {
    opacity: 0.9;
    transform: scale(1.6);
  }

  72% {
    opacity: 0.48;
    transform: scale(0.72);
  }

  100% {
    opacity: 0;
    transform: scale(0.18);
  }
}

@keyframes normal-mode-ring-pulse {
  0% {
    opacity: 0.62;
    transform: scale(1.18);
  }

  100% {
    opacity: 0;
    transform: scale(0.2);
  }
}

@keyframes normal-mode-copy-shift {
  0% {
    opacity: 0;
    transform: translate(-50%, 18px);
  }

  30%,
  72% {
    opacity: 1;
    transform: translate(-50%, 0);
  }

  100% {
    opacity: 0;
    transform: translate(-50%, -18px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .normal-mode-transition-overlay::before,
  .normal-mode-grid,
  .normal-mode-workbench-blueprint,
  .normal-mode-workbench-blueprint::before,
  .normal-mode-workbench-frame,
  .normal-mode-blueprint-segment,
  .normal-mode-panels span,
  .normal-mode-core,
  .normal-mode-core-ring,
  .normal-mode-copy {
    animation: none;
  }
}
</style>
