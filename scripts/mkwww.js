/* بناء مجلد www/ لمصفوفة Capacitor — ينسخ ملفات الموقع فقط */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const WWW = path.join(ROOT, 'www');

const FILES = [
    'index.html', 'calendar.html', 'adhan.html', 'qibla.html',
    'reminders.html', 'settings.html',
    'style.css', 'script.js', 'sw.js', 'manifest.json',
    'favicon.ico', 'favicon.svg'
];
const DIRS = ['icons', 'sounds'];

fs.rmSync(WWW, { recursive: true, force: true });
fs.mkdirSync(WWW, { recursive: true });

for (const f of FILES) {
    const src = path.join(ROOT, f);
    if (!fs.existsSync(src)) { console.error('MISSING:', f); process.exitCode = 1; continue; }
    fs.copyFileSync(src, path.join(WWW, f));
}
for (const d of DIRS) {
    fs.cpSync(path.join(ROOT, d), path.join(WWW, d), { recursive: true });
}

const count = [];
(function walk(dir) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) walk(p); else count.push(p);
    }
})(WWW);
console.log('www OK —', count.length, 'files');
