import './style.css'
import './styles/interview.css'
import '@fortawesome/fontawesome-free/css/all.min.css'

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// 路由必须在 mount 之前注册：App.vue 里直接用了 <RouterView>
createApp(App).use(router).mount('#app')
