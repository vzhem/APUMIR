#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Песочный помощник (раунд 242). Отдаёт владельцу файл воркера и страницу
проверки /check, которая работает ИЗ ЕГО БРАУЗЕРА: сама песочница к
*.workers.dev доступа не имеет (TLS-сброс), а браузер владельца имеет.

Запуск:  python3 tools/worker/preview_check_server.py 8080
Страницы:  /            - копирование/скачивание p2p_relay_worker.js + ссылка /check
           /raw         - сам файл текстом (для копирования)
           /download    - файл как вложение
           /check       - проверка воркера (health, version, stats, MQTT-хендшейк,
                          комната зеркала) прямо из браузера

Это вспомогательный инструмент песочницы; на приложение он не влияет.
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
WORKER = os.path.join(HERE, "p2p_relay_worker.js")
DEFAULT_BASE = "https://p2p-relay.1985vzhem.workers.dev"

LANDING = """<!doctype html><html lang="ru"><meta charset="utf-8">
<title>APU — файл воркера</title>
<style>
 body{font-family:system-ui,Segoe UI,Roboto,sans-serif;margin:24px;max-width:820px;line-height:1.5}
 a.btn{display:inline-block;padding:10px 16px;margin:6px 8px 6px 0;background:#2b6cd4;color:#fff;
       border-radius:8px;text-decoration:none}
 a.btn2{background:#444}
 code{background:#f2f2f2;padding:2px 6px;border-radius:4px}
</style>
<h1>APU — файл воркера и проверка</h1>
<p><a class="btn" href="/check">Проверить воркер (/check)</a>
   <a class="btn btn2" href="/download">Скачать p2p_relay_worker.js</a>
   <a class="btn btn2" href="/raw">Показать текстом</a></p>
<p>Файл: <code>tools/worker/p2p_relay_worker.js</code>. Копируйте целиком
(<code>/raw</code> → Ctrl+A → Ctrl+C), вставляйте в Cloudflare → Workers → Edit code → Deploy.</p>
<p>После деплоя откройте <a href="/check">/check</a>: страница сама дёрнет воркер из вашего
браузера и покажет, живой ли он и принимает ли MQTT-соединения.</p>
</html>"""

