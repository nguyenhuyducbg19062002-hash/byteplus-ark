import { defineStore } from 'pinia'

/**
 * Tham chiếu được gửi từ Thư viện / Virtual Portrait sang trang Tạo ảnh / Tạo video.
 * Mỗi ref: { kind: 'image'|'video'|'audio', value, label, preview }
 * value: data URI | URL | asset://ID | file:{id}
 */
export const useReferencesStore = defineStore('references', {
  state: () => ({ forVideo: [], forImage: [] }),
  actions: {
    sendToVideo(refs) {
      this.forVideo.push(...refs)
    },
    sendToImage(refs) {
      this.forImage.push(...refs)
    },
    takeVideo() {
      return this.forVideo.splice(0)
    },
    takeImage() {
      return this.forImage.splice(0)
    }
  }
})

/** Ref từ 1 file trong thư viện – backend tự đổi sang URL gốc / asset / base64. */
export function refFromFile(f) {
  return { kind: f.kind.toLowerCase(), value: 'file:' + f.id, label: f.name, preview: f.kind === 'IMAGE' ? f.url : null }
}

/** Ref từ asset Virtual Portrait. */
export function refFromAsset(a) {
  const kind = (a.AssetType || 'Image').toLowerCase()
  return { kind, value: 'asset://' + a.Id, label: a.Name || a.Id, preview: kind === 'image' ? a.URL : null }
}
