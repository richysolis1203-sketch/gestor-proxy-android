(function () {
  'use strict';
  // Desactiva WebRTC en la página: sin él no hay forma de que la página descubra tu IP real por STUN
  // (WebRTC no pasa por el proxy). Efecto secundario: no funcionan las videollamadas dentro de la página.
  var g = typeof window !== 'undefined' ? window : globalThis;
  var names = [
    'RTCPeerConnection', 'webkitRTCPeerConnection', 'mozRTCPeerConnection', 'RTCSessionDescription',
    'RTCIceCandidate', 'RTCDataChannel', 'RTCRtpSender', 'RTCRtpReceiver', 'RTCRtpTransceiver',
    'RTCDtlsTransport', 'RTCIceTransport', 'RTCSctpTransport', 'RTCCertificate', 'RTCPeerConnectionIceEvent',
    'RTCDataChannelEvent', 'RTCTrackEvent', 'RTCDTMFSender', 'RTCError', 'RTCErrorEvent', 'RTCStatsReport',
    'RTCEncodedAudioFrame', 'RTCEncodedVideoFrame'
  ];
  for (var i = 0; i < names.length; i++) {
    try {
      Object.defineProperty(g, names[i], { value: undefined, writable: true, configurable: true, enumerable: false });
    } catch (e) { /* ignorar */ }
  }
  try {
    var md = g.navigator && g.navigator.mediaDevices;
    if (md) {
      var deny = function () {
        var err;
        try { err = new DOMException('Permission denied', 'NotAllowedError'); } catch (e) { err = new Error('Permission denied'); }
        return Promise.reject(err);
      };
      var methods = { getUserMedia: deny, getDisplayMedia: deny, enumerateDevices: function () { return Promise.resolve([]); } };
      for (var k in methods) {
        if (Object.prototype.hasOwnProperty.call(methods, k)) {
          try { Object.defineProperty(md, k, { value: methods[k], writable: true, configurable: true, enumerable: true }); } catch (e) { /* ignorar */ }
        }
      }
    }
    var nav = g.navigator;
    if (nav) {
      var legacy = ['getUserMedia', 'webkitGetUserMedia', 'mozGetUserMedia'];
      for (var j = 0; j < legacy.length; j++) {
        if (legacy[j] in nav) {
          try { Object.defineProperty(nav, legacy[j], { value: undefined, writable: true, configurable: true }); } catch (e) { /* ignorar */ }
        }
      }
    }
  } catch (e) { /* ignorar */ }
})();