CHECK = """<!doctype html><html lang="ru"><meta charset="utf-8">
<title>APU — проверка воркера</title>
<style>
 body{font-family:system-ui,Segoe UI,Roboto,sans-serif;margin:24px;max-width:900px;line-height:1.5}
 .row{margin:8px 0;padding:8px 12px;border-radius:8px;background:#f4f4f4}
 .ok{background:#e3f7e6;border-left:6px solid #2e9e4f}
 .bad{background:#fdeaea;border-left:6px solid #d33}
 .wait{background:#fff8e1;border-left:6px solid #e0a800}
 pre{white-space:pre-wrap;word-break:break-word;margin:4px 0 0 0;font-size:13px}
 input{width:430px;padding:8px;font-size:14px}
 button{padding:9px 16px;font-size:14px;border:0;border-radius:8px;background:#2b6cd4;color:#fff}
 h2{margin-top:26px}
</style>
<h1>Проверка воркера</h1>
<p>Адрес: <input id="base" value="__BASE__"> <button onclick="run()">Проверить</button></p>
<p>Проверка идёт из вашего браузера — песочница к workers.dev доступа не имеет.</p>
<div id="out"></div>
<script>
const out = document.getElementById('out');
function row(title, text, cls){
  const d = document.createElement('div');
  d.className = 'row ' + (cls||'wait');
  d.innerHTML = '<b>' + title + '</b><pre></pre>';
  d.querySelector('pre').textContent = text;
  out.appendChild(d);
  return d;
}
function set(d, text, cls){ d.className = 'row ' + (cls||'wait'); d.querySelector('pre').textContent = text; }

async function run(){
  out.innerHTML = '';
  const base = document.getElementById('base').value.trim().replace(/\\/+$/,'');
  localStorage.setItem('apuCheckBase', base);

  // 1) health
  let d = row('1. /health — воркер жив?', 'проверяю...');
  try {
    const t0 = Date.now();
    const r = await fetch(base + '/health', {cache:'no-store'});
    const txt = await r.text();
    set(d, 'HTTP ' + r.status + ' за ' + (Date.now()-t0) + ' мс\\n' + txt,
        (r.ok && txt.includes('ok')) ? 'ok' : 'bad');
  } catch(e){ set(d, 'ОШИБКА: ' + e.message + '\\n(нет сети? неверный адрес? воркер не задеплоен?)', 'bad'); }

  // 2) version
  d = row('2. /version — какая версия воркера на сервере?', 'проверяю...');
  try {
    const r = await fetch(base + '/version', {cache:'no-store'});
    const txt = await r.text();
    set(d, 'HTTP ' + r.status + '\\n' + txt, r.ok ? 'ok' : 'bad');
  } catch(e){ set(d, 'ОШИБКА: ' + e.message, 'bad'); }

  // 3) stats
  d = row('3. /stats — счётчики обращений', 'проверяю...');
  try {
    const r = await fetch(base + '/stats', {cache:'no-store'});
    const txt = await r.text();
    set(d, 'HTTP ' + r.status + '\\n' + txt, r.ok ? 'ok' : 'bad');
  } catch(e){ set(d, 'ОШИБКА: ' + e.message, 'bad'); }

  // 4) MQTT over WebSocket: CONNECT -> CONNACK
  d = row('4. Релей сообщений (MQTT): соединение', 'открываю...');
  const wsUrl = base.replace(/^http/, 'ws') + '/mqtt';
  try {
    const res = await mqttHandshake(wsUrl);
    set(d, res.text, res.ok ? 'ok' : 'bad');
  } catch(e){ set(d, 'ОШИБКА: ' + e.message, 'bad'); }

  // 5) комната зеркала
  d = row('5. Комната зеркала на двоих', 'открываю две комнаты...');
  try {
    const room = 'check' + Math.floor(Math.random()*1e9);
    const res = await mirrorRoundTrip(base.replace(/^http/, 'ws') + '/mirror/' + room);
    set(d, res.text, res.ok ? 'ok' : 'bad');
  } catch(e){ set(d, 'ОШИБКА: ' + e.message, 'bad'); }

  row('Готово', 'Если все пять строк зелёные — воркер задеплоен и рабочий.\\n' +
      'Если красная только первая — воркер не задеплоен или адрес другой.', 'wait');
}

function mqttHandshake(url){
  return new Promise((resolve, reject) => {
    let ws;
    try { ws = new WebSocket(url, 'mqtt'); } catch(e){ return reject(e); }
    const to = setTimeout(() => { try{ws.close();}catch(_){}; reject(new Error('таймаут 8 с')); }, 8000);
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => {
      const clientId = 'apu-check-' + Math.random().toString(16).slice(2,8);
      const cid = new TextEncoder().encode(clientId);
      const varHdr = new Uint8Array([0,4,77,81,84,84,4,2,0,60]);
      const payload = new Uint8Array([cid.length >> 8, cid.length & 255, ...cid]);
      const rem = varHdr.length + payload.length;
      const pkt = new Uint8Array(2 + rem);
      pkt[0] = 0x10; pkt[1] = rem; pkt.set(varHdr, 2); pkt.set(payload, 2 + varHdr.length);
      ws.send(pkt);
    };
    ws.onmessage = (ev) => {
      clearTimeout(to);
      const b = new Uint8Array(ev.data);
      if (b[0] === 0x20) {
        resolve({ok:true, text:'соединение принято (CONNACK), ответ: ' +
          Array.from(b.slice(0,4)).map(x=>'0x'+x.toString(16).padStart(2,'0')).join(' ')});
      } else {
        resolve({ok:false, text:'ответ не CONNACK: ' + Array.from(b.slice(0,8)).map(x=>'0x'+x.toString(16)).join(' ')});
      }
      try{ws.close();}catch(_){}
    };
    ws.onerror = () => { clearTimeout(to); reject(new Error('WebSocket не открылся (сеть, фильтр или нет маршрута /mqtt)')); };
    ws.onclose = (e) => { if (e.code !== 1000) { clearTimeout(to); } };
  });
}

function mirrorRoundTrip(wss){
  return new Promise((resolve, reject) => {
    const a = new WebSocket(wss + '?dev=probeA');
    const b = new WebSocket(wss + '?dev=probeB');
    let open = 0, done = false;
    const to = setTimeout(() => finish(false, 'таймаут 8 с: кадр не дошёл'), 8000);
    function finish(ok, text){ if(done) return; done = true; clearTimeout(to);
      try{a.close();}catch(_){}; try{b.close();}catch(_){}; resolve({ok, text}); }
    a.onopen = b.onopen = () => {
      if (++open === 2) a.send('apu-check-' + Date.now());
    };
    b.onmessage = (ev) => finish(true, 'кадр прошёл из первой комнаты во вторую: ' + ev.data);
    a.onerror = b.onerror = () => finish(false, 'WebSocket не открылся (комната зеркала недоступна)');
    a.onclose = b.onclose = () => { if (!done) finish(false, 'соединение закрылось до проверки'); };
  });
}

document.getElementById('base').value = localStorage.getItem('apuCheckBase') || '__BASE__';
run();
</script>
</html>"""


class Handler(BaseHTTPRequestHandler):
    server_version = "apu-preview/1.0"

    def _send(self, body, ctype="text/plain; charset=utf-8", extra=None, code=200):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html"):
            self._send(LANDING, "text/html; charset=utf-8")
        elif path == "/check":
            self._send(CHECK.replace("__BASE__", DEFAULT_BASE), "text/html; charset=utf-8")
        elif path == "/raw":
            with open(WORKER, "rb") as f:
                self._send(f.read(), "text/plain; charset=utf-8")
        elif path == "/download":
            with open(WORKER, "rb") as f:
                data = f.read()
            self._send(data, "application/javascript; charset=utf-8",
                       {"Content-Disposition": 'attachment; filename="p2p_relay_worker.js"'})
        elif path == "/health":
            self._send(json.dumps({"status": "ok", "worker": os.path.getsize(WORKER)}), "application/json")
        else:
            self._send("not found", code=404)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("preview: http://0.0.0.0:%d/ (worker %d B)" % (port, os.path.getsize(WORKER)), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
