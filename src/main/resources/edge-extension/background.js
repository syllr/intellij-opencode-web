// 主动给 OpenCode server origin 预置 notification allow
// 走 Edge 内部正确路径(跟用户在 edge://settings 里手动改"阻止"为"允许"是同一条路径),
// 会清掉 QuietNotificationPrompts 永久 block 状态(permission_autoblocking_data)。
//
// 背景:每个 project 走独立 --user-data-dir profile,新 profile 第一次访问 localhost:12396 时
// permission = "default",Edge 会弹"是否允许通知"框。如果用户连续 3 次忽略(不点允许),
// Chromium 的 QuietNotificationPrompts feature 永久抑制该 origin 的弹框,之后
// requestPermission() 直接返回 "denied" 不弹框 — 用户感知"通知失效"。
//
// 这个 background service worker 每次启动都执行一次 set(),把所有中毒的 / 新建的 profile
// 都强制设成 allow。用户从此不需要手动操作 Edge 设置。
const TARGET_ORIGIN = 'http://localhost:12396';

if (chrome.contentSettings && chrome.contentSettings.notifications) {
  chrome.contentSettings.notifications.set(
    {
      primaryPattern: TARGET_ORIGIN + '/*',
      scope: 'regular',
      setting: 'allow',
    },
    () => {
      if (chrome.runtime.lastError) {
        console.error(
          '[opencode-web-ext] pre-allow notifications FAILED:',
          chrome.runtime.lastError.message
        );
      } else {
        console.log(
          '[opencode-web-ext] pre-allow notifications OK for ' + TARGET_ORIGIN
        );
      }
    }
  );
} else {
  console.warn(
    '[opencode-web-ext] chrome.contentSettings.notifications API not available in this context'
  );
}
