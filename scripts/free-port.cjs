// Giải phóng cổng trước khi chạy: tắt tiến trình cũ (node/java) đang chiếm cổng.
// Dùng: node scripts/free-port.cjs <port> <tên tiến trình được phép tắt, vd node.exe>
// Chỉ tắt đúng loại tiến trình chỉ định để không đụng nhầm chương trình khác.
const { execSync } = require('child_process')

const port = process.argv[2]
const allowed = (process.argv[3] || '').toLowerCase()
if (!port || process.platform !== 'win32') process.exit(0)

let lines = []
try {
  lines = execSync('netstat -ano -p TCP', { encoding: 'utf8' }).split(/\r?\n/)
} catch { process.exit(0) }

const pids = new Set()
for (const l of lines) {
  const cols = l.trim().split(/\s+/)
  // Proto  Local  Foreign  State  PID
  if (cols.length >= 5 && cols[3] === 'LISTENING' && cols[1].endsWith(':' + port)) pids.add(cols[4])
}
// netstat -p TCP không liệt kê IPv6 – kiểm tra thêm
try {
  for (const l of execSync('netstat -ano -p TCPv6', { encoding: 'utf8' }).split(/\r?\n/)) {
    const cols = l.trim().split(/\s+/)
    if (cols.length >= 5 && cols[3] === 'LISTENING' && cols[1].endsWith(':' + port)) pids.add(cols[4])
  }
} catch { /* bỏ qua */ }

for (const pid of pids) {
  if (pid === '0' || pid === String(process.pid)) continue
  let name = ''
  try {
    name = execSync(`tasklist /FI "PID eq ${pid}" /FO CSV /NH`, { encoding: 'utf8' }).split(',')[0].replace(/"/g, '').trim().toLowerCase()
  } catch { continue }
  if (allowed && name !== allowed) {
    console.error(`[free-port] Cổng ${port} đang bị ${name} (PID ${pid}) chiếm – không phải ${allowed} nên không tự tắt.`)
    process.exit(1)
  }
  try {
    execSync(`taskkill /PID ${pid} /F`, { stdio: 'ignore' })
    console.log(`[free-port] Đã tắt tiến trình cũ ${name} (PID ${pid}) đang giữ cổng ${port}.`)
  } catch { /* đã tắt */ }
}
