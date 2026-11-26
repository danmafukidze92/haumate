import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import '../api/api.dart';
import 'realtime.dart';

/// In-app voice call over WebRTC. Signalling goes through the server (/app/loads/{id}/call ->
/// /user/queue/call), so phone numbers stay private and calls stay on the platform.
class CallSession extends ChangeNotifier {
  final Api api;
  final int loadId;
  final bool incoming;
  String state; // ringing, connecting, active, ended
  RTCPeerConnection? _pc;
  MediaStream? _mic;
  DateTime? _startedAt;
  void Function()? _unsub;
  bool muted = false, speaker = false;
  final List<RTCIceCandidate> _pendingIce = [];
  bool _remoteSet = false;

  CallSession({required this.api, required this.loadId, required this.incoming})
      : state = incoming ? 'ringing' : 'calling';

  Duration get elapsed => _startedAt == null ? Duration.zero : DateTime.now().difference(_startedAt!);

  void _signal(Map<String, dynamic> m) => Realtime.instance.send('/app/loads/$loadId/call', m);

  Future<void> _setup() async {
    final ice = await api.get('/api/calls/ice-servers');
    _pc = await createPeerConnection({'iceServers': ice, 'sdpSemantics': 'unified-plan'});
    _mic = await navigator.mediaDevices.getUserMedia({'audio': true, 'video': false});
    for (final t in _mic!.getAudioTracks()) {
      await _pc!.addTrack(t, _mic!);
    }
    _pc!.onIceCandidate = (c) => _signal({'type': 'ice', 'candidate': c.candidate, 'sdpMid': c.sdpMid, 'sdpMLineIndex': c.sdpMLineIndex});
    _pc!.onConnectionState = (s) {
      if (s == RTCPeerConnectionState.RTCPeerConnectionStateConnected) {
        state = 'active';
        _startedAt ??= DateTime.now();
        notifyListeners();
      } else if (s == RTCPeerConnectionState.RTCPeerConnectionStateFailed) {
        hangup(local: true);
      }
    };
  }

  void listen() {
    _unsub = Realtime.instance.subscribe('/user/queue/call', (m) async {
      if ((m['loadId'] as num?)?.toInt() != loadId) return;
      switch (m['type']) {
        case 'accept':
          state = 'connecting'; notifyListeners();
          await _setup();
          final offer = await _pc!.createOffer({'offerToReceiveAudio': 1});
          await _pc!.setLocalDescription(offer);
          _signal({'type': 'offer', 'sdp': offer.sdp});
          break;
        case 'offer':
          await _pc!.setRemoteDescription(RTCSessionDescription(m['sdp'], 'offer'));
          _remoteSet = true; await _drainIce();
          final answer = await _pc!.createAnswer({'offerToReceiveAudio': 1});
          await _pc!.setLocalDescription(answer);
          _signal({'type': 'answer', 'sdp': answer.sdp});
          break;
        case 'answer':
          await _pc!.setRemoteDescription(RTCSessionDescription(m['sdp'], 'answer'));
          _remoteSet = true; await _drainIce();
          break;
        case 'ice':
          final c = RTCIceCandidate(m['candidate'], m['sdpMid'], (m['sdpMLineIndex'] as num?)?.toInt());
          if (_remoteSet && _pc != null) { await _pc!.addCandidate(c); } else { _pendingIce.add(c); }
          break;
        case 'reject':
        case 'hangup':
          await hangup(local: false);
          break;
      }
    });
  }

  Future<void> _drainIce() async {
    for (final c in _pendingIce) { await _pc!.addCandidate(c); }
    _pendingIce.clear();
  }

  Future<void> startOutgoing() async {
    listen();
    _signal({'type': 'ring'});
    Timer(const Duration(seconds: 45), () { if (state == 'calling') hangup(local: true); });
  }

  Future<void> accept() async {
    state = 'connecting'; notifyListeners();
    await _setup();
    _signal({'type': 'accept'});
  }

  void reject() { _signal({'type': 'reject'}); _end(); }

  Future<void> hangup({bool local = true}) async {
    if (state == 'ended') return;
    if (local) _signal({'type': 'hangup', if (_startedAt != null) 'durationSec': elapsed.inSeconds});
    _end();
  }

  void _end() {
    state = 'ended';
    _unsub?.call();
    _mic?.getTracks().forEach((t) => t.stop());
    _mic?.dispose();
    _pc?.close();
    notifyListeners();
  }

  void toggleMute() {
    muted = !muted;
    _mic?.getAudioTracks().forEach((t) => t.enabled = !muted);
    notifyListeners();
  }

  void toggleSpeaker() {
    speaker = !speaker;
    Helper.setSpeakerphoneOn(speaker);
    notifyListeners();
  }
}
