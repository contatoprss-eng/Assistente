/* Service worker: deixa o app abrir sem internet e mostra as notificações. */
const VERSAO = 'assistente-v3';
const ARQUIVOS = [
  './', 'index.html', 'estilo.css', 'nucleo.js', 'manifest.webmanifest',
  'modulos/calendario.js', 'modulos/decisoes.js', 'modulos/conversa.js', 'dados/decisoes.json',
  'icones/icone.svg', 'icones/icone-180.png', 'icones/icone-192.png', 'icones/icone-512.png'
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSAO).then(c => c.addAll(ARQUIVOS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(ks => Promise.all(ks.filter(k => k !== VERSAO).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});
// Rede primeiro (pega atualizações), cache se estiver sem internet.
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    fetch(e.request).then(r => {
      if (r.ok && new URL(e.request.url).origin === location.origin) {
        const copia = r.clone();
        caches.open(VERSAO).then(c => c.put(e.request, copia));
      }
      return r;
    }).catch(() => caches.match(e.request).then(r => r || caches.match('index.html')))
  );
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(cs => {
    const c = cs.find(x => 'focus' in x);
    return c ? c.focus() : self.clients.openWindow('./');
  }));
});
