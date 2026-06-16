#!/usr/bin/env node
import {spawn} from 'node:child_process';
import {mkdir, rm, writeFile} from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..', '..');
const html = path.join(__dirname, 'hero-recording.html');
const frames = path.join('/tmp', 'executespec-hero-frames');
const out = path.join(__dirname, 'hero.webm');
const poster = path.join(__dirname, 'hero-poster.png');
const chromePath = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const port = 9228;
const width = 1280;
const height = 800;
const fps = 12;
const duration = 54;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function getJson(url) {
  return new Promise((resolve, reject) => {
    http
      .get(url, (res) => {
        let body = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => {
          body += chunk;
        });
        res.on('end', () => {
          try {
            resolve(JSON.parse(body));
          } catch (err) {
            reject(err);
          }
        });
      })
      .on('error', reject);
  });
}

async function waitForPageTarget() {
  for (let i = 0; i < 80; i += 1) {
    try {
      const targets = await getJson(`http://127.0.0.1:${port}/json/list`);
      const page = targets.find((target) => target.type === 'page' && target.webSocketDebuggerUrl);
      if (page) return page;
    } catch {
      // Chrome is still starting.
    }
    await sleep(125);
  }
  throw new Error('Chrome did not expose a page DevTools target');
}

async function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, {once: true});
    ws.addEventListener('error', reject, {once: true});
  });
  let id = 0;
  const pending = new Map();
  ws.addEventListener('message', (event) => {
    const msg = JSON.parse(event.data);
    if (!msg.id) return;
    const p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    if (msg.error) p.reject(new Error(msg.error.message));
    else p.resolve(msg.result);
  });
  return {
    send(method, params = {}) {
      const msg = {id: ++id, method, params};
      ws.send(JSON.stringify(msg));
      return new Promise((resolve, reject) => pending.set(msg.id, {resolve, reject}));
    },
    close() {
      ws.close();
    },
  };
}

async function main() {
  await rm(frames, {recursive: true, force: true});
  await mkdir(frames, {recursive: true});

  const chrome = spawn(chromePath, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--window-size=${width},${height}`,
    '--hide-scrollbars',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    `file://${html}`,
  ]);
  chrome.stderr.on('data', () => {});

  try {
    const pageTarget = await waitForPageTarget();
    const client = await cdp(pageTarget.webSocketDebuggerUrl);
    await client.send('Page.enable');
    await client.send('Runtime.enable');
    await client.send('Emulation.setDeviceMetricsOverride', {
      width,
      height,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await client.send('Page.navigate', {url: `file://${html}`});
    await sleep(500);

    const total = fps * duration;
    for (let i = 0; i < total; i += 1) {
      const t = i / fps;
      await client.send('Runtime.evaluate', {
        expression: `window.renderAt(${t.toFixed(4)})`,
        awaitPromise: true,
      });
      const shot = await client.send('Page.captureScreenshot', {
        format: 'png',
        fromSurface: true,
        captureBeyondViewport: false,
      });
      const file = path.join(frames, `frame-${String(i).padStart(4, '0')}.png`);
      await writeFile(file, Buffer.from(shot.data, 'base64'));
      if (i === 0) {
        await writeFile(poster, Buffer.from(shot.data, 'base64'));
      }
      if (i % 60 === 0) {
        process.stdout.write(`captured ${i}/${total}\n`);
      }
    }
    client.close();
  } finally {
    chrome.kill('SIGTERM');
  }

  await new Promise((resolve, reject) => {
    const ffmpeg = spawn('ffmpeg', [
      '-y',
      '-framerate',
      String(fps),
      '-i',
      path.join(frames, 'frame-%04d.png'),
      '-an',
      '-c:v',
      'libvpx-vp9',
      '-b:v',
      '0',
      '-crf',
      '33',
      '-deadline',
      'good',
      '-cpu-used',
      '4',
      '-row-mt',
      '1',
      '-pix_fmt',
      'yuv420p',
      out,
    ]);
    ffmpeg.stdout.pipe(process.stdout);
    ffmpeg.stderr.pipe(process.stderr);
    ffmpeg.on('exit', (code) => {
      if (code) reject(new Error(`ffmpeg exited with ${code}`));
      else resolve();
    });
  });
  console.log(`wrote ${out}`);
  console.log(`wrote ${poster}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
