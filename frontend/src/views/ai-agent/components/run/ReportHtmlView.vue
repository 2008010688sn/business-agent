<!--
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->

<template>
  <div class="report-html-view-wrapper">
    <iframe ref="iframeRef" class="report-html-iframe" sandbox="allow-scripts" title="HTML报告预览" />
  </div>
</template>

<script lang="ts">
import { defineComponent, nextTick, onBeforeUnmount, ref, watch, type PropType } from 'vue';
import { buildReportHtmlDocument, type ReportSourceFormat } from '@/views/ai-agent/utils/reportRenderer';

const HTML_PREVIEW_DEBOUNCE_MS = 360;

export default defineComponent({
  name: 'ReportHtmlView',
  props: {
    content: {
      type: String,
      default: ''
    },
    sourceFormat: {
      type: String as PropType<ReportSourceFormat>,
      default: 'html'
    }
  },
  setup(props) {
    const iframeRef = ref<HTMLIFrameElement | null>(null);
    let loadTimer: ReturnType<typeof setTimeout> | null = null;

    const clearLoadTimer = () => {
      if (loadTimer !== null) {
        clearTimeout(loadTimer);
        loadTimer = null;
      }
    };

    const loadHtml = () => {
      clearLoadTimer();
      if (!iframeRef.value) return;

      if (!props.content) {
        iframeRef.value.srcdoc = buildReportHtmlDocument('', { sourceFormat: props.sourceFormat });
        return;
      }

      iframeRef.value.srcdoc = buildReportHtmlDocument(props.content, { sourceFormat: props.sourceFormat });
    };

    const scheduleLoadHtml = async (immediate = false) => {
      await nextTick();
      clearLoadTimer();
      if (immediate) {
        loadHtml();
        return;
      }
      loadTimer = setTimeout(loadHtml, HTML_PREVIEW_DEBOUNCE_MS);
    };

    watch(
      () => [props.content, props.sourceFormat],
      () => scheduleLoadHtml(),
      { immediate: true }
    );

    onBeforeUnmount(() => {
      clearLoadTimer();
    });

    return {
      iframeRef
    };
  }
});
</script>

<style scoped>
.report-html-view-wrapper {
  width: 100%;
  min-height: 400px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.report-html-iframe {
  width: 100%;
  min-height: 600px;
  border: none;
  display: block;
}
</style>
