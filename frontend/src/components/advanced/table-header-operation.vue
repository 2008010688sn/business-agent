<script setup lang="ts">
import { ref } from 'vue';
import { useBreakpoints } from '@vueuse/core';
import type { MessageParamsWithType } from 'element-plus';
import { ElMessage } from 'element-plus';
import { checkDownloadExcelFile, downloadExcelFile } from '@/utils/index.js';
import { $t } from '@/locales';
import { Http } from '@/service/request';
import { useCommonMixin } from '@/mixins/composition.js';
import { signatureHeaders } from '@/utils/safe';
const { startLoading, endLoading } = useCommonMixin();

defineOptions({ name: 'TableHeaderOperation' });

const breakpoints = useBreakpoints({ sm: 640 });
const isMobile = breakpoints.smaller('sm');
const mobilePopoverVisible = ref(false);

interface Props {
  disabledDelete?: boolean;
  loading?: boolean;
  authCode?: string;
  downLoadUrl?: string;
  searchParams: object;
  exportName?: string;
  allowExport?: () => boolean;
}

const props = withDefaults(defineProps<Props>(), {
  allowExport: () => {
    return true;
  }
});

interface Emits {
  (e: 'add'): void;
  (e: 'delete'): void;
  (e: 'refresh'): void;
}

const emit = defineEmits<Emits>();

const columns = defineModel<UI.TableColumnCheck[]>('columns', {
  default: () => []
});

const tabKey = defineModel<string>('tabKey');

function add() {
  emit('add');
}

function listExport() {
  if (!props.allowExport()) return;
  startLoading('global', '导出中...');
  const typeBol = { responseType: 'arraybuffer', ...signatureHeaders() };
  Http.post(props.downLoadUrl, props.searchParams, typeBol)
    .then(response => {
      checkDownloadExcelFile(
        response,
        (blobUrl: any) => {
          downloadExcelFile(blobUrl, `${props.exportName}.xlsx`);
          endLoading('global');
        },
        (error: { msg: MessageParamsWithType }) => {
          ElMessage.error(error.message);
          endLoading('global');
        }
      );
    })
    .catch(error => {
      ElMessage.error(error.message);
      endLoading('global');
    });
}

function batchDelete() {
  emit('delete');
}

function refresh() {
  emit('refresh');
}
</script>

<template>
  <ElSpace v-if="!isMobile" direction="horizontal" wrap justify="end" class="lt-sm:w-200px">
    <slot name="prefix"></slot>
    <slot name="default">
      <ElButton v-if="!authCode && downLoadUrl" @click="listExport">
        <template #icon>
          <ElIcon><Download /></ElIcon>
        </template>
        {{ $t('common.download') }}
      </ElButton>
      <ElButton v-else v-auth="authCode" @click="listExport">
        <template #icon>
          <ElIcon><Download /></ElIcon>
        </template>
        {{ $t('common.download') }}
      </ElButton>
    </slot>
    <ElButton @click="refresh">
      <template #icon>
        <icon-mdi-refresh class="text-icon" :class="{ 'animate-spin': loading }" />
      </template>
      {{ $t('common.refresh') }}
    </ElButton>
    <TableColumnSetting v-model:columns="columns" :tab-key="tabKey" />
    <slot name="suffix"></slot>
  </ElSpace>
  <div v-else class="table-header-operation-mobile">
    <ElPopover
      v-model:visible="mobilePopoverVisible"
      placement="bottom-end"
      trigger="click"
      popper-class="table-header-operation-popper"
    >
      <template #reference>
        <ElButton circle class="table-header-operation-mobile__trigger">
          <template #icon>
            <ElIcon><MoreFilled /></ElIcon>
          </template>
        </ElButton>
      </template>
      <ElSpace direction="vertical" class="table-header-operation-popper__content">
        <slot name="prefix"></slot>
        <slot name="default">
          <ElButton v-if="!authCode && downLoadUrl" @click="listExport">
            <template #icon>
              <ElIcon><Download /></ElIcon>
            </template>
            {{ $t('common.download') }}
          </ElButton>
          <ElButton v-else v-auth="authCode" @click="listExport">
            <template #icon>
              <ElIcon><Download /></ElIcon>
            </template>
            {{ $t('common.download') }}
          </ElButton>
        </slot>
        <ElButton @click="refresh">
          <template #icon>
            <icon-mdi-refresh class="text-icon" :class="{ 'animate-spin': loading }" />
          </template>
          {{ $t('common.refresh') }}
        </ElButton>
        <TableColumnSetting v-model:columns="columns" :tab-key="tabKey" />
        <slot name="suffix"></slot>
      </ElSpace>
    </ElPopover>
  </div>
</template>

<style scoped>
.table-header-operation-mobile {
  display: contents;
}

.table-header-operation-mobile__trigger {
  position: absolute;
  top: 10px;
  right: 16px;
  z-index: 1;
}

:global(.table-header-operation-popper) {
  max-width: calc(100vw - 24px);
}

:global(.table-header-operation-popper__content) {
  width: 100%;
  align-items: stretch;
}

@media (max-width: 639px) {
  :global(.list-page-main-card > .el-card__header),
  :global(.x-wrapper-main-card > .el-card__header) {
    position: relative;
  }
}
</style>
