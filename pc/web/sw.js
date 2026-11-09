// Minimal service worker: lets Android install PhoneWheel as a full-screen app.
// No caching - the app always loads fresh from your PC.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => {});
