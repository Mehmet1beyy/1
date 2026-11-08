'use strict';

/* ======================================================================
   Haritalar — apk içi, açık kaynak sıra-sıra navigasyon
   Harita: Leaflet + OpenStreetMap · Rota: OSRM · Arama: Photon/Nominatim
   Sesli yönlendirme: Android TTS köprüsü (yoksa Web Speech) · Türkçe
   ====================================================================== */

const OSRM = 'https://router.project-osrm.org';
const PHOTON = 'https://photon.komoot.io/api/';
const NOMINATIM = 'https://nominatim.openstreetmap.org/search';

let map, tileLayer;
let meMarker, meAccuracy;
let destMarker, routeCasing, routeLine, passedLine;
let lastPos = null;         // {lat,lng,accuracy,heading,speed}
let destination = null;     // {lat,lng,label}
let route = null;           // {coords:[[lat,lng]], cum:[], steps:[], distance, duration}
let navActive = false;
let follow = true;
let offRouteCount = 0;
let announced = {};         // {idx:{pre:bool,now:bool}}
let rerouting = false;

/* ----------------------- yardımcılar ----------------------- */
const $ = (id) => document.getElementById(id);
const rad = (d) => d * Math.PI / 180;

function haversine(a, b) {
    const R = 6371000;
    const dLat = rad(b[0] - a[0]), dLng = rad(b[1] - a[1]);
    const la1 = rad(a[0]), la2 = rad(b[0]);
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dLng / 2) ** 2;
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

function fmtDist(m) {
    if (m == null) return '—';
    if (m < 1000) return Math.round(m / 5) * 5 + ' m';
    return (m / 1000).toFixed(m < 10000 ? 1 : 0).replace('.', ',') + ' km';
}
function fmtDur(s) {
    if (s == null) return '—';
    const min = Math.round(s / 60);
    if (min < 60) return min + ' dk';
    return Math.floor(min / 60) + ' sa ' + (min % 60) + ' dk';
}
function etaClock(s) {
    const d = new Date(Date.now() + s * 1000);
    return d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
}

let toastTimer;
function toast(msg, ms = 2600) {
    const t = $('toast');
    t.textContent = msg; t.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add('hidden'), ms);
}

function speak(text) {
    try { if (window.AndroidTTS && AndroidTTS.speak) { AndroidTTS.speak(text); return; } } catch (e) {}
    try {
        const u = new SpeechSynthesisUtterance(text);
        u.lang = 'tr-TR';
        window.speechSynthesis.cancel();
        window.speechSynthesis.speak(u);
    } catch (e) {}
}
function keepAwake(on) {
    try { if (window.AndroidNav && AndroidNav.keepAwake) AndroidNav.keepAwake(!!on); } catch (e) {}
}

/* ----------------------- harita ----------------------- */
function initMap() {
    map = L.map('map', { zoomControl: false, attributionControl: true })
        .setView([41.0082, 28.9784], 12); // varsayılan: İstanbul
    L.control.zoom({ position: 'bottomleft' }).addTo(map);
    tileLayer = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19, attribution: '© OpenStreetMap katkıda bulunanlar'
    }).addTo(map);

    map.on('dragstart', () => { if (navActive) follow = false; });
}

function meIcon() {
    return L.divIcon({ className: '', html: '<div class="me-dot"></div>', iconSize: [18, 18], iconAnchor: [9, 9] });
}

/* ----------------------- konum ----------------------- */
let firstFix = true;
function startLocation() {
    if (!navigator.geolocation) { toast('Bu cihazda konum servisi yok'); return; }
    navigator.geolocation.watchPosition(onPos, onPosErr, {
        enableHighAccuracy: true, maximumAge: 1000, timeout: 20000
    });
}
function onPos(p) {
    const c = p.coords;
    lastPos = { lat: c.latitude, lng: c.longitude, accuracy: c.accuracy,
        heading: c.heading, speed: c.speed };
    const ll = [lastPos.lat, lastPos.lng];
    if (!meMarker) {
        meMarker = L.marker(ll, { icon: meIcon(), zIndexOffset: 1000 }).addTo(map);
        meAccuracy = L.circle(ll, { radius: lastPos.accuracy || 20, color: '#1a73e8',
            weight: 1, fillColor: '#1a73e8', fillOpacity: .12 }).addTo(map);
    } else {
        meMarker.setLatLng(ll);
        meAccuracy.setLatLng(ll).setRadius(lastPos.accuracy || 20);
    }
    if (firstFix) { firstFix = false; map.setView(ll, 16); }
    if (navActive) {
        if (follow) map.setView(ll, Math.max(map.getZoom(), 17), { animate: true });
        updateNavigation();
    }
}
function onPosErr(e) {
    if (firstFix) toast('Konum alınamadı — GPS/İzin kontrol edin');
}

