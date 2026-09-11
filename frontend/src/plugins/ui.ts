import type { App } from 'vue';
import ElementPlus, { ElCard, ElDialog, ElTable } from 'element-plus';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';

ElTable.TableColumn.props.align = {
  type: String,
  default: 'center'
};

ElCard.props.shadow = {
  type: String,
  default: 'never'
};

export const setupUI = (app: App) => {
  ElDialog.props.closeOnClickModal.default = false;
  app.use(ElementPlus, { size: 'default' });
  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component);
  }
};
