/* 家庭健康记录 Service Worker
 * 缓存策略：network-first（HTML/JS/CSS，避免新版本被旧缓存遮蔽）+ stale-while-revalidate（CDN 资源）
 */
const CACHE = 'family-health-v50';  // v137：重复项目合并显示(用户删多余)+血压拆收缩压/舒张压+tooltip双击+全点数值标注+>8点横滑+异常卡片/药丸浅红底+卡片正常范围
const CORE = ['./', './index.html', './manifest.json'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(CORE)).catch(() => {}));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys =>
    Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
  ));
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);

  // HTML：network-first + cache fallback（新版本优先，旧缓存兜底离线）
  if (e.request.mode === 'navigate' || url.pathname.endsWith('/') || url.pathname.endsWith('.html')) {
    e.respondWith(
      fetch(e.request).then(resp => {
        if (resp.ok) {
          const clone = resp.clone();
          caches.open(CACHE).then(c => c.put(e.request, clone));
        }
        return resp;
      }).catch(() => caches.match(e.request))
    );
    return;
  }

  // Supabase API：直接放行，绝不缓存——API 响应是动态数据（记录增删改），
  // 缓存会导致其它设备的删除/修改在 APP 启动时被旧缓存遮蔽（离线由应用层 localStorage 兜底）
  if (url.hostname.includes('supabase')) return;

  // CDN 静态资源：stale-while-revalidate
  if (url.hostname.includes('jsdelivr')) {
    e.respondWith(
      caches.open(CACHE).then(cache =>
        cache.match(e.request).then(cached => {
          const fetched = fetch(e.request).then(resp => {
            if (resp.ok) cache.put(e.request, resp.clone());
            return resp;
          }).catch(() => cached);
          return cached || fetched;
        })
      )
    );
  }
});

