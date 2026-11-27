// Phần bổ sung: trang Cài đặt (key giả) + Virtual Portrait (asset giả lập). Chạy sau shots.js trên cùng backend riêng.
const path = require('path');
const [base, out, pw] = process.argv.slice(2);
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  const { chromium } = require(path.join(pw, 'node_modules', 'playwright-core'));
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 940 }, deviceScaleFactor: 1.5 });
  const shot = async (name, el) => { const o = { path: path.join(out, name + '.jpg'), type: 'jpeg', quality: 82 }; if (el) await (await page.$(el)).screenshot(o); else await page.screenshot(o); console.log('shot', name); };
  const now = Date.now();
  await page.addInitScript(() => { localStorage.setItem('ark-christmas', '0'); localStorage.setItem('ark-dark', '0'); });
  await page.route('**/api/v1/settings/connection-test', (r) => r.fulfill({ json: { apiKeyOk: true, apiKey: 'Kết nối được ModelArk (ap-southeast) – key dùng được', akskOk: true, aksk: 'Ký được OpenAPI – thấy 1 asset group trong project default' } }));
  await page.goto(base + '/#/settings'); await page.waitForSelector('form section.card input'); await wait(1200);
  const inputs = await page.$$('form section.card input');
  await inputs[0].fill('ark-7f3c2a91-demo-0000-key-khong-that');
  await inputs[3].fill('AKLTdemoAccessKeyKhongThat');
  await inputs[4].fill('demoSecretKeyKhongThat==');
  await inputs[5].fill('default');
  await page.click('button:has-text("Kiểm tra kết nối")'); await wait(2000); await page.mouse.move(0, 0);
  await shot('02-settings-filled');
  await page.$eval('section.card:has(h2:text("BOTSD (botsd.pro)"))', (el) => el.scrollIntoView({ block: 'start' })); await wait(500);
  await shot('03-settings-providers');
  const files = (await page.evaluate(async () => (await fetch('/api/v1/files?kind=IMAGE&page=1&size=60')).json())).items.filter((f) => /^CHAR/i.test(f.name)).slice(0, 5);
  await page.route('**/api/v1/asset-groups', (r) => r.request().method() === 'GET' ? r.fulfill({ json: [{ Id: 'group-20261127-ide21', Name: 'IDE 21 – nhân vật' }] }) : r.continue());
  await page.route('**/api/v1/assets?**', (r) => { const st = ['Active', 'Active', 'Active', 'Processing', 'Failed'];
    r.fulfill({ json: { total: files.length, items: files.map((f, i) => ({ Id: 'asset-20261127-a' + (i + 1), Name: f.name.replace(/\.[a-z]+$/i, ''), AssetType: 'Image', Status: st[i], URL: f.url,
      CreateTime: new Date(now - (i + 1) * 3600e3).toISOString(), LastInferenceTime: i < 2 ? new Date(now - 600e3).toISOString() : null,
      Error: i === 4 ? { Code: 'ImageAuditFailed', Message: 'Ảnh không qua kiểm duyệt' } : null })) } }); });
  await page.goto(base + '/#/assets'); await wait(2500); await page.mouse.move(0, 0);
  await shot('22-assets');
  await browser.close();
})();
