'use strict';
// Prueba del guion que desactiva WebRTC (app/src/main/assets/gp-webrtc.js) en un entorno simulado.
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert');

const root = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets');
const src = fs.readFileSync(path.join(root, 'gp-webrtc.js'), 'utf8');

function makeWindow() {
  const w = {};
  w.window = w;
  for (const n of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCSessionDescription', 'RTCIceCandidate', 'RTCDataChannel']) {
    w[n] = function () {};
  }
  w.DOMException = class extends Error {
    constructor(m, n) {
      super(m);
      this.name = n;
    }
  };
  w.navigator = {
    mediaDevices: {
      getUserMedia() {
        return Promise.resolve('flujo');
      },
      getDisplayMedia() {
        return Promise.resolve('pantalla');
      },
      enumerateDevices() {
        return Promise.resolve([{ kind: 'audioinput' }]);
      },
    },
    getUserMedia() {},
  };
  return w;
}

(async () => {
  const w = makeWindow();
  vm.createContext(w);
  vm.runInContext(src, w);
  assert.strictEqual(vm.runInContext('typeof RTCPeerConnection', w), 'undefined', 'RTCPeerConnection debe desaparecer');
  assert.strictEqual(vm.runInContext('typeof webkitRTCPeerConnection', w), 'undefined');
  assert.strictEqual(vm.runInContext('typeof RTCDataChannel', w), 'undefined');
  assert.throws(() => vm.runInContext('new RTCPeerConnection()', w), (e) => e.name === 'TypeError');
  await assert.rejects(w.navigator.mediaDevices.getUserMedia(), (e) => e.name === 'NotAllowedError');
  await assert.rejects(w.navigator.mediaDevices.getDisplayMedia(), (e) => e.name === 'NotAllowedError');
  assert.strictEqual(JSON.stringify(await w.navigator.mediaDevices.enumerateDevices()), '[]');
  assert.strictEqual(w.navigator.getUserMedia, undefined);

  // Sin mediaDevices ni navigator: no debe lanzar errores
  const bare = { DOMException: Error };
  bare.window = bare;
  vm.createContext(bare);
  vm.runInContext(src, bare);

  // Con propiedades no configurables: no debe romper la página
  const stubborn = makeWindow();
  Object.defineProperty(stubborn, 'RTCPeerConnection', { value: function () {}, configurable: false, writable: false });
  vm.createContext(stubborn);
  vm.runInContext(src, stubborn);
  assert.strictEqual(vm.runInContext('typeof RTCDataChannel', stubborn), 'undefined');

  console.log('ok   gp-webrtc.js bloquea WebRTC y no rompe la página');
})().catch((e) => {
  console.error('FAIL', e);
  process.exit(1);
});
