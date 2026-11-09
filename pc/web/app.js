'use strict';
/* PhoneWheel phone client
 * Sends "steer,throttle,brake,buttonmask" over a WebSocket to server.py,
 * which feeds a vJoy virtual wheel (X = steering, Y = throttle, Z = brake, buttons 1-32).
 */
const $ = (s) => document.querySelector(s);
const IOS = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
            (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

// ------------------------------------------------------------------ settings
const DEFAULTS = {
  range: 90,          // degrees of phone rotation lock-to-lock
  deadzone: 0.5,      // degrees around center
  smoothing: 0.25,    // 0 = raw, 0.9 = very smooth (adds lag)
  linearity: 1.0,     // >1 = less sensitive near center
  invert: false,
  pedalMode: 'absolute',
  pedalDead: 5,       // % ignored at the bottom of the pedal
  pedalSat: 8,        // % at the top that already counts as 100%
  thrCurve: 1.0,
  brkCurve: 1.0,
  haptics: true,
  showNums: false,
};
const SCHEMA = [
  ['range', 'Steering range', 'Phone rotation lock-to-lock (°)', 'range', 30, 360, 5, (v) => v + '°'],
  ['deadzone', 'Center deadzone', 'Degrees ignored at center', 'range', 0, 5, 0.1, (v) => (+v).toFixed(1) + '°'],
  ['smoothing', 'Smoothing', 'Higher = steadier but more lag', 'range', 0, 0.9, 0.05, (v) => Math.round(v * 100) + '%'],
  ['linearity', 'Steering linearity', '>1 softer center, <1 sharper', 'range', 0.5, 2.5, 0.05, (v) => (+v).toFixed(2)],
  ['invert', 'Invert steering', 'Use if steering goes the wrong way', 'check'],
  ['pedalMode', 'Pedal mode', 'Absolute: height = amount. Relative: slide up from where you touch', 'select', ['absolute', 'relative']],
  ['pedalDead', 'Pedal deadzone', 'Bottom % ignored (rest your thumb)', 'range', 0, 30, 1, (v) => v + '%'],
  ['pedalSat', 'Pedal saturation', 'Top % that is already full', 'range', 0, 30, 1, (v) => v + '%'],
  ['thrCurve', 'Throttle curve', '>1 finer control at low throttle', 'range', 0.4, 3, 0.05, (v) => (+v).toFixed(2)],
  ['brkCurve', 'Brake curve', '>1 finer control at low brake', 'range', 0.4, 3, 0.05, (v) => (+v).toFixed(2)],
  ['haptics', 'Vibration', 'Buzz on button press (Android)', 'check'],
  ['showNums', 'Show button numbers', 'Shows vJoy button number on each key', 'check'],
];
function loadCfg() {
  try { return Object.assign({}, DEFAULTS, JSON.parse(localStorage.getItem('pw-cfg') || '{}')); }
  catch (e) { return Object.assign({}, DEFAULTS); }
}
function saveCfg() { try { localStorage.setItem('pw-cfg', JSON.stringify(cfg)); } catch (e) {} }
let cfg = loadCfg();

// ------------------------------------------------------------------ button map (vJoy 1-32)
const BTN = {
  UPSHIFT: 1, DOWNSHIFT: 2,
};
// [vjoy#, label, sublabel, color, page, gridArea(row/col/rowspan/colspan), round?]
const BUTTONS = [
  [3, 'PIT', 'LIMITER', 'c-blue', 'drive', '1/1'],
  [4, 'LIGHTS', '', 'c-white', 'drive', '1/2'],
  [5, 'FLASH', '', 'c-white', 'drive', '1/3'],
  [6, 'WIPER', '', 'c-cyan', 'drive', '1/4'],
  [8, 'TC +', '', 'c-yellow', 'drive', '2/1'],
  [10, 'ABS +', '', 'c-orange', 'drive', '2/2'],
  [12, 'MAP +', 'ENGINE', 'c-blue', 'drive', '2/3'],
  [14, 'BB +', 'BIAS FWD', 'c-purple', 'drive', '2/4'],
  [9, 'TC −', '', 'c-yellow', 'drive', '3/1'],
  [11, 'ABS −', '', 'c-orange', 'drive', '3/2'],
  [13, 'MAP −', 'ENGINE', 'c-blue', 'drive', '3/3'],
  [15, 'BB −', 'BIAS REAR', 'c-purple', 'drive', '3/4'],
  [18, '◀ IND', 'LEFT', 'c-orange', 'drive', '4/1'],
  [16, 'IGN', 'IGNITION', 'c-red', 'drive', '4/2'],
  [17, 'START', 'ENGINE', 'c-green', 'drive', '4/3'],
  [19, 'IND ▶', 'RIGHT', 'c-orange', 'drive', '4/4'],

  [20, 'HAZARD', '', 'c-red', 'mfd', '1/1'],
  [25, '▲', 'MFD UP', 'c-white', 'mfd', '1/2'],
  [30, 'PAGE', 'MFD CYCLE', 'c-cyan', 'mfd', '1/3'],
  [31, 'ESC', 'MENU', 'c-red', 'mfd', '1/4'],
  [27, '◀', 'MFD LEFT', 'c-white', 'mfd', '2/1'],
  [29, 'OK', 'ENTER', 'c-green', 'mfd', '2/2'],
  [28, '▶', 'MFD RIGHT', 'c-white', 'mfd', '2/3'],
  [32, 'HUD', 'CYCLE', 'c-blue', 'mfd', '2/4/4/5'],
  [24, 'CAM', 'CAMERA', 'c-purple', 'mfd', '3/1'],
  [26, '▼', 'MFD DOWN', 'c-white', 'mfd', '3/2'],
  [7, 'RAIN', 'LIGHT', 'c-cyan', 'mfd', '3/3'],
  [21, 'LOOK', 'LEFT', 'c-yellow', 'mfd', '4/1'],
  [23, 'LOOK', 'BACK', 'c-yellow', 'mfd', '4/2/5/4'],
  [22, 'LOOK', 'RIGHT', 'c-yellow', 'mfd', '4/4'],
];

// ------------------------------------------------------------------ state
const st = {
  steer: 0, thr: 0, brk: 0, mask: 0,
  rawDeg: null, centerDeg: null, smoothDeg: 0,
  locked: false, manualSteer: null,
  sensorSeen: false,
};
const held = new Map(); // bit -> Set(pointerId)

// ------------------------------------------------------------------ helpers
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const wrap180 = (d) => ((d + 540) % 360) - 180;
function buzz(ms) { if (cfg.haptics && navigator.vibrate) { try { navigator.vibrate(ms); } catch (e) {} } }
let toastT;
function toast(msg, ms = 2200) {
  const t = $('#toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('show'), ms);
}

// ------------------------------------------------------------------ build buttons
function buildButtons() {
  rebuildButtons();
  bindPress($('#paddleL'), BTN.UPSHIFT, 22);
  bindPress($('#paddleR'), BTN.DOWNSHIFT, 22);
}

function bindPress(el, num, vib) {
  const bit = 1 << (num - 1);
  const down = (e) => {
    e.preventDefault();
    try { el.setPointerCapture(e.pointerId); } catch (_) {}
    let s = held.get(bit); if (!s) held.set(bit, (s = new Set()));
    s.add(e.pointerId);
    st.mask |= bit; el.classList.add('on'); buzz(vib); sendSoon();
  };
  const up = (e) => {
    const s = held.get(bit); if (!s) return;
    s.delete(e.pointerId);
    if (!s.size) { st.mask &= ~bit; el.classList.remove('on'); sendSoon(); }
  };
  el.addEventListener('pointerdown', down);
  el.addEventListener('pointerup', up);
  el.addEventListener('pointercancel', up);
  el.addEventListener('lostpointercapture', up);
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}

// ------------------------------------------------------------------ pedals
function bindPedal(el, key) {
  let active = null; // {id, originY}
  const valueAt = (y) => {
    const r = el.getBoundingClientRect();
    let v;
    if (cfg.pedalMode === 'relative') v = (active.originY - y) / (r.height * 0.6);
    else v = (r.bottom - y) / r.height;
    const lo = cfg.pedalDead / 100, hi = 1 - cfg.pedalSat / 100;
    v = clamp((v - lo) / Math.max(0.05, hi - lo), 0, 1);
    return Math.pow(v, key === 'thr' ? cfg.thrCurve : cfg.brkCurve);
  };
  el.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    try { el.setPointerCapture(e.pointerId); } catch (_) {}
    active = { id: e.pointerId, originY: e.clientY };
    st[key] = valueAt(e.clientY);
    el.classList.add('active'); sendSoon();
  });
  el.addEventListener('pointermove', (e) => {
    if (!active || e.pointerId !== active.id) return;
    // use coalesced events for the freshest position
    const evs = e.getCoalescedEvents ? e.getCoalescedEvents() : [];
    const y = evs.length ? evs[evs.length - 1].clientY : e.clientY;
    st[key] = valueAt(y); sendSoon();
  });
  const end = (e) => {
    if (!active || e.pointerId !== active.id) return;
    active = null; st[key] = 0; el.classList.remove('active'); sendSoon();
  };
  el.addEventListener('pointerup', end);
  el.addEventListener('pointercancel', end);
  el.addEventListener('lostpointercapture', end);
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}