/* ----------------------- arama / geocode ----------------------- */
async function geocode(q) {
    // 1) Photon
    try {
        let url = PHOTON + '?q=' + encodeURIComponent(q) + '&limit=6';
        if (lastPos) url += '&lat=' + lastPos.lat + '&lon=' + lastPos.lng;
        const r = await fetch(url, { headers: { 'Accept': 'application/json' } });
        if (r.ok) {
            const j = await r.json();
            const list = (j.features || []).map(f => ({
                lat: f.geometry.coordinates[1], lng: f.geometry.coordinates[0],
                label: photonLabel(f.properties)
            }));
            if (list.length) return list;
        }
    } catch (e) {}
    // 2) Nominatim yedeği
    try {
        const url = NOMINATIM + '?format=jsonv2&limit=6&accept-language=tr&q=' + encodeURIComponent(q);
        const r = await fetch(url, { headers: { 'Accept': 'application/json' } });
        if (r.ok) {
            const j = await r.json();
            return (j || []).map(o => ({ lat: +o.lat, lng: +o.lon, label: o.display_name }));
        }
    } catch (e) {}
    return [];
}
function photonLabel(p) {
    const name = p.name || [p.street, p.housenumber].filter(Boolean).join(' ');
    const where = [p.city || p.town || p.village || p.county, p.state, p.country]
        .filter(Boolean).slice(0, 2).join(', ');
    return (name || where) + (name && where ? ' — ' + where : '');
}

