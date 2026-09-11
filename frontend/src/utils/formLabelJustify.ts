const formSelector = '.xx-form-label-justify';
const labelSelector = '.el-form-item__label';
const formattedClass = 'xx-form-label-justify__label';
const textClass = 'xx-form-label-justify__text';
const charClass = 'xx-form-label-justify__char';

let observer: MutationObserver | null = null;
let pendingLabels = new Set<HTMLElement>();
let scheduled = false;

function getLabelText(label: Element) {
  const text = label.textContent?.trim() || '';
  return text.replace(/\s+/g, '');
}

function shouldFormatLabel(label: HTMLElement) {
  return !label.querySelector(`.${textClass}`) && !label.querySelector('*') && Boolean(getLabelText(label));
}

function formatLabel(label: HTMLElement) {
  if (!shouldFormatLabel(label)) return;

  const text = getLabelText(label);
  const chars = Array.from(text);
  const wrapper = document.createElement('span');

  wrapper.className = textClass;
  wrapper.style.setProperty('--xx-form-label-char-count', String(chars.length));

  chars.forEach(char => {
    const charNode = document.createElement('span');
    charNode.className = charClass;
    charNode.textContent = char;
    wrapper.appendChild(charNode);
  });

  label.textContent = '';
  label.classList.add(formattedClass);
  label.appendChild(wrapper);
}

function collectLabels(root: ParentNode) {
  const labels: HTMLElement[] = [];

  if (
    root instanceof HTMLElement &&
    root.matches(`${formSelector} ${labelSelector}, .el-form-item${formSelector} ${labelSelector}`)
  ) {
    labels.push(root);
  }

  root
    .querySelectorAll<HTMLElement>(`${formSelector} ${labelSelector}, .el-form-item${formSelector} ${labelSelector}`)
    .forEach(label => labels.push(label));

  return labels;
}

function flushLabels() {
  scheduled = false;

  pendingLabels.forEach(formatLabel);
  pendingLabels = new Set();
}

function enqueueLabels(root: ParentNode) {
  collectLabels(root).forEach(label => pendingLabels.add(label));

  if (scheduled || pendingLabels.size === 0) return;

  scheduled = true;
  requestAnimationFrame(flushLabels);
}

function handleMutation(mutation: MutationRecord) {
  if (mutation.type === 'attributes') {
    enqueueLabels(mutation.target);
    return;
  }

  mutation.addedNodes.forEach(node => {
    if (node instanceof HTMLElement) {
      enqueueLabels(node);
    }
  });
}

export function setupFormLabelJustify() {
  if (typeof window === 'undefined' || observer) return;

  enqueueLabels(document);

  observer = new MutationObserver(mutations => {
    mutations.forEach(handleMutation);
  });
  observer.observe(document.body, {
    attributes: true,
    attributeFilter: ['class'],
    childList: true,
    subtree: true
  });
}