// ------------------------------------------------------------------ steering (tilt)
function screenAngle() {
  if (screen.orientation && typeof screen.orientation.angle === 'number') return screen.orientation.angle;
  return typeof window.orientation === 'number' ? window.orientation : 0;
}
function onMotion(e) {
  const g = e.accelerationIncludingGravity;
  if (!g || g.x == null || g.y == null) return;
  let x = g.x, y = g.y;
  if (IOS) { x = -x; y = -y; }            // iOS reports gravity with the opposite sign
  const a = screenAngle() * Math.PI / 180;
  const sx = x * Math.cos(a) - y * Math.sin(a);   // gravity in screen coords
  const sy = x * Math.sin(a) + y * Math.cos(a);
  if (Math.hypot(sx, sy) < 1.5) return;   // phone lying flat: roll is undefined
  const deg = -Math.atan2(sx, sy) * 180 / Math.PI; // clockwise rotation = positive (right)
  st.rawDeg = deg;
  if (st.centerDeg === null) st.centerDeg = deg;
  st.sensorSeen = true;
  computeSteer();
}
function computeSteer() {
  if (st.locked) { st.steer = 0; sendSoon(); return; }
  if (st.manualSteer !== null) { st.steer = st.manualSteer; sendSoon(); return; }
  if (st.rawDeg === null) return;
  const d = wrap180(st.rawDeg - st.centerDeg) * (cfg.invert ? -1 : 1);
  const k = 1 - cfg.smoothing;
  st.smoothDeg += (d - st.smoothDeg) * k;
  const half = cfg.range / 2;
  let a = Math.abs(st.smoothDeg);
  a = a <= cfg.deadzone ? 0 : (a - cfg.deadzone) / Math.max(1, half - cfg.deadzone);
  a = Math.pow(clamp(a, 0, 1), cfg.linearity);
  st.steer = Math.sign(st.smoothDeg) * a;
  sendSoon();
}
function centerNow() {
  if (st.rawDeg !== null) st.centerDeg = st.rawDeg;
  st.smoothDeg = 0; computeSteer();
  toast('Steering centered'); buzz(30);
}

