const path = require('path');
const [app, html, out] = process.argv.slice(2);
(async () => {
  const { chromium } = require(path.join(app, 'node_modules', 'playwright-core'));
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  const page = await browser.newPage();
  await page.goto(require('url').pathToFileURL(html).href, { waitUntil: 'networkidle' });
  await page.pdf({ path: out, format: 'A4', printBackground: true, preferCSSPageSize: true, displayHeaderFooter: true,
    headerTemplate: '<div></div>',
    footerTemplate: '<div style="width:100%;font:8px Segoe UI,Arial;color:#8a90a0;padding:0 15mm;display:flex;justify-content:space-between"><span>Ark Studio · Hướng dẫn sử dụng</span><span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>' });
  await browser.close();
})();
