/* 有序 · 离线缓存。先用本地缓存立即打开，再在后台更新，所以网络不通时也能用。 */
const V = 'youxu-089f3119a9';
const SHELL = ['./', 'index.html', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png'];
self.addEventListener('install', (e) => { e.waitUntil(caches.open(V).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', (e) => { e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== V && k.indexOf('youxu-') === 0).map((k) => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', (e) => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== self.location.origin) return;   // 同步请求不经过缓存
  e.respondWith(caches.open(V).then(async (c) => {
    const hit = (await c.match(e.request, { ignoreSearch: true })) || (e.request.mode === 'navigate' ? await c.match('index.html') : null);
    // 后台更新时去掉网址里的参数：分享进来的文字只留在本机，不会发给服务器
    const clean = u.origin + u.pathname;
    const net = fetch(clean, { cache: 'no-cache' }).then((res) => { if (res && res.ok) c.put(clean, res.clone()); return res; }).catch(() => null);
    return hit || (await net) || new Response('离线，且还没有缓存', { status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
  }));
});