// manual steering by dragging the yellow bar (no sensor / desktop testing)
function bindSteerBar() {
  const el = $('.steerbar');
  let id = null;
  const set = (x) => {
    const r = el.getBoundingClientRect();
    st.manualSteer = clamp(((x - r.left) / r.width) * 2 - 1, -1, 1);
    computeSteer();
  };
  el.addEventListener('pointerdown', (e) => { id = e.pointerId; el.setPointerCapture(id); set(e.clientX); });
  el.addEventListener('pointermove', (e) => { if (e.pointerId === id) set(e.clientX); });
  const end = (e) => { if (e.pointerId !== id) return; id = null; st.manualSteer = null; st.steer = 0; computeSteer(); };
  el.addEventListener('pointerup', end);
  el.addEventListener('pointercancel', end);
}

// ------------------------------------------------------------------ networking
let ws = null, wsOpen = false, lastSent = '', lastSendT = 0, sendQueued = false, pingMs = null;
function wsUrl() { return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws'; }
function connect() {
  try { ws = new WebSocket(wsUrl()); } catch (e) { setTimeout(connect, 1000); return; }
  setConn('…', '');
  ws.onopen = () => { wsOpen = true; setConn('PC', 'ok'); toast('Connected to PC'); lastSent = ''; send(true); };
  ws.onmessage = (ev) => {
    const d = ev.data;
    if (d.startsWith('q,')) { pingMs = Math.round(performance.now() - parseFloat(d.slice(2))); }
    else if (d.startsWith('hello,')) {
      try {
        const info = JSON.parse(d.slice(6));
        if (info.buttons < 32) toast(`PC vJoy device has only ${info.buttons} buttons — set 32 in Configure vJoy`, 5000);
      } catch (e) {}
    }
  };
  ws.onclose = () => { wsOpen = false; pingMs = null; setConn('no PC', 'bad'); setTimeout(connect, 800); };
  ws.onerror = () => { try { ws.close(); } catch (e) {} };
}
function setConn(text, cls) { $('#connText').textContent = text; $('#connDot').className = cls; }
function send(force) {
  if (!wsOpen || ws.readyState !== 1) return;
  if (ws.bufferedAmount > 512) return; // never queue stale input on a slow link
  const msg = `${st.steer.toFixed(4)},${st.thr.toFixed(4)},${st.brk.toFixed(4)},${st.mask >>> 0}`;
  const now = performance.now();
  if (!force && msg === lastSent && now - lastSendT < 60) return; // keepalive every 60ms
  ws.send(msg); lastSent = msg; lastSendT = now;
}
function sendSoon() {
  // coalesce bursts of events to at most one packet per ~6ms
  if (sendQueued) return;
  const wait = Math.max(0, 6 - (performance.now() - lastSendT));
  sendQueued = true;
  setTimeout(() => { sendQueued = false; send(true); }, wait);
}
setInterval(() => send(false), 20);
setInterval(() => { if (wsOpen) ws.send('p,' + performance.now()); }, 1000);

// ------------------------------------------------------------------ display loop
function render() {
  const pct = (v) => Math.round(v * 100);
  $('#brake .fill').style.height = pct(st.brk) + '%';
  $('#throttle .fill').style.height = pct(st.thr) + '%';
  $('#brakeVal').textContent = pct(st.brk);
  $('#throttleVal').textContent = pct(st.thr);
  $('#steerVal').textContent = (st.steer > 0 ? '+' : '') + pct(st.steer) + '%';
  $('#wheelIcon').style.transform = `rotate(${st.steer * 90}deg)`;
  const bar = $('#steerBar');
  if (st.steer >= 0) { bar.style.left = '50%'; bar.style.width = st.steer * 50 + '%'; }
  else { bar.style.left = (50 + st.steer * 50) + '%'; bar.style.width = -st.steer * 50 + '%'; }
  if (wsOpen) setConn(pingMs !== null ? `${pingMs} ms` : 'PC', 'ok');
  requestAnimationFrame(render);
}

// ------------------------------------------------------------------ settings UI
function buildSettings() {
  const f = $('#settingsForm'); f.innerHTML = '';
  for (const [key, label, hint, type, a, b, step, fmt] of SCHEMA) {
    const row = document.createElement('div'); row.className = 'row';
    const lab = `<label for="s-${key}">${label}<small>${hint}</small></label>`;
    if (type === 'range') {
      row.innerHTML = `${lab}<input id="s-${key}" type="range" min="${a}" max="${b}" step="${step}" value="${cfg[key]}"><output>${fmt(cfg[key])}</output>`;
      const inp = row.querySelector('input'), out = row.querySelector('output');
      inp.addEventListener('input', () => { cfg[key] = parseFloat(inp.value); out.textContent = fmt(cfg[key]); saveCfg(); });
    } else if (type === 'check') {
      row.innerHTML = `${lab}<span></span><input id="s-${key}" type="checkbox" ${cfg[key] ? 'checked' : ''}>`;
      const inp = row.querySelector('input');
      inp.addEventListener('change', () => {
        cfg[key] = inp.checked; saveCfg();
        if (key === 'showNums') rebuildButtons();
      });
    } else {
      row.innerHTML = `${lab}<select id="s-${key}">${a.map((o) => `<option ${o === cfg[key] ? 'selected' : ''}>${o}</option>`).join('')}</select><span></span>`;
      const sel = row.querySelector('select');
      sel.addEventListener('change', () => { cfg[key] = sel.value; saveCfg(); });
    }
    f.appendChild(row);
  }
}
function rebuildButtons() {
  $('#page-drive').innerHTML = ''; $('#page-mfd').innerHTML = '';
  // paddles keep their listeners; only rebuild grid buttons
  for (const [num, label, sub, color, page, area, round] of BUTTONS) {
    const el = document.createElement('div');
    el.className = `wb ${color}` + (round ? ' round' : '');
    el.innerHTML = `${label}${sub ? `<small>${sub}</small>` : ''}` + (cfg.showNums ? `<span class="num">${num}</span>` : '');
    const [r, c, r2, c2] = area.split('/');
    el.style.gridRow = r2 ? `${r} / ${r2}` : r;
    el.style.gridColumn = c2 ? `${c} / ${c2}` : c;
    bindPress(el, num, 14);
    $('#page-' + page).appendChild(el);
  }
}

// ------------------------------------------------------------------ start / system
let wakeLock = null;
async function keepAwake() {
  try { if ('wakeLock' in navigator) wakeLock = await navigator.wakeLock.request('screen'); } catch (e) {}
}
async function start() {
  // iOS 13+: motion needs explicit permission from a tap
  if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') {
    try {
      const r = await DeviceMotionEvent.requestPermission();
      if (r !== 'granted') toast('Motion permission denied — steering disabled', 4000);
    } catch (e) { toast('Could not get motion permission', 4000); }
  }
  window.addEventListener('devicemotion', onMotion);
  try {
    if (!document.fullscreenElement && document.documentElement.requestFullscreen) {
      await document.documentElement.requestFullscreen({ navigationUI: 'hide' });
    }
  } catch (e) {}
  try { if (screen.orientation && screen.orientation.lock) await screen.orientation.lock('landscape'); } catch (e) {}
  keepAwake();
  $('#startOverlay').hidden = true;
  st.centerDeg = null; // auto-center on first reading
  setTimeout(() => {
    if (!st.sensorSeen) toast('No motion sensor data. Use https:// (WiFi) or http://localhost (USB). Drag the yellow bar to test.', 6000);
  }, 1500);
}

