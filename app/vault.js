'use strict';
/*
 * PHANTOM VAULT – web edition.
 * Everything is encrypted in the browser with WebCrypto and stored in IndexedDB on this device only.
 * Key hierarchy: random 256-bit master key (DEK) ← wrapped by PBKDF2-SHA256(PIN). Wrong PIN = GCM auth failure.
 */
(() => {
const $ = (id) => document.getElementById(id);
const enc = new TextEncoder(), dec = new TextDecoder();
const ITER = 310000, FREE_ATTEMPTS = 5;
const DB_NAME = 'phantom-vault', DB_VER = 1;

// ---------------- IndexedDB ----------------
let dbp;
function db() {
  return dbp ||= new Promise((res, rej) => {
    const r = indexedDB.open(DB_NAME, DB_VER);
    r.onupgradeneeded = () => {
      r.result.createObjectStore('meta');
      r.result.createObjectStore('items', { keyPath: 'id' });
    };
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  });
}
async function tx(store, mode, fn) {
  const d = await db();
  return new Promise((res, rej) => {
    const t = d.transaction(store, mode); const s = t.objectStore(store);
    const out = fn(s); t.oncomplete = () => res(out instanceof IDBRequest ? out.result : out); t.onerror = () => rej(t.error);
  });
}
const metaGet = (k) => tx('meta', 'readonly', (s) => s.get(k));
const metaSet = (k, v) => tx('meta', 'readwrite', (s) => s.put(v, k));
const itemsAll = () => tx('items', 'readonly', (s) => s.getAll());
const itemPut = (v) => tx('items', 'readwrite', (s) => s.put(v));
const itemDel = (id) => tx('items', 'readwrite', (s) => s.delete(id));
const itemGet = (id) => tx('items', 'readonly', (s) => s.get(id));

// ---------------- Crypto ----------------
const rnd = (n) => crypto.getRandomValues(new Uint8Array(n));
async function kekFrom(pin, salt, iter) {
  const base = await crypto.subtle.importKey('raw', enc.encode(pin), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
async function gcmEnc(key, data) {
  const iv = rnd(12);
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, data);
  return { iv, ct };
}
const gcmDec = (key, box) => crypto.subtle.decrypt({ name: 'AES-GCM', iv: box.iv }, key, box.ct);

let DEK_RAW = null, DEK = null; // in memory only while unlocked
async function setDek(raw) {
  DEK_RAW = raw;
  DEK = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
}
async function wrapPin(pin) {
  const salt = rnd(16);
  const kek = await kekFrom(pin, salt, ITER);
  await metaSet('key', { salt, iter: ITER, box: await gcmEnc(kek, DEK_RAW), len: pin.length });
}
const encJson = async (o) => gcmEnc(DEK, enc.encode(JSON.stringify(o)));
const decJson = async (b) => JSON.parse(dec.decode(await gcmDec(DEK, b)));

// ---------------- UI helpers ----------------
function show(id) { for (const s of ['setup', 'lock', 'main']) $(s).hidden = s !== id; }
let toastT;
function toast(m) { const t = $('toast'); t.textContent = m; t.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => t.hidden = true, 3200); }
function busy(m) { $('busy').hidden = !m; $('busyMsg').textContent = m || ''; }
function el(tag, attrs = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') e.className = v; else if (k === 'style') e.style.cssText = v; else if (k.startsWith('on')) e.addEventListener(k.slice(2), v); else e.setAttribute(k, v);
  }
  for (const k of kids) e.append(k);
  return e;
}
function fmtSize(b) { return b >= 1 << 30 ? (b / (1 << 30)).toFixed(1) + ' GB' : b >= 1 << 20 ? (b / (1 << 20)).toFixed(1) + ' MB' : b >= 1024 ? Math.round(b / 1024) + ' KB' : b + ' B'; }
function hue(s) { let h = 0; for (const c of s) h = (h * 31 + c.codePointAt(0)) % 360; return h; }
function modal(build) {
  const sh = $('sheet'); sh.replaceChildren(); build(sh); $('modal').hidden = false;
}
function closeModal() { $('modal').hidden = true; $('sheet').replaceChildren(); }
$('modal').addEventListener('click', (e) => { if (e.target.id === 'modal') closeModal(); });

// ---------------- PIN pads ----------------
function buildPad(container, onDigit, onDel) {
  for (const k of ['1','2','3','4','5','6','7','8','9','','0','⌫']) {
    const b = el('button', { type: 'button', class: k === '⌫' || k === '' ? 'ghost' : '' }, k);
    if (k === '') b.disabled = true;
    else b.addEventListener('click', () => { navigator.vibrate?.(8); k === '⌫' ? onDel() : onDigit(k); });
    container.append(b);
  }
}
function drawDots(id, n, filled, err) {
  const d = $(id); d.replaceChildren(); d.classList.toggle('err', !!err);
  for (let i = 0; i < n; i++) d.append(el('i', { class: i < filled ? 'on' : '' }));
}
function shake(id) { const d = $(id); d.classList.remove('shake'); void d.offsetWidth; d.classList.add('shake'); }

// ---------------- Setup ----------------
let setupPin = '', setupFirst = null;
const setupPad = document.querySelector('.pad[data-target=setup]');
buildPad(setupPad, (k) => { if (setupPin.length < 12) { setupPin += k; renderSetup(); } }, () => { setupPin = setupPin.slice(0, -1); renderSetup(); });
function renderSetup() {
  drawDots('setupDots', Math.max(setupPin.length, 4), setupPin.length);
  $('setupOk').hidden = setupPin.length < 4;
}
$('setupOk').addEventListener('click', async () => {
  if (setupFirst === null) {
    setupFirst = setupPin; setupPin = ''; $('setupMsg').textContent = 'הזן שוב לאימות'; renderSetup(); return;
  }
  if (setupPin !== setupFirst) {
    drawDots('setupDots', setupPin.length, setupPin.length, true); shake('setupDots');
    toast('הקודים לא תואמים – נסה שוב'); setupFirst = null; setupPin = '';
    setTimeout(() => { $('setupMsg').textContent = 'בחר קוד סודי (4–12 ספרות)'; renderSetup(); }, 500); return;
  }
  busy('יוצר מפתח הצפנה…');
  try {
    await setDek(rnd(32)); await wrapPin(setupPin);
    await metaSet('apps', await encJson([]));
    setupPin = ''; setupFirst = null; busy(); openVault();
  } catch (e) { busy(); toast('שגיאה ביצירת הכספת'); }
});

// ---------------- Lock ----------------
let lockPin = '', lockLen = 6, checking = false;
const lockPad = document.querySelector('.pad[data-target=lock]');
buildPad(lockPad, (k) => { if (!checking && lockoutLeft() === 0 && lockPin.length < lockLen) { lockPin += k; drawDots('lockDots', lockLen, lockPin.length); if (lockPin.length === lockLen) tryUnlock(); } },
  () => { if (!checking) { lockPin = lockPin.slice(0, -1); drawDots('lockDots', lockLen, lockPin.length); } });
const fails = () => +localStorage.getItem('pv_fails') || 0;
const lockoutLeft = () => Math.max(0, (+localStorage.getItem('pv_lock_until') || 0) - Date.now());
function lockMsg() {
  const left = lockoutLeft();
  $('lockMsg').textContent = left ? `יותר מדי ניסיונות. נסה שוב בעוד ${Math.ceil(left / 1000)} שניות`
    : fails() ? `קוד שגוי · ניסיון ${fails()}` : 'הזן קוד גישה';
  $('lockMsg').classList.toggle('red', !!(left || fails()));
  lockPad.querySelectorAll('button').forEach((b) => { if (b.textContent) b.disabled = !!left; });
  if (left) setTimeout(lockMsg, 1000);
}
async function tryUnlock() {
  checking = true; $('lockMsg').textContent = 'מאמת…';
  const k = await metaGet('key');
  try {
    const kek = await kekFrom(lockPin, k.salt, k.iter);
    const raw = new Uint8Array(await gcmDec(kek, k.box));
    await setDek(raw);
    localStorage.setItem('pv_fails', 0); localStorage.setItem('pv_lock_until', 0);
    lockPin = ''; checking = false; openVault();
  } catch {
    const f = fails() + 1; localStorage.setItem('pv_fails', f);
    if (f >= FREE_ATTEMPTS) localStorage.setItem('pv_lock_until', Date.now() + Math.min(3600e3, 30e3 * 2 ** Math.min(7, f - FREE_ATTEMPTS)));
    drawDots('lockDots', lockLen, lockLen, true); shake('lockDots');
    setTimeout(() => { lockPin = ''; checking = false; drawDots('lockDots', lockLen, 0); lockMsg(); }, 450);
  }
}
function lock() {
  if (DEK_RAW) DEK_RAW.fill(0);
  DEK_RAW = null; DEK = null;
  closeViewer(); closeModal();
  $('appsGrid').replaceChildren(); $('filesGrid').replaceChildren(); $('notesList').replaceChildren();
  thumbUrls.forEach(URL.revokeObjectURL); thumbUrls = [];
  start();
}

// ---------------- Auto-lock ----------------
let hiddenAt = 0;
document.addEventListener('visibilitychange', () => {
  if (!DEK) return;
  const secs = +(localStorage.getItem('pv_autolock') || 0);
  if (document.hidden) { hiddenAt = Date.now(); if (secs === 0 && !pickerActive()) lock(); }
  else { if (hiddenAt && !pickerActive() && Date.now() - hiddenAt > secs * 1000) lock(); hiddenAt = 0; pickerUntil = 0; }
});
$('lockBtn').addEventListener('click', lock); $('lockBtn2').addEventListener('click', lock);
$('autoLock').addEventListener('change', (e) => localStorage.setItem('pv_autolock', e.target.value));

// ---------------- Main / tabs ----------------
const titles = { apps: 'אפליקציות', files: 'קבצים', notes: 'פתקים', settings: 'הגדרות' };
document.querySelectorAll('nav button').forEach((b) => b.addEventListener('click', () => {
  document.querySelectorAll('nav button').forEach((x) => x.classList.toggle('on', x === b));
  for (const t of Object.keys(titles)) $('tab-' + t).hidden = t !== b.dataset.tab;
  $('tabTitle').textContent = titles[b.dataset.tab];
}));
async function openVault() {
  show('main');
  $('autoLock').value = localStorage.getItem('pv_autolock') || '0';
  await Promise.all([renderApps(), renderFiles(), renderNotes()]);
}

// ---------------- Apps ----------------
const PRESETS = [
  ['WhatsApp', 'https://wa.me/'], ['Instagram', 'https://www.instagram.com/'], ['TikTok', 'https://www.tiktok.com/'],
  ['YouTube', 'https://www.youtube.com/'], ['Telegram', 'https://t.me/'], ['Snapchat', 'https://www.snapchat.com/'],
  ['Facebook', 'https://www.facebook.com/'], ['X', 'https://x.com/'], ['Spotify', 'https://open.spotify.com/'],
  ['Discord', 'https://discord.com/app'], ['Gmail', 'https://mail.google.com/'], ['Netflix', 'https://www.netflix.com/'],
];
async function loadApps() { const b = await metaGet('apps'); return b ? decJson(b) : []; }
async function saveApps(list) { await metaSet('apps', await encJson(list)); }
async function renderApps() {
  const list = await loadApps(); const g = $('appsGrid'); g.replaceChildren();
  if (!list.length) { g.append(el('div', { class: 'empty', style: 'grid-column:1/-1' }, 'עדיין אין אפליקציות. הוסף את הראשונה.')); return; }
  list.forEach((a, i) => {
    const ic = el('div', { class: 'ic' }, [...a.name][0]?.toUpperCase() || '•');
    ic.style.background = `linear-gradient(135deg,hsl(${hue(a.name)} 70% 45%),hsl(${(hue(a.name) + 40) % 360} 70% 28%))`;
    const t = el('a', { class: 'tile', href: a.url, rel: 'noopener noreferrer' }, ic, el('div', { class: 'nm' }, a.name));
    let press; t.addEventListener('touchstart', () => { press = setTimeout(() => editApp(i), 600); }, { passive: true });
    t.addEventListener('touchend', () => clearTimeout(press)); t.addEventListener('touchmove', () => clearTimeout(press));
    t.addEventListener('contextmenu', (e) => { e.preventDefault(); editApp(i); });
    g.append(t);
  });
  g.append(el('div', { class: 'empty tiny', style: 'grid-column:1/-1;padding:12px' }, 'לחיצה ארוכה על כפתור – עריכה או מחיקה'));
}
function validUrl(u) { try { const x = new URL(u); return ['https:', 'http:', 'intent:'].includes(x.protocol) || /^[a-z][a-z0-9+.-]*:/.test(u); } catch { return false; } }
function appForm(sh, a, onSave, onDelete) {
  const name = el('input', { type: 'text', maxlength: 30, value: a?.name || '', placeholder: 'שם' });
  const url = el('input', { type: 'url', value: a?.url || '', placeholder: 'https://…', dir: 'ltr' });
  sh.append(el('h3', {}, a ? 'עריכת אפליקציה' : 'הוספת אפליקציה'));
  if (!a) {
    sh.append(el('label', {}, 'בחירה מהירה'));
    const p = el('div', { class: 'presets' });
    PRESETS.forEach(([n, u]) => p.append(el('button', { class: 'small-btn', type: 'button', onclick: () => { name.value = n; url.value = u; } }, n)));
    sh.append(p);
  }
  sh.append(el('label', {}, 'שם'), name, el('label', {}, 'קישור (אתר האפליקציה או קישור שפותח אותה)'), url);
  const save = el('button', { class: 'btn', type: 'button' }, 'שמור');
  save.addEventListener('click', () => {
    if (!name.value.trim()) return toast('הזן שם');
    if (!validUrl(url.value.trim())) return toast('קישור לא תקין');
    onSave({ name: name.value.trim(), url: url.value.trim() });
  });
  const acts = el('div', { class: 'actions' }, save);
  if (onDelete) acts.append(el('button', { class: 'btn', style: 'background:rgba(248,113,113,.2);color:#f87171', type: 'button', onclick: onDelete }, 'מחק'));
  sh.append(acts);
}
$('addAppBtn').addEventListener('click', () => modal((sh) => appForm(sh, null, async (a) => {
  const l = await loadApps(); l.push(a); await saveApps(l); closeModal(); renderApps();
})));
async function editApp(i) {
  const l = await loadApps();
  modal((sh) => appForm(sh, l[i], async (a) => { l[i] = a; await saveApps(l); closeModal(); renderApps(); },
    async () => { l.splice(i, 1); await saveApps(l); closeModal(); renderApps(); }));
}

// ---------------- Files ----------------
let pickerUntil = 0, thumbUrls = [];
const pickerActive = () => Date.now() < pickerUntil;
const allowPicker = (ms = 120000) => { pickerUntil = Date.now() + ms; };
$('fileInput').addEventListener('click', () => allowPicker());
$('fileInput').addEventListener('change', async (e) => {
  pickerUntil = 0;
  const files = [...e.target.files]; e.target.value = '';
  if (!files.length) return;
  let ok = 0, fail = 0;
  for (let i = 0; i < files.length; i++) {
    busy(`מצפין ${i + 1}/${files.length}…`);
    try { await addFile(files[i]); ok++; } catch (err) { fail++; }
  }
  busy(); renderFiles();
  toast(`הוצפנו ${ok} קבצים` + (fail ? ` · ${fail} נכשלו (גדולים מדי?)` : '') + ' · את המקור אפשר למחוק מהגלריה');
});
async function makeThumb(file) {
  if (!file.type.startsWith('image/')) return null;
  const bmp = await createImageBitmap(file);
  const s = Math.min(1, 320 / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas'); c.width = Math.round(bmp.width * s); c.height = Math.round(bmp.height * s);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height); bmp.close?.();
  const blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.8));
  return blob ? blob.arrayBuffer() : null;
}
async function addFile(file) {
  const id = crypto.randomUUID();
  const data = await gcmEnc(DEK, await file.arrayBuffer());
  const th = await makeThumb(file).catch(() => null);
  await itemPut({ id, kind: 'file', added: Date.now(),
    meta: await encJson({ name: file.name, type: file.type || 'application/octet-stream', size: file.size }),
    data, thumb: th ? await gcmEnc(DEK, th) : null });
}
async function renderFiles() {
  thumbUrls.forEach(URL.revokeObjectURL); thumbUrls = [];
  const items = (await itemsAll()).filter((x) => x.kind === 'file').sort((a, b) => b.added - a.added);
  $('filesCount').textContent = `${items.length} פריטים מוצפנים`;
  const g = $('filesGrid'); g.replaceChildren();
  if (!items.length) { g.append(el('div', { class: 'empty', style: 'grid-column:1/-1' }, 'אין עדיין קבצים.')); return; }
  for (const it of items) {
    const m = await decJson(it.meta);
    const th = el('div', { class: 'th' }, m.type.startsWith('video/') ? '🎬' : m.type.startsWith('image/') ? '🖼' : '📄');
    if (it.thumb) {
      const u = URL.createObjectURL(new Blob([await gcmDec(DEK, it.thumb)], { type: 'image/jpeg' })); thumbUrls.push(u);
      th.replaceChildren(el('img', { src: u, alt: '' }));
      if (m.type.startsWith('video/')) th.append(el('span', { class: 'play' }, '▶'));
    }
    g.append(el('button', { class: 'ftile', type: 'button', onclick: () => openFile(it.id) }, th, el('div', { class: 'fn' }, m.name)));
  }
}
let viewUrl = null, viewId = null, viewMeta = null;
async function openFile(id) {
  busy('מפענח…');
  try {
    const it = await itemGet(id); const m = await decJson(it.meta);
    const blob = new Blob([await gcmDec(DEK, it.data)], { type: m.type });
    viewUrl = URL.createObjectURL(blob); viewId = id; viewMeta = m;
    $('vName').textContent = `${m.name} · ${fmtSize(m.size)}`;
    const body = $('vBody'); body.replaceChildren();
    if (m.type.startsWith('image/')) body.append(el('img', { src: viewUrl, alt: '' }));
    else if (m.type.startsWith('video/')) body.append(el('video', { src: viewUrl, controls: '', autoplay: '', playsinline: '' }));
    else if (m.type.startsWith('audio/')) body.append(el('audio', { src: viewUrl, controls: '' }));
    else body.append(el('div', { class: 'other' }, 'אין תצוגה מקדימה לסוג קובץ זה. אפשר להוריד אותו (הוא יישמר לא מוצפן).'));
    $('viewer').hidden = false;
  } catch { toast('לא ניתן לפענח את הקובץ'); }
  busy();
}
function closeViewer() { $('viewer').hidden = true; $('vBody').replaceChildren(); if (viewUrl) URL.revokeObjectURL(viewUrl); viewUrl = viewId = viewMeta = null; }
$('vClose').addEventListener('click', closeViewer);
$('vDownload').addEventListener('click', () => {
  if (!viewUrl) return; allowPicker(15000);
  const a = el('a', { href: viewUrl, download: viewMeta.name }); document.body.append(a); a.click(); a.remove();
  toast('הקובץ ירד לא מוצפן לתיקיית ההורדות');
});
$('vDelete').addEventListener('click', async () => {
  if (!viewId || !confirm('למחוק את הקובץ לצמיתות מהכספת?')) return;
  await itemDel(viewId); closeViewer(); renderFiles(); toast('נמחק');
});

