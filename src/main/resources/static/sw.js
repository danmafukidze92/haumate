// Minimal service worker so the admin panel installs as an app on Android/iOS ("Add to Home Screen").
self.addEventListener('install', e => self.skipWaiting());
self.addEventListener('fetch', () => {});
