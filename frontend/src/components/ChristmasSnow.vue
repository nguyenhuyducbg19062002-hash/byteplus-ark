<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'

/**
 * Tuyết rơi (canvas phủ toàn màn hình, không chặn chuột). Dừng khi cửa sổ bị ẩn để không tốn CPU khi app chạy nền;
 * máy bật "giảm chuyển động" thì chỉ vẽ tuyết đứng yên.
 */
const canvas = ref(null)
let ctx = null
let flakes = []
let raf = 0
let w = 0
let h = 0
const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

function resize() {
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  w = window.innerWidth
  h = window.innerHeight
  canvas.value.width = w * dpr
  canvas.value.height = h * dpr
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  const n = Math.round(Math.min(160, (w * h) / 9000))
  flakes = Array.from({ length: n }, () => flake(true))
}

function flake(anywhere) {
  const r = Math.random() * 2.6 + 0.8
  return {
    x: Math.random() * w,
    y: anywhere ? Math.random() * h : -10,
    r,
    vy: 0.35 + r * 0.28 + Math.random() * 0.3, // bông to rơi nhanh hơn → cảm giác gần / xa
    sway: Math.random() * Math.PI * 2,
    swaySpeed: 0.008 + Math.random() * 0.012,
    alpha: 0.45 + Math.random() * 0.5
  }
}

function draw() {
  ctx.clearRect(0, 0, w, h)
  for (const f of flakes) {
    ctx.beginPath()
    ctx.fillStyle = `rgba(255,255,255,${f.alpha})`
    ctx.arc(f.x, f.y, f.r, 0, Math.PI * 2)
    ctx.fill()
  }
}

function step() {
  for (let i = 0; i < flakes.length; i++) {
    const f = flakes[i]
    f.sway += f.swaySpeed
    f.y += f.vy
    f.x += Math.sin(f.sway) * 0.45
    if (f.y > h + 8 || f.x < -10 || f.x > w + 10) flakes[i] = flake(false)
  }
  draw()
  raf = requestAnimationFrame(step)
}

function onVisibility() {
  cancelAnimationFrame(raf)
  if (!document.hidden && !reduce) raf = requestAnimationFrame(step)
}

onMounted(() => {
  ctx = canvas.value.getContext('2d')
  resize()
  window.addEventListener('resize', resize)
  document.addEventListener('visibilitychange', onVisibility)
  if (reduce) draw()
  else raf = requestAnimationFrame(step)
})
onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  window.removeEventListener('resize', resize)
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <canvas ref="canvas" class="pointer-events-none fixed inset-0 z-[1500]" aria-hidden="true" />
</template>
