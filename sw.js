/* 家庭健康记录 Service Worker
 * 缓存策略：network-first（HTML/JS/CSS，避免新版本被旧缓存遮蔽）+ stale-while-revalidate（CDN 资源）
 */
const CACHE = 'family-health-v27';  // 改 cache 名强制旧 SW 失效（v111：SW更新报错静默+月经连续记录合并+预测规则重写(预存5天窗)+可填项编辑弹窗+AI补正常范围+趋势分析收起）
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

  // CDN：stale-while-revalidate
  if (url.hostname.includes('jsdelivr') || url.hostname.includes('supabase')) {
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

