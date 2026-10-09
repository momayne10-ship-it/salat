/* صلاتي — Service Worker
   Strategies:
   - Navigation (HTML): network-first, fallback to cache
   - Google Fonts: stale-while-revalidate
   - Font Awesome CDN: cache-first
   - Aladhan API: cache-first (date-specific prayer times, safe for the day)
   - Nominatim: network-first with cache fallback
   - sounds/*.mp3: cache-first (on-demand)
   - everything else: stale-while-revalidate
*/

const CACHE_NAME = 'salat-v1';

const APP_SHELL = [
    './',
    'index.html',
    'calendar.html',
    'adhan.html',
    'qibla.html',
    'reminders.html',
    'settings.html',
    'manifest.json',
    'favicon.svg',
    'favicon.ico',
    'style.css',
    'script.js',
    'icons/icon-192.svg',
    'icons/icon-512.svg',
    'icons/icon-maskable-512.svg',
    'icons/icon-192.png',
    'icons/icon-512.png',
    'icons/icon-maskable-512.png',
    'icons/apple-touch-icon.svg',
    'icons/apple-touch-icon.png'
];

const FONT_HOSTS = ['fonts.googleapis.com', 'fonts.gstatic.com'];
const FONT_AWESOME_HOSTS = ['cdnjs.cloudflare.com', 'use.fontawesome.com', 'ka-f.fontawesome.com'];
const ALADHAN_HOSTS = ['api.aladhan.com', 'aladhan.com'];
const NOMINATIM_HOSTS = ['nominatim.openstreetmap.org'];

function isFontUrl(url) {
    return FONT_HOSTS.indexOf(url.hostname) !== -1;
}

function isFontAwesomeUrl(url) {
    return FONT_AWESOME_HOSTS.indexOf(url.hostname) !== -1;
}

function isAladhanUrl(url) {
    return ALADHAN_HOSTS.indexOf(url.hostname) !== -1;
}

function isNominatimUrl(url) {
    return NOMINATIM_HOSTS.indexOf(url.hostname) !== -1;
}

function isSoundUrl(url) {
    return /\/sounds\/[^/?#]+\.mp3$/i.test(url.pathname);
}

function canCache(response) {
    return !!response &&
        (response.ok || response.status === 304 || response.type === 'opaque');
}

function putInCache(request, response) {
    if (!canCache(response)) return Promise.resolve();
    const copy = response.clone();
    return caches.open(CACHE_NAME)
        .then((cache) => cache.put(request, copy))
        .catch(() => undefined);
}

/* ---- strategies ---- */

function cacheFirst(event) {
    const request = event.request;
    event.respondWith(
        caches.match(request).then((cached) => {
            if (cached) return cached;
            return fetch(request).then((response) => {
                if (canCache(response)) putInCache(request, response);
                return response;
            });
        }).catch(() => caches.match(request).then((cached) => cached || Response.error()))
    );
}

function staleWhileRevalidate(event) {
    const request = event.request;
    event.respondWith(
        caches.open(CACHE_NAME).then((cache) => {
            return cache.match(request).then((cached) => {
                const networkPromise = fetch(request).then((response) => {
                    if (canCache(response)) {
                        cache.put(request, response.clone()).catch(() => undefined);
                    }
                    return response;
                }).catch((err) => {
                    if (cached) return cached;
                    throw err;
                });

                if (cached) {
                    event.waitUntil(networkPromise.catch(() => undefined));
                    return cached;
                }
                return networkPromise;
            });
        })
    );
}

function networkOnlyWithCacheFallback(event) {
    const request = event.request;
    event.respondWith(
        fetch(request).then((response) => {
            putInCache(request, response);
            return response;
        }).catch(() =>
            caches.match(request).then((cached) => cached || Response.error())
        )
    );
}

function networkFirstForNavigation(event) {
    const request = event.request;
    event.respondWith(
        fetch(request).then((response) => {
            putInCache(request, response);
            return response;
        }).catch(() =>
            caches.match(request).then((cached) => {
                if (cached) return cached;
                return caches.match('index.html').then((home) => home || Response.error());
            })
        )
    );
}

/* ---- lifecycle ---- */

self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME).then((cache) =>
            Promise.all(APP_SHELL.map((url) =>
                cache.add(new Request(url, { cache: 'reload' })).catch(() => undefined)
            ))
        ).then(() => self.skipWaiting())
    );
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((keys) =>
            Promise.all(
                keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
            )
        ).then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', (event) => {
    const request = event.request;

    if (request.method !== 'GET') return;

    let url;
    try {
        url = new URL(request.url);
    } catch (e) {
        return;
    }

    /* let the browser handle non-http(s) schemes (data:, blob:, chrome-extension:, ...) */
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return;

    /* let the browser handle audio Range requests (seeking) — never cache-range */
    if (request.headers.has('Range')) return;

    if (request.mode === 'navigate') {
        networkFirstForNavigation(event);
        return;
    }

    if (isFontUrl(url)) {
        staleWhileRevalidate(event);
        return;
    }

    if (isFontAwesomeUrl(url)) {
        cacheFirst(event);
        return;
    }

    if (isAladhanUrl(url)) {
        cacheFirst(event);
        return;
    }

    if (isNominatimUrl(url)) {
        networkOnlyWithCacheFallback(event);
        return;
    }

    if (isSoundUrl(url)) {
        cacheFirst(event);
        return;
    }

    staleWhileRevalidate(event);
});
