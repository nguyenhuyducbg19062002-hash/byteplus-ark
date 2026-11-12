import { createRouter, createWebHashHistory } from 'vue-router'

export const routes = [
  { path: '/', redirect: '/image' },
  { path: '/image', component: () => import('./views/ImageView.vue'), meta: { title: 'Tạo ảnh', icon: 'Picture', group: 'Sáng tạo' } },
  { path: '/video', component: () => import('./views/VideoView.vue'), meta: { title: 'Tạo video', icon: 'VideoCamera', group: 'Sáng tạo' } },
  { path: '/script', component: () => import('./views/ScriptView.vue'), meta: { title: 'Kịch bản hàng loạt', icon: 'Film', group: 'Sáng tạo' } },
  { path: '/assets', component: () => import('./views/AssetsView.vue'), meta: { title: 'Virtual Portrait', icon: 'User', group: 'Tài nguyên' } },
  { path: '/library', component: () => import('./views/LibraryView.vue'), meta: { title: 'Thư viện', icon: 'Files', group: 'Tài nguyên' } },
  { path: '/tasks', component: () => import('./views/TasksView.vue'), meta: { title: 'Task video', icon: 'List', group: 'Theo dõi' } },
  { path: '/jobs', component: () => import('./views/JobsView.vue'), meta: { title: 'Tiến trình', icon: 'Timer', group: 'Theo dõi' } },
  { path: '/settings', component: () => import('./views/SettingsView.vue'), meta: { title: 'Cài đặt', icon: 'Setting', group: 'Hệ thống' } }
]

const router = createRouter({ history: createWebHashHistory(), routes })
router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · BytePlus Ark Studio` : 'BytePlus Ark Studio'
})
export default router