let searchTimer;
function onSearchInput() {
    const q = $('searchInput').value.trim();
    clearTimeout(searchTimer);
    if (q.length < 3) { hideResults(); return; }
    searchTimer = setTimeout(() => doSearch(q), 350);
}
async function doSearch(q) {
    const list = await geocode(q);
    renderResults(list);
}
function renderResults(list) {
    const ul = $('results');
    ul.innerHTML = '';
    if (!list.length) { ul.innerHTML = '<li>Sonuç bulunamadı</li>'; ul.classList.remove('hidden'); return; }
    list.forEach(item => {
        const li = document.createElement('li');
        const parts = item.label.split(' — ');
        li.innerHTML = '<strong>' + esc(parts[0]) + '</strong>' +
            (parts[1] ? '<small>' + esc(parts[1]) + '</small>' : '');
        li.onclick = () => chooseDestination(item);
        ul.appendChild(li);
    });
    ul.classList.remove('hidden');
}
function hideResults() { $('results').classList.add('hidden'); }
function esc(s) { return (s || '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

/* ----------------------- hedef & rota ----------------------- */
function chooseDestination(item) {
    destination = item;
    hideResults();
    $('searchInput').blur();
    $('searchInput').value = item.label.split(' — ')[0];
    if (destMarker) destMarker.remove();
    destMarker = L.marker([item.lat, item.lng]).addTo(map);
    toast('Rota hesaplanıyor…');
    buildRoute();
}

async function getRoute(from, to) {
    const url = OSRM + '/route/v1/driving/' +
        from.lng + ',' + from.lat + ';' + to.lng + ',' + to.lat +
        '?overview=full&geometries=geojson&steps=true&annotations=false';
    const r = await fetch(url);
    const j = await r.json();
    if (!j.routes || !j.routes.length) throw new Error('Rota bulunamadı');
    const rt = j.routes[0];
    const coords = rt.geometry.coordinates.map(c => [c[1], c[0]]);
    const cum = [0];
    for (let i = 1; i < coords.length; i++) cum[i] = cum[i - 1] + haversine(coords[i - 1], coords[i]);
    const steps = [];
    rt.legs.forEach(leg => leg.steps.forEach(s => steps.push(s)));
    // her manevraya rota üzerindeki kümülatif mesafeyi eşle
    steps.forEach(s => {
        const loc = [s.maneuver.location[1], s.maneuver.location[0]];
        let bi = 0, bd = Infinity;
        for (let i = 0; i < coords.length; i++) {
            const d = haversine(coords[i], loc);
            if (d < bd) { bd = d; bi = i; }
        }
        s._idx = bi; s._cum = cum[bi];
    });
    return { coords, cum, steps, distance: rt.distance, duration: rt.duration };
}

async function buildRoute() {
    if (!destination) return;
    const from = lastPos || { lat: destination.lat, lng: destination.lng };
    if (!lastPos) { toast('Önce konumunuz alınmalı'); return; }
    try {
        route = await getRoute(from, destination);
        drawRoute();
        showSummary();
        map.fitBounds(routeLine.getBounds(), { padding: [50, 50] });
    } catch (e) {
        toast('Rota alınamadı: ' + (e.message || 'bağlantı hatası'));
    }
}

function drawRoute() {
    [routeCasing, routeLine, passedLine].forEach(l => l && l.remove());
    routeCasing = L.polyline(route.coords, { color: '#174ea6', weight: 10, opacity: .9 }).addTo(map);
    routeLine = L.polyline(route.coords, { color: '#1a73e8', weight: 6, opacity: 1 }).addTo(map);
    passedLine = L.polyline([], { color: '#9aa0a6', weight: 6, opacity: .9 }).addTo(map);
}

function showSummary() {
    $('bottomSheet').classList.remove('hidden');
    $('tripEta').textContent = etaClock(route.duration);
    $('tripDist').textContent = fmtDist(route.distance);
    $('tripDest').textContent = destination.label.split(' — ')[0];
    $('startBtn').classList.remove('hidden');
    $('stopBtn').classList.add('hidden');
}

/* ----------------------- navigasyon ----------------------- */
// rota üzerinde en yakın nokta + oraya kadar kümülatif mesafe
function projectOnRoute(pos) {
    const p = [pos.lat, pos.lng];
    const c = route.coords, cum = route.cum;
    let best = { dist: Infinity, cum: 0 };
    for (let i = 0; i < c.length - 1; i++) {
        const a = c[i], b = c[i + 1];
        const res = nearestOnSeg(p, a, b);
        if (res.dist < best.dist) {
            best = { dist: res.dist, cum: cum[i] + res.t * (cum[i + 1] - cum[i]) };
        }
    }
    return best;
}
// basit düzlemsel projeksiyon (kısa mesafede yeterli)
function nearestOnSeg(p, a, b) {
    const latRef = rad(p[0]);
    const mPerDegLat = 111320, mPerDegLng = 111320 * Math.cos(latRef);
    const ax = (a[1]) * mPerDegLng, ay = (a[0]) * mPerDegLat;
    const bx = (b[1]) * mPerDegLng, by = (b[0]) * mPerDegLat;
    const px = (p[1]) * mPerDegLng, py = (p[0]) * mPerDegLat;
    const dx = bx - ax, dy = by - ay;
    const len2 = dx * dx + dy * dy || 1e-9;
    let t = ((px - ax) * dx + (py - ay) * dy) / len2;
    t = Math.max(0, Math.min(1, t));
    const cx = ax + t * dx, cy = ay + t * dy;
    const dist = Math.hypot(px - cx, py - cy);
    return { t, dist };
}

function startNav() {
    if (!route) { toast('Önce bir hedef seçin'); return; }
    navActive = true; follow = true; offRouteCount = 0; announced = {};
    $('navBanner').classList.remove('hidden');
    $('startBtn').classList.add('hidden');
    $('stopBtn').classList.remove('hidden');
    $('searchBar').classList.add('hidden');
    keepAwake(true);
    speak('Navigasyon başladı. ' + fmtDist(route.distance) + ' mesafe.');
    if (lastPos) { map.setView([lastPos.lat, lastPos.lng], 17); updateNavigation(); }
}

function stopNav(reason) {
    navActive = false;
    $('navBanner').classList.add('hidden');
    $('stopBtn').classList.add('hidden');
    $('searchBar').classList.remove('hidden');
    keepAwake(false);
    if (route) { $('startBtn').classList.remove('hidden'); }
    if (reason === 'arrived') { toast('Varış noktasına ulaştınız'); }
}

function clearTrip() {
    stopNav();
    destination = null; route = null;
    [destMarker, routeCasing, routeLine, passedLine].forEach(l => l && l.remove());
    destMarker = routeCasing = routeLine = passedLine = null;
    $('bottomSheet').classList.add('hidden');
    $('searchInput').value = '';
}

async function reroute() {
    if (rerouting || !lastPos || !destination) return;
    rerouting = true;
    toast('Rota yeniden hesaplanıyor…');
    speak('Rota yeniden hesaplanıyor');
    try {
        route = await getRoute(lastPos, destination);
        drawRoute(); showSummary();
        announced = {}; offRouteCount = 0;
        $('startBtn').classList.add('hidden'); $('stopBtn').classList.remove('hidden');
    } catch (e) { toast('Yeni rota alınamadı'); }
    rerouting = false;
}

function maneuverIcon(step) {
    const t = step.maneuver.type, m = step.maneuver.modifier || '';
    if (t === 'arrive') return '🏁';
    if (t === 'roundabout' || t === 'rotary' || t === 'roundabout turn') return '↻';
    const map2 = {
        'left': '↰', 'right': '↱', 'slight left': '↖', 'slight right': '↗',
        'sharp left': '↙', 'sharp right': '↘', 'uturn': '⤺', 'straight': '↑'
    };
    return map2[m] || '↑';
}

function instruction(step) {
    const t = step.maneuver.type, m = step.maneuver.modifier || '', name = step.name || '';
    const road = name ? ' ' + name : '';
    const dir = {
        'left': 'sola', 'right': 'sağa', 'slight left': 'hafif sola', 'slight right': 'hafif sağa',
        'sharp left': 'keskin sola', 'sharp right': 'keskin sağa', 'straight': 'düz', 'uturn': 'U dönüşü'
    }[m] || '';
    const cap = s => s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
    switch (t) {
        case 'depart': return 'Yola çıkın' + (road ? ',' + road : '');
        case 'turn': return m === 'uturn' ? 'U dönüşü yapın' : (cap(dir) + ' dönün' + (road ? ',' + road : ''));
        case 'new name': return 'Düz devam edin' + (road ? ',' + road : '');
        case 'continue': return m === 'uturn' ? 'U dönüşü yapın' : ('Düz devam edin' + (road ? ',' + road : ''));
        case 'merge': return 'Trafiğe katılın' + (dir ? ' (' + dir + ')' : '') + (road ? ',' + road : '');
        case 'on ramp': return 'Bağlantı yoluna girin' + (road ? ',' + road : '');
        case 'off ramp': return 'Çıkışa geçin' + (dir ? ' (' + dir + ')' : '');
        case 'fork': return 'Yol ayrımında ' + (dir || 'düz') + ' gidin';
        case 'end of road': return 'Yolun sonunda ' + (dir || 'düz') + ' dönün';
        case 'roundabout':
        case 'rotary': return 'Göbekten ' + (step.maneuver.exit ? step.maneuver.exit + '. çıkıştan' : '') + ' çıkın';
        case 'roundabout turn': return 'Göbekte ' + (dir || 'düz') + ' dönün';
        case 'arrive': return 'Varış noktasına ulaştınız';
        default: return (dir ? cap(dir) + ' ' : '') + 'devam edin';
    }
}

function updateNavigation() {
    if (!route || !lastPos) return;
    const proj = projectOnRoute(lastPos);
    const totalRemaining = Math.max(0, route.cum[route.cum.length - 1] - proj.cum);

    // geçilen kısmı grileştir
    try {
        const idx = route.cum.findIndex(v => v >= proj.cum);
        if (idx > 1) passedLine.setLatLngs(route.coords.slice(0, idx));
    } catch (e) {}

    // varış kontrolü
    if (totalRemaining < 25) {
        $('navDistance').textContent = 'Vardınız';
        $('navInstruction').textContent = destination.label.split(' — ')[0];
        $('maneuverIcon').textContent = '🏁';
        speak('Varış noktasına ulaştınız');
        stopNav('arrived');
        return;
    }

    // rotadan sapma → yeniden hesapla
    if (proj.dist > 55) {
        offRouteCount++;
        if (offRouteCount >= 3) { offRouteCount = 0; reroute(); }
    } else offRouteCount = 0;

    // yaklaşan manevra (depart hariç ilk ileri manevra)
    let up = null;
    for (let i = 1; i < route.steps.length; i++) {
        if (route.steps[i]._cum > proj.cum + 2) { up = { i, step: route.steps[i] }; break; }
    }
    if (!up) { // son adım yaklaşıyor
        $('navDistance').textContent = fmtDist(totalRemaining);
        $('navInstruction').textContent = 'Varış noktasına ilerleyin';
        $('maneuverIcon').textContent = '🏁';
        return;
    }

    const dist = Math.max(0, up.step._cum - proj.cum);
    const text = instruction(up.step);
    $('navDistance').textContent = fmtDist(dist);
    $('navInstruction').textContent = text;
    $('maneuverIcon').textContent = maneuverIcon(up.step);

    // sesli anonslar
    const a = announced[up.i] || (announced[up.i] = { pre: false, now: false });
    if (!a.pre && dist <= 260 && dist > 70) {
        a.pre = true; speak(Math.round(dist / 10) * 10 + ' metre sonra, ' + text);
    }
    if (!a.now && dist <= 55) {
        a.now = true; a.pre = true; speak(text);
    }
}

/* ----------------------- olaylar ----------------------- */
function wire() {
    $('searchInput').addEventListener('input', onSearchInput);
    $('searchInput').addEventListener('keydown', e => {
        if (e.key === 'Enter') { e.preventDefault(); const q = $('searchInput').value.trim(); if (q) doSearch(q); }
    });
    $('searchBtn').onclick = () => { const q = $('searchInput').value.trim(); if (q) doSearch(q); };
    $('recenterBtn').onclick = () => {
        if (!lastPos) { toast('Konum henüz alınmadı'); return; }
        follow = true;
        map.setView([lastPos.lat, lastPos.lng], navActive ? 17 : 16, { animate: true });
    };
    $('startBtn').onclick = startNav;
    $('stopBtn').onclick = () => { stopNav(); toast('Navigasyon durduruldu'); };
    $('clearBtn').onclick = clearTrip;
}

window.addEventListener('DOMContentLoaded', () => {
    initMap();
    wire();
    startLocation();
});