function init() {
  buildButtons();
  bindPedal($('#brake'), 'brk');
  bindPedal($('#throttle'), 'thr');
  bindSteerBar();
  buildSettings();

  if (!window.isSecureContext) $('#secureWarn').hidden = false;
  $('#startInfo').textContent = `Server: ${location.host}`;
  $('#btnStart').addEventListener('click', start);
  $('#btnCenter').addEventListener('click', centerNow);
  $('#btnLock').addEventListener('click', (e) => {
    st.locked = !st.locked; e.currentTarget.classList.toggle('on', st.locked);
    computeSteer(); toast(st.locked ? 'Steering locked at center (use while binding pedals/buttons)' : 'Steering unlocked');
  });
  $('#btnSettings').addEventListener('click', () => { $('#settingsOverlay').hidden = false; });
  $('#btnCloseSettings').addEventListener('click', () => { $('#settingsOverlay').hidden = true; });
  $('#btnReset').addEventListener('click', () => { cfg = Object.assign({}, DEFAULTS); saveCfg(); buildSettings(); rebuildButtons(); toast('Defaults restored'); });
  document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x === t));
    document.querySelectorAll('.page').forEach((p) => p.classList.toggle('active', p.id === 'page-' + t.dataset.page));
  }));
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') keepAwake();
    else { st.thr = st.brk = 0; st.mask = 0; held.clear(); document.querySelectorAll('.wb.on, .paddle.on').forEach((e) => e.classList.remove('on')); send(true); }
  });
  document.addEventListener('touchmove', (e) => { if (!e.target.closest('.card')) e.preventDefault(); }, { passive: false });
  document.addEventListener('gesturestart', (e) => e.preventDefault());

  connect();
  requestAnimationFrame(render);
  if ('serviceWorker' in navigator && window.isSecureContext) navigator.serviceWorker.register('sw.js').catch(() => {});
}
init();
