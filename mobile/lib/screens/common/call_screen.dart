import 'dart:async';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:provider/provider.dart';
import '../../services/calls.dart';
import '../../services/session.dart';

class CallScreen extends StatefulWidget {
  final int loadId;
  final bool incoming;
  const CallScreen({super.key, required this.loadId, required this.incoming});
  @override
  State<CallScreen> createState() => _CallScreenState();
}

class _CallScreenState extends State<CallScreen> {
  late final CallSession _call;
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _call = CallSession(api: context.read<Session>().api, loadId: widget.loadId, incoming: widget.incoming);
    _call.addListener(_changed);
    _tick = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
    _start();
  }

  Future<void> _start() async {
    bool granted = true;
    try { granted = (await Permission.microphone.request()).isGranted; } catch (_) {/* browser asks itself */}
    if (!granted) {
      if (mounted) Navigator.pop(context);
      return;
    }
    if (widget.incoming) { _call.listen(); } else { await _call.startOutgoing(); }
  }

  void _changed() {
    if (_call.state == 'ended' && mounted) {
      Future.delayed(const Duration(milliseconds: 600), () { if (mounted) Navigator.pop(context); });
    }
    setState(() {});
  }

  @override
  void dispose() {
    _tick?.cancel();
    _call.removeListener(_changed);
    if (_call.state != 'ended') _call.hangup();
    super.dispose();
  }

  String get _label => switch (_call.state) {
        'calling' => 'Calling...',
        'ringing' => 'Incoming call',
        'connecting' => 'Connecting...',
        'active' => '${_call.elapsed.inMinutes.toString().padLeft(2, '0')}:${(_call.elapsed.inSeconds % 60).toString().padLeft(2, '0')}',
        _ => 'Call ended',
      };

  Widget _btn(IconData i, Color c, VoidCallback f, {bool on = false}) => Padding(
      padding: const EdgeInsets.all(12),
      child: FloatingActionButton.large(heroTag: null, backgroundColor: on ? Colors.white : c,
          onPressed: f, child: Icon(i, color: on ? Colors.black : Colors.white, size: 34)));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF14204F),
      body: SafeArea(child: Column(children: [
        const Spacer(),
        const CircleAvatar(radius: 48, child: Icon(Icons.person, size: 54)),
        const SizedBox(height: 16),
        Text('HaulMate call · load #${widget.loadId}', style: const TextStyle(color: Colors.white70)),
        const SizedBox(height: 8),
        Text(_label, style: const TextStyle(color: Colors.white, fontSize: 28, fontWeight: FontWeight.w600)),
        const Spacer(),
        if (_call.state == 'ringing')
          Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            _btn(Icons.call_end, Colors.red, _call.reject),
            _btn(Icons.call, Colors.green, _call.accept),
          ])
        else
          Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            _btn(_call.muted ? Icons.mic_off : Icons.mic, Colors.white24, _call.toggleMute, on: _call.muted),
            _btn(Icons.call_end, Colors.red, () => _call.hangup()),
            _btn(Icons.volume_up, Colors.white24, _call.toggleSpeaker, on: _call.speaker),
          ]),
        const SizedBox(height: 40),
      ])),
    );
  }
}
