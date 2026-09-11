<script setup lang="ts" generic="T extends Record<string, unknown>, K = never">
import { VueDraggable } from 'vue-draggable-plus';
import type { CheckboxValueType } from 'element-plus';
import { ElMessage } from 'element-plus';
import { onBeforeMount, ref } from 'vue';
import { useRoute } from 'vue-router';
import { $t } from '@/locales';
import { tableColumns } from '@/store/modules/column/index';
const route = useRoute();
const columnKey = ref('');
defineOptions({ name: 'TableColumnSetting' });

const columns = defineModel<UI.TableColumnCheck[]>('columns', {
  required: true
});

const tabKey = defineModel<string>('tabKey');
if (tabKey.value) {
  columnKey.value = `${route.name}-${tabKey.value}`;
} else {
  columnKey.value = route.name;
}

const handleChange = (_value: CheckboxValueType, index: number) => {
  const checkedColumns = columns.value.filter(item => item.checked);
  if (checkedColumns.length === 0) {
    columns.value[index].checked = true;
    ElMessage.warning('请至少保持一列');
  }
};

onBeforeMount(() => {
  const list = tableColumns().getList(columnKey.value);
  const arr1 = columns.value?.map(el => el.prop) || [];
  const arr2 = list?.map((el: any) => el.prop) || [];
  const noChanged = arr1.length === arr2.length && arr1.every(item => arr2.includes(item));
  if (noChanged) {
    columns.value = list;
  }
});

const onHide = () => {
  tableColumns().setList(columnKey.value, columns.value);
};
</script>

<template>
  <ElPopover placement="bottom-end" trigger="click" width="auto" @hide="onHide">
    <template #reference>
      <ElButton>
        <template #icon>
          <icon-ant-design-setting-outlined class="text-icon" />
        </template>
        {{ $t('common.columnSetting') }}
      </ElButton>
    </template>
    <VueDraggable v-model="columns" :animation="150" filter=".none_draggable">
      <div
        v-for="(item, index) in columns"
        :key="item.prop"
        class="h-36px flex-y-center rd-4px hover:(bg-primary bg-opacity-20)"
      >
        <icon-mdi-drag class="mr-8px h-full cursor-move text-icon" />
        <ElCheckbox v-model="item.checked" class="none_draggable flex-1" @change="handleChange($event, index)">
          {{ item.label }}
        </ElCheckbox>
      </div>
    </VueDraggable>
  </ElPopover>
</template>

<style scoped></style>