// ---------------- Notes ----------------
async function renderNotes() {
  const notes = (await itemsAll()).filter((x) => x.kind === 'note');
  const list = []; for (const n of notes) list.push({ id: n.id, ...(await decJson(n.meta)) });
  list.sort((a, b) => b.updated - a.updated);
  const box = $('notesList'); box.replaceChildren();
  if (!list.length) { box.append(el('div', { class: 'empty' }, 'אין פתקים עדיין.')); return; }
  for (const n of list) {
    box.append(el('div', { class: 'card note', onclick: () => editNote(n) },
      el('div', { class: 'nt' }, n.title || 'ללא כותרת'),
      el('div', { class: 'dim tiny' }, (n.body || '').slice(0, 120)),
      el('div', { class: 'dim tiny', style: 'margin-top:6px' }, new Date(n.updated).toLocaleString('he-IL'))));
  }
}
function editNote(n) {
  modal((sh) => {
    const title = el('input', { type: 'text', maxlength: 80, value: n?.title || '', placeholder: 'כותרת' });
    const body = el('textarea', { placeholder: 'כתוב כאן…' }); body.value = n?.body || '';
    sh.append(el('h3', {}, n ? 'עריכת פתק' : 'פתק חדש'), title, el('div', { style: 'height:10px' }), body);
    const save = el('button', { class: 'btn', type: 'button' }, 'שמור');
    save.addEventListener('click', async () => {
      const id = n?.id || crypto.randomUUID();
      await itemPut({ id, kind: 'note', added: n?.added || Date.now(), meta: await encJson({ title: title.value, body: body.value, updated: Date.now() }) });
      closeModal(); renderNotes();
    });
    const acts = el('div', { class: 'actions' }, save);
    if (n) acts.append(el('button', { class: 'btn', type: 'button', style: 'background:rgba(248,113,113,.2);color:#f87171',
      onclick: async () => { if (confirm('למחוק את הפתק?')) { await itemDel(n.id); closeModal(); renderNotes(); } } }, 'מחק'));
    sh.append(acts);
  });
}
$('newNoteBtn').addEventListener('click', () => editNote(null));

