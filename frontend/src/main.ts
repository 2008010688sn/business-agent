import { createApp } from 'vue';
import './plugins/assets';
import { authDirective } from '@/hooks/business/authDirective';
import { smoothShowDirective } from '@/hooks/business/smoothShowDirective';
import { setupDayjs, setupIconifyOffline, setupLoading, setupNProgress, setupUI } from './plugins';
import { setupStore } from './store';
import { setupRouter } from './router';
import { setupI18n } from './locales';
import App from './App.vue';

async function setupApp() {
  setupLoading();
  setupNProgress();
  setupIconifyOffline();
  setupDayjs();

  const app = createApp(App);

  authDirective(app);
  smoothShowDirective(app);
  setupUI(app);
  setupStore(app);
  await setupRouter(app);
  setupI18n(app);

  app.mount('#app');
}

setupApp();
