// Mínimo para o Android oferecer "Instalar app". O painel em si vive no claude.ai.
self.addEventListener('install', e => self.skipWaiting());
self.addEventListener('activate', e => self.clients.claim());
self.addEventListener('fetch', () => {});
