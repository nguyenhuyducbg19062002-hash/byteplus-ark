import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import vi from 'element-plus/es/locale/lang/vi'
import * as Icons from '@element-plus/icons-vue'
// Tailwind trước, Element Plus sau để style của Element Plus không bị preflight ghi đè
import './style.css'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './element-overrides.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
for (const [name, comp] of Object.entries(Icons)) app.component(name, comp)
app.use(createPinia()).use(router).use(ElementPlus, { locale: vi }).mount('#app')
