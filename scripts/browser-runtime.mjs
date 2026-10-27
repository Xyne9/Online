/* 探针可显式复用本机既有浏览器，避免 Playwright 升级后强制重复下载。
 *
 * 背景：Playwright 每次升级都会要求下载与新版本号绑定的 chromium
 * （例如 chromium_headless_shell-1248），而本机可能只装了 1228。
 * 此时 `chromium.launch()` 直接抛
 *   "Executable doesn't exist at .../chromium_headless_shell-1248/..."
 * 整套验收脚本就连一行断言都跑不到。
 *
 * 这里按三级顺序解析可执行文件：
 *   ① 环境变量 OJ_BROWSER_EXECUTABLE（显式指定，最高优先级）
 *   ② Playwright 自带浏览器（版本匹配时用它，行为最贴近 CI）
 *   ③ 本机已装的 Chrome / Edge（Windows 常见路径）
 * 都找不到才抛出原始错误，提示去 npx playwright install。
 */
import { chromium as engine } from 'playwright';
import fs from 'node:fs';

const SYSTEM_CANDIDATES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
];

function resolveExecutable() {
  const explicit = process.env.OJ_BROWSER_EXECUTABLE;
  if (explicit && fs.existsSync(explicit)) return explicit;
  // ② Playwright 自带：先探测一下它到底存不存在，别等 launch 才炸
  try {
    const bundled = engine.executablePath();
    if (bundled && fs.existsSync(bundled)) return undefined; // undefined = 用默认
  } catch { /* 老版本没有该方法，直接走 ③ */ }
  // ③ 本机 Chrome / Edge
  for (const candidate of SYSTEM_CANDIDATES) {
    if (fs.existsSync(candidate)) return candidate;
  }
  return undefined;
}

export function resolveBrowserExecutable() { return resolveExecutable(); }

export const chromium = {
  launch(options = {}) {
    const executablePath = resolveExecutable();
    return engine.launch({ ...options, ...(executablePath ? { executablePath } : {}) });
  }
};
