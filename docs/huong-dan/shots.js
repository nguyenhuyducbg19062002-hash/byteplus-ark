// Ảnh chụp cho hướng dẫn Ark Studio. Chạy với 1 backend RIÊNG (port khác, user.home tạm, key giả) – không đụng app đã cài.
// Các API gọi BytePlus (kiểm tra kết nối, tạo ảnh / video, task, asset) được giả lập bằng page.route: không tốn tiền, không cần key thật.
// node shots.js <url backend> <thư mục dự án mẫu (bản sao)> <thư mục ảnh ra> <thư mục có node_modules/playwright-core>
const path = require('path');
const [base, projDir, out, pw] = process.argv.slice(2);
require('fs').mkdirSync(out, { recursive: true });
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  const { chromium } = require(path.join(pw, 'node_modules', 'playwright-core'));
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 940 }, deviceScaleFactor: 1.5 });
  page.on('pageerror', (e) => console.log('PAGEERR', e.message));
  const shot = async (name, el, opts = {}) => {
    const o = { path: path.join(out, name + '.jpg'), type: 'jpeg', quality: 82, ...opts };
    if (el) await (await page.$(el)).screenshot(o); else await page.screenshot(o);
    console.log('shot', name);
  };
  const step = async (name, fn) => { try { await fn(); } catch (e) { console.log('FAIL', name, e.message.split('\n')[0]); } };
  const go = async (hash) => { await page.goto(base + '/#' + hash); await wait(1500); };
  const now = Date.now();

  // ---- giả lập các API gọi ra BytePlus ----
  await page.route('**/api/v1/settings/connection-test', (r) => r.fulfill({ json: { apiKeyOk: true, apiKey: 'Kết nối được ModelArk (ap-southeast) – key dùng được', akskOk: true, aksk: 'Ký được OpenAPI – thấy 1 asset group trong project default' } }));
  let files = [];
  await page.route('**/api/v1/images/generations', (r) => r.fulfill({ json: { id: 'demo-img' } }));
  await page.route('**/api/v1/videos/generations', (r) => r.fulfill({ json: { id: 'demo-vid' } }));
  await page.route('**/api/v1/jobs/demo-img', (r) => r.fulfill({ json: { id: 'demo-img', status: 'SUCCEEDED', title: 'Tạo ảnh · Seedream 5.0', createdAt: new Date(now - 40000).toISOString(),
    logs: ['Gửi yêu cầu tới seedream-5-0 (2K, 1 ảnh)…', 'BytePlus trả về 1 ảnh sau 18s', 'Đã lưu: output\\images\\20261127-135512_seedream-5-0_1.jpeg', 'Xong'], files: files.slice(0, 2) } }));
  await page.route('**/api/v1/jobs/demo-vid', (r) => r.fulfill({ json: { id: 'demo-vid', status: 'RUNNING', title: 'Tạo video · Seedance 2.0', taskId: 'cgt-20261127135840-7xk2p', createdAt: new Date(now - 95000).toISOString(),
    logs: ['Đẩy 2 ảnh nhân vật lên Virtual Portrait (asset group của bạn)…', 'Asset Active: asset://asset-20261127-a1, asset://asset-20261127-a2', 'Tạo task cgt-20261127135840-7xk2p (720p, 16:9, 10s, có âm thanh)', 'queued…', 'running… (45s)', 'running… (90s)'] } }));
  await page.route('**/api/v1/video-tasks?**', (r) => r.fulfill({ json: { total: 4, items: [
    { id: 'cgt-20261127135840-7xk2p', model: 'dreamina-seedance-2-0-260128', status: 'running', resolution: '720p', ratio: '16:9', duration: 10, created_at: Math.floor(now / 1000) - 95 },
    { id: 'cgt-20261127131204-m2q8d', model: 'dreamina-seedance-2-0-260128', status: 'succeeded', resolution: '720p', ratio: '16:9', duration: 15, created_at: Math.floor(now / 1000) - 2700 },
    { id: 'cgt-20261127130511-c9w1z', model: 'dreamina-seedance-2-0-fast-260128', status: 'failed', resolution: '480p', ratio: '9:16', duration: 5, created_at: Math.floor(now / 1000) - 3300, error: { code: 'InputImageSensitiveContentDetected', message: 'The request failed because the input image may contain real person.' } },
    { id: 'cgt-20261126172210-p0a5t', model: 'seedance-1-5-pro-251215', status: 'succeeded', resolution: '1080p', ratio: '16:9', duration: 8, created_at: Math.floor(now / 1000) - 75000 }] } }));

  await page.route('**/api/v1/asset-groups', (r) => r.request().method() === 'GET' ? r.fulfill({ json: [{ Id: 'group-20261127-ide21', Name: 'IDE 21 – nhân vật', ProjectName: 'default' }] }) : r.continue());
  await page.route('**/api/v1/assets?**', (r) => { const img = files.filter((f) => f.kind === 'IMAGE' && /CHAR/i.test(f.name || f.fileName || f.url)).slice(0, 5);
    const st = ['Active', 'Active', 'Active', 'Processing', 'Failed'];
    r.fulfill({ json: { total: img.length, items: img.map((f, i) => ({ Id: 'asset-20261127-a' + (i + 1), Name: (f.name || f.fileName || 'CHAR').replace(/\.[a-z]+$/i, ''), AssetType: 'Image', Status: st[i], URL: f.url, CreateTime: new Date(now - (i + 1) * 3600e3).toISOString(), LastInferenceTime: i < 2 ? new Date(now - 600e3).toISOString() : null, Error: i === 4 ? { Code: 'ImageAuditFailed', Message: 'Ảnh không qua kiểm duyệt' } : null })) } }); });
  await page.addInitScript(() => { localStorage.setItem('ark-christmas', '0'); localStorage.setItem('ark-dark', '0'); });
  await page.route('**/api/v1/jobs?**', (r) => r.fulfill({ json: { total: 4, items: [
    { id: 'j4', status: 'RUNNING', type: 'video', title: 'Tạo video · Seedance 2.0 · 720p 16:9 10s', createdAt: new Date(now - 95000).toISOString(), files: [] },
    { id: 'j3', status: 'SUCCEEDED', type: 'asset-push', title: 'Đẩy 2 ảnh vào Virtual Portrait', createdAt: new Date(now - 160000).toISOString(), files: [] },
    { id: 'j2', status: 'SUCCEEDED', type: 'image', title: 'Tạo ảnh · Seedream 5.0 · 2K', createdAt: new Date(now - 400000).toISOString(), files: [{}] },
    { id: 'j1', status: 'FAILED', type: 'video', title: 'Tạo video · Seedance 2.0 fast · 480p 9:16 5s', createdAt: new Date(now - 3300000).toISOString(), files: [], error: 'InputImageSensitiveContentDetected: ảnh đầu vào có thể chứa người thật' }] } }));
  await page.goto(base + '/');
  await go('/settings');

  await step('settings-empty', async () => { await shot('01-settings-empty'); });
  await step('settings-fill', async () => {
    await page.waitForSelector('form section.card input', { timeout: 20000 }); await wait(500);
    const inputs = await page.$$('form section.card input');
    console.log('inputs', inputs.length);
    await inputs[0].fill('ark-7f3c2a91-demo-0000-key-khong-that');
    await inputs[3].fill('AKLTdemoAccessKeyKhongThat');
    await inputs[4].fill('demoSecretKeyKhongThat==');
    await inputs[5].fill('default');
    await page.click('button:has-text("Kiểm tra kết nối")'); await wait(1800);
    await page.mouse.move(0, 0);
    await shot('02-settings-filled', null, { fullPage: false });
    await page.$eval('h2:text("BOTSD (botsd.pro)")', (el) => el.scrollIntoView({ block: 'start' })); await wait(500);
    await shot('03-settings-providers');
  });

  await step('library-files', async () => {
    const r = await page.evaluate(async () => (await fetch('/api/v1/files?kind=IMAGE&page=1&size=40')).json()); files = r.items || [];
  });

  await step('image', async () => {
    await go('/image');
    await shot('04-image-empty');
    await page.fill('textarea', 'Character sheet of a 45-year-old man in a navy wool coat, full body + close-up face, plain light grey background, natural soft light, realistic photo');
    await page.click('button:has-text("Tạo ảnh")'); await wait(2500);
    await shot('05-image-result');
  });

  await step('video', async () => {
    await go('/video');
    const sel = await page.$$('form .el-select');
    await sel[1].click(); await wait(500);
    await shot('06-video-modes');
    const omni = await page.$('.el-select-dropdown__item:has-text("tham chiếu")');
    if (omni) await omni.click(); else await page.keyboard.press('Escape');
    await wait(500);
    await page.fill('textarea', 'The man in Image 1 walks into the ballroom in Image 2, stops, looks around nervously. Slow dolly-in, warm evening light.');
    await shot('07-video-omni');
    await page.click('button:has-text("Tạo video")'); await wait(2500);
    await shot('08-video-running');
  });

  await step('script-empty', async () => { await go('/script'); await shot('09-script-empty'); });
  await step('folder-dialog', async () => {
    await page.click('button:has-text("Mở thư mục dự án")'); await wait(800);
    await page.fill('.el-dialog input', projDir);
    await page.click('.el-dialog button:has-text("Quét")');
    await page.waitForSelector('.el-dialog table', { timeout: 120000 }); await wait(800);
    await shot('10-folder-scan', '.el-dialog');
    await page.click('.el-dialog__footer button.el-button--primary, .el-dialog button:has-text("Mở dự án")');
    await page.waitForSelector('.script-table', { timeout: 300000 }); await wait(2500);
    await page.mouse.move(0, 0);
    await shot('11-project-top');
  });
  await step('config', async () => {
    await page.click('.el-collapse-item__header'); await wait(900);
    const c = await page.$('.el-collapse');
    await c.scrollIntoViewIfNeeded(); await wait(400);
    await shot('12-config', '.el-collapse');
  });
  await step('clips', async () => {
    await page.click('.el-collapse-item__header'); await wait(600);
    await page.$eval('.el-tabs', (el) => el.scrollIntoView({ block: 'start' })); await wait(800); await page.mouse.move(0, 0);
    await shot('13-clips');
  });
  await step('clip-detail', async () => {
    const rows = await page.$$('.script-table .el-table__row');
    await rows[0].click(); await wait(1200);
    const ex = await page.$('.el-table__expanded-cell');
    await ex.scrollIntoViewIfNeeded(); await wait(300);
    await shot('14-clip-detail', '.el-table__expanded-cell');
    await rows[0].click(); await wait(500);
  });
  await step('refs', async () => {
    await page.click('.el-tabs__item:has-text("Ảnh tham chiếu")'); await wait(2000);
    await page.$eval('.el-tabs', (el) => el.scrollIntoView({ block: 'start' })); await wait(800);
    await page.mouse.move(0, 0);
    await shot('15-refs');
    const missing = await page.$('label:has-text("Thiếu")');
    if (missing) { await missing.click(); await wait(1200); await shot('16-refs-missing'); }
    const all = await page.$('label:has-text("Tất cả")'); if (all) { await all.click(); await wait(800); }
  });
  await step('prompt', async () => {
    const b = page.locator('button:visible', { hasText: /^Prompt$/ }).first();
    await b.click(); await wait(1000);
    await page.waitForSelector('.el-dialog textarea'); await wait(400); const ds = await page.$$('.el-dialog'); for (const d of ds) if (await d.isVisible()) { await d.screenshot({ path: path.join(out, '18-ref-prompt.jpg'), type: 'jpeg', quality: 82 }); console.log('shot 18'); }
    await page.keyboard.press('Escape'); await wait(500);
  });
  await step('library', async () => { await go('/library'); await page.click('label:has-text("Ảnh")').catch(() => {}); await wait(1500); await shot('19-library'); });
  await step('tasks', async () => { await go('/tasks'); await shot('20-tasks'); });
  await step('jobs', async () => { await go('/jobs'); await shot('21-jobs'); });
  await step('assets', async () => { await go('/assets'); await shot('22-assets'); });
  await browser.close();
})();
