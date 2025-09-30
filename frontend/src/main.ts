import { createApp, watch } from 'vue';
import { createPinia } from 'pinia';
import { createI18n } from 'vue-i18n';
import App from './App.vue';
import router from './router';
import './assets/main.css';
import { messages } from './locales/messages';
import { useAuthStore } from './stores/auth';
import { usePreferencesStore } from './stores/preferences';

const app = createApp(App);

const pinia = createPinia();
app.use(pinia);

const preferencesStore = usePreferencesStore(pinia);
const startingLocale = preferencesStore.initialize();

const i18n = createI18n({
  legacy: false,
  locale: startingLocale,
  fallbackLocale: 'en',
  messages
});
app.use(i18n);

watch(
  () => preferencesStore.locale,
  (next) => {
    i18n.global.locale.value = next;
  },
  { immediate: true }
);

app.use(router);

const authStore = useAuthStore(pinia);
authStore.initializeFromStorage();

app.mount('#app');