// ---------------- Settings ----------------
$('changePinBtn').addEventListener('click', () => modal((sh) => {
  const cur = el('input', { type: 'password', inputmode: 'numeric', placeholder: 'קוד נוכחי', dir: 'ltr' });
  const n1 = el('input', { type: 'password', inputmode: 'numeric', placeholder: 'קוד חדש (4–12 ספרות)', dir: 'ltr' });
  const n2 = el('input', { type: 'password', inputmode: 'numeric', placeholder: 'אימות קוד חדש', dir: 'ltr' });
  const save = el('button', { class: 'btn', type: 'button' }, 'שמור');
  save.addEventListener('click', async () => {
    if (!/^\d{4,12}$/.test(n1.value)) return toast('הקוד החדש חייב להיות 4–12 ספרות');
    if (n1.value !== n2.value) return toast('הקודים החדשים לא תואמים');
    busy('בודק…');
    try {
      const k = await metaGet('key'); await gcmDec(await kekFrom(cur.value, k.salt, k.iter), k.box);
      await wrapPin(n1.value); busy(); closeModal(); toast('הקוד עודכן');
    } catch { busy(); toast('הקוד הנוכחי שגוי'); }
  });
  sh.append(el('h3', {}, 'שינוי קוד'), cur, el('div', { style: 'height:8px' }), n1, el('div', { style: 'height:8px' }), n2, save);
}));
$('wipeBtn').addEventListener('click', async () => {
  if (!confirm('למחוק את כל הכספת? הקבצים, הפתקים והקוד יימחקו לצמיתות.')) return;
  if (!confirm('בטוח? אין דרך לשחזר.')) return;
  (await db()).close(); dbp = null;
  await new Promise((r) => { const q = indexedDB.deleteDatabase(DB_NAME); q.onsuccess = q.onerror = q.onblocked = r; });
  ['pv_fails', 'pv_lock_until', 'pv_autolock'].forEach((k) => localStorage.removeItem(k));
  DEK = DEK_RAW = null; location.reload();
});

// ---------------- Start ----------------
async function start() {
  try { await navigator.storage?.persist?.(); } catch {}
  const k = await metaGet('key');
  if (!k) { setupPin = ''; setupFirst = null; renderSetup(); show('setup'); return; }
  lockLen = k.len || 6; lockPin = ''; drawDots('lockDots', lockLen, 0); lockMsg(); show('lock');
}
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
start();
})();
