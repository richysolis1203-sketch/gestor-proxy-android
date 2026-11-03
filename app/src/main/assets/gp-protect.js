function gpProtect(cfg) {
  'use strict';

  const seed = (cfg.seed >>> 0) || 1;

  // Generador pseudoaleatorio determinista (mulberry32): mismo resultado dentro de una sesión,
  // distinto entre sesiones.
  function mulberry32(a) {
    return function () {
      a = (a + 0x6d2b79f5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  function mix(a, b, c) {
    let h = seed ^ 0x9e3779b9;
    const parts = [a, b, c];
    for (let i = 0; i < parts.length; i++) {
      h = Math.imul(h ^ (((parts[i] | 0) + 0x7f4a7c15) | 0), 0x85ebca6b);
      h ^= h >>> 13;
    }
    return h >>> 0;
  }

  // Hace que las funciones sustituidas parezcan nativas al llamar a .toString()
  const nativeToString = Function.prototype.toString;
  const fakeSources = new WeakMap();
  const patchedToString = function toString() {
    const s = fakeSources.get(this);
    return s !== undefined ? s : nativeToString.call(this);
  };
  fakeSources.set(patchedToString, nativeToString.call(nativeToString));
  Object.defineProperty(Function.prototype, 'toString', {
    value: patchedToString,
    writable: true,
    configurable: true,
    enumerable: false,
  });

  function replaceMethod(proto, name, makeWrapper) {
    const desc = Object.getOwnPropertyDescriptor(proto, name);
    if (!desc || typeof desc.value !== 'function') return;
    const original = desc.value;
    const wrapped = makeWrapper(original);
    Object.defineProperty(wrapped, 'name', { value: name, configurable: true });
    Object.defineProperty(wrapped, 'length', { value: original.length, configurable: true });
    fakeSources.set(wrapped, nativeToString.call(original));
    Object.defineProperty(proto, name, {
      value: wrapped,
      writable: desc.writable,
      configurable: true,
      enumerable: desc.enumerable,
    });
  }

  function replaceGetter(proto, prop, valueFn, force) {
    const desc = Object.getOwnPropertyDescriptor(proto, prop);
    if (!desc && !force) return;
    const getter = function () {
      return valueFn();
    };
    Object.defineProperty(getter, 'name', { value: 'get ' + prop, configurable: true });
    fakeSources.set(
      getter,
      desc && desc.get ? nativeToString.call(desc.get) : 'function get ' + prop + '() { [native code] }',
    );
    Object.defineProperty(proto, prop, {
      get: getter,
      set: undefined,
      enumerable: desc ? desc.enumerable : true,
      configurable: true,
    });
  }

  // ---- Navigator: valores genéricos y señal «Global Privacy Control» ----
  try {
    if (cfg.hardware) {
      replaceGetter(Navigator.prototype, 'hardwareConcurrency', function () {
        return cfg.hardwareConcurrency;
      });
      replaceGetter(Navigator.prototype, 'deviceMemory', function () {
        return cfg.deviceMemory;
      });
      if (Array.isArray(cfg.languages) && cfg.languages.length) {
        const langs = Object.freeze(cfg.languages.slice());
        replaceGetter(Navigator.prototype, 'languages', function () {
          return langs;
        });
        replaceGetter(Navigator.prototype, 'language', function () {
          return langs[0];
        });
      }
      if (window.NavigatorUAData) {
        replaceMethod(NavigatorUAData.prototype, 'getHighEntropyValues', function (orig) {
          return function () {
            return orig.apply(this, arguments).then(function (v) {
              return { brands: v.brands, mobile: v.mobile, platform: v.platform };
            });
          };
        });
      }
    }
    if (cfg.gpc) {
      replaceGetter(
        Navigator.prototype,
        'globalPrivacyControl',
        function () {
          return true;
        },
        true,
      );
    }
  } catch (e) {
    /* ignorar */
  }

  // ---- Canvas: ruido mínimo (±1 en un bit de unos pocos píxeles), estable dentro de la sesión ----
  if (cfg.canvas) {
    try {
      const createEl = Document.prototype.createElement;
      const origGetContext = HTMLCanvasElement.prototype.getContext;
      const origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
      const origPutImageData = CanvasRenderingContext2D.prototype.putImageData;
      const origDrawImage = CanvasRenderingContext2D.prototype.drawImage;

      const addNoise = function (data, w, h, salt) {
        const n = w * h;
        if (n < 256) return;
        const rnd = mulberry32(mix(w, h, salt));
        const count = Math.min(4096, Math.max(16, Math.floor(n / 64)));
        for (let i = 0; i < count; i++) {
          const o = Math.floor(rnd() * n) * 4;
          if (data[o + 3] === 0) continue; // no tocar píxeles totalmente transparentes
          data[o + Math.floor(rnd() * 3)] ^= 1;
        }
      };

      const noisyCopy = function (canvas) {
        const w = canvas.width;
        const h = canvas.height;
        if (!(w > 0 && h > 0) || w * h > 16777216 || w * h < 256) return null;
        const c = createEl.call(document, 'canvas');
        c.width = w;
        c.height = h;
        const ctx = origGetContext.call(c, '2d');
        if (!ctx) return null;
        origDrawImage.call(ctx, canvas, 0, 0);
        const img = origGetImageData.call(ctx, 0, 0, w, h);
        addNoise(img.data, w, h, 0x51);
        origPutImageData.call(ctx, img, 0, 0);
        return c;
      };

      replaceMethod(CanvasRenderingContext2D.prototype, 'getImageData', function (orig) {
        return function () {
          const img = orig.apply(this, arguments);
          try {
            addNoise(img.data, img.width, img.height, (arguments[0] | 0) * 31 + (arguments[1] | 0));
          } catch (e) {
            /* ignorar */
          }
          return img;
        };
      });
      const viaNoisyCopy = function (orig) {
        return function () {
          let target = this;
          try {
            const c = noisyCopy(this);
            if (c) target = c;
          } catch (e) {
            /* lienzo contaminado u otro error: se usa el original */
          }
          return orig.apply(target, arguments);
        };
      };
      replaceMethod(HTMLCanvasElement.prototype, 'toDataURL', viaNoisyCopy);
      replaceMethod(HTMLCanvasElement.prototype, 'toBlob', viaNoisyCopy);
    } catch (e) {
      /* ignorar */
    }
  }

  // ---- WebGL: nombre genérico de la tarjeta gráfica ----
  if (cfg.webgl) {
    try {
      const patchGL = function (ctor) {
        if (!ctor || !ctor.prototype) return;
        replaceMethod(ctor.prototype, 'getParameter', function (orig) {
          return function (p) {
            if (p === 0x9245) return cfg.glVendor;
            if (p === 0x9246) return cfg.glRenderer;
            return orig.apply(this, arguments);
          };
        });
      };
      patchGL(window.WebGLRenderingContext);
      patchGL(window.WebGL2RenderingContext);
    } catch (e) {
      /* ignorar */
    }
  }

  // ---- Audio: ruido inaudible (~1e-7) en los datos que se leen ----
  if (cfg.audio) {
    try {
      const noised = new WeakMap();
      const addAudioNoise = function (arr, channel) {
        if (!arr || !arr.length) return;
        const rnd = mulberry32(mix(arr.length, channel | 0, 0xa1));
        const step = Math.max(1, Math.floor(arr.length / 256));
        for (let i = 0; i < arr.length; i += step) arr[i] += (rnd() - 0.5) * 1e-7;
      };
      if (window.AudioBuffer) {
        replaceMethod(AudioBuffer.prototype, 'getChannelData', function (orig) {
          return function (channel) {
            const arr = orig.apply(this, arguments);
            try {
              let done = noised.get(this);
              if (!done) {
                done = new Set();
                noised.set(this, done);
              }
              if (!done.has(channel)) {
                done.add(channel);
                addAudioNoise(arr, channel);
              }
            } catch (e) {
              /* ignorar */
            }
            return arr;
          };
        });
        replaceMethod(AudioBuffer.prototype, 'copyFromChannel', function (orig) {
          return function (dest, channel) {
            const r = orig.apply(this, arguments);
            try {
              addAudioNoise(dest, channel);
            } catch (e) {
              /* ignorar */
            }
            return r;
          };
        });
      }
      if (window.AnalyserNode) {
        replaceMethod(AnalyserNode.prototype, 'getFloatFrequencyData', function (orig) {
          return function (arr) {
            const r = orig.apply(this, arguments);
            try {
              const rnd = mulberry32(mix(arr.length, 0, 0xb2));
              for (let i = 0; i < arr.length; i += 4) arr[i] += (rnd() - 0.5) * 1e-4;
            } catch (e) {
              /* ignorar */
            }
            return r;
          };
        });
      }
    } catch (e) {
      /* ignorar */
    }
  }
}
