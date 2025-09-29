import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { createI18n } from 'vue-i18n';
import App from './App.vue';
import router from './router';
import './assets/main.css';
import { messages } from './locales/messages';
import { useAuthStore } from './stores/auth';

const app = createApp(App);

const pinia = createPinia();
app.use(pinia);

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages
});
app.use(i18n);

app.use(router);

const authStore = useAuthStore(pinia);
authStore.initializeFromStorage();

app.mount('#app');
