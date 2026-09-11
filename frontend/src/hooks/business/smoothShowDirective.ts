import type { App } from 'vue';

type SmoothShowElement = HTMLElement & {
  __smoothShowDisplay?: string;
  __smoothShowTimer?: number;
  __smoothShowFrame?: number;
};

const duration = 500;
const easing = 'cubic-bezier(0.16, 1, 0.3, 1)';

const clearTimer = (el: SmoothShowElement) => {
  if (el.__smoothShowTimer) {
    window.clearTimeout(el.__smoothShowTimer);
    el.__smoothShowTimer = undefined;
  }
};

const clearFrame = (el: SmoothShowElement) => {
  if (el.__smoothShowFrame) {
    window.cancelAnimationFrame(el.__smoothShowFrame);
    el.__smoothShowFrame = undefined;
  }
};

const prefersReducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

const clearAnimationStyles = (el: SmoothShowElement) => {
  el.style.transition = '';
  el.style.height = '';
  el.style.opacity = '';
  el.style.overflow = '';
};

const setTransition = (el: SmoothShowElement) => {
  el.style.overflow = 'hidden';
  el.style.transition = `height ${duration}ms ${easing}, opacity ${duration}ms ${easing}`;
};

const show = (el: SmoothShowElement) => {
  clearTimer(el);
  clearFrame(el);
  if (prefersReducedMotion()) {
    el.style.display = el.__smoothShowDisplay || '';
    clearAnimationStyles(el);
    return;
  }
  const isHidden = window.getComputedStyle(el).display === 'none';
  const startHeight = isHidden ? 0 : el.offsetHeight;
  setTransition(el);
  el.style.display = el.__smoothShowDisplay || '';
  el.style.height = `${startHeight}px`;
  el.style.opacity = isHidden ? '0' : window.getComputedStyle(el).opacity;

  el.__smoothShowFrame = window.requestAnimationFrame(() => {
    el.style.height = `${el.scrollHeight}px`;
    el.style.opacity = '1';
    el.__smoothShowFrame = undefined;
  });

  el.__smoothShowTimer = window.setTimeout(() => {
    clearAnimationStyles(el);
  }, duration);
};

const hide = (el: SmoothShowElement) => {
  clearTimer(el);
  clearFrame(el);
  if (prefersReducedMotion()) {
    el.style.display = 'none';
    clearAnimationStyles(el);
    return;
  }
  const startHeight = el.offsetHeight;
  setTransition(el);
  el.style.height = `${startHeight}px`;
  el.style.opacity = '1';
  void el.offsetHeight;

  el.__smoothShowFrame = window.requestAnimationFrame(() => {
    el.style.height = '0px';
    el.style.opacity = '0';
    el.__smoothShowFrame = undefined;
  });

  el.__smoothShowTimer = window.setTimeout(() => {
    el.style.display = 'none';
    clearAnimationStyles(el);
  }, duration);
};

export function smoothShowDirective(app: App) {
  app.directive('smooth-show', {
    mounted(el: SmoothShowElement, binding) {
      const computedDisplay = window.getComputedStyle(el).display;
      el.__smoothShowDisplay = computedDisplay === 'none' ? '' : computedDisplay;

      if (!binding.value) {
        el.style.display = 'none';
      }
    },
    updated(el: SmoothShowElement, binding) {
      if (binding.value === binding.oldValue) return;
      binding.value ? show(el) : hide(el);
    },
    beforeUnmount(el: SmoothShowElement) {
      clearTimer(el);
      clearFrame(el);
    }
  });
}
