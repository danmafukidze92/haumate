import 'dart:async';
import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:google_mlkit_face_detection/google_mlkit_face_detection.dart';
import 'package:provider/provider.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';

/// Live selfie + on-device liveness challenge (ML Kit):
///   1. look straight -> selfie   2. blink   3. turn your head one way   4. the other way
/// Frames from each step are uploaded; the server re-checks that the same face moved.
class LivenessScreen extends StatefulWidget {
  const LivenessScreen({super.key});
  @override
  State<LivenessScreen> createState() => _LivenessScreenState();
}

class _LivenessScreenState extends State<LivenessScreen> {
  CameraController? _cam;
  final _detector = FaceDetector(options: FaceDetectorOptions(enableClassification: true, performanceMode: FaceDetectorMode.accurate));
  int _step = 0;
  String _hint = 'Starting camera...';
  bool _running = false;
  double? _firstTurn;
  final List<(String, String)> _frames = []; // (path, challenge)
  String? _selfie;

  static const _prompts = ['Look straight at the camera', 'Now blink slowly', 'Turn your head to one side', 'Now turn to the other side'];

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    if (kIsWeb) {
      // Browser testing: ML Kit liveness is phone-only, so take a plain selfie
      final img = await ImagePicker().pickImage(source: ImageSource.camera, preferredCameraDevice: CameraDevice.front, imageQuality: 85);
      if (!mounted) return;
      if (img == null) { Navigator.pop(context, false); return; }
      final api = context.read<Session>().api;
      final ok = await busy(context, () => api.uploadXFile('/api/kyc/documents', img, {'docType': 'SELFIE', 'captureMode': 'LIVE'}));
      if (mounted) Navigator.pop(context, ok != null);
      return;
    }
    final cams = await availableCameras();
    final front = cams.firstWhere((c) => c.lensDirection == CameraLensDirection.front, orElse: () => cams.first);
    _cam = CameraController(front, ResolutionPreset.medium, enableAudio: false);
    await _cam!.initialize();
    if (!mounted) return;
    setState(() => _hint = _prompts[0]);
    _loop();
  }

  Future<void> _loop() async {
    _running = true;
    final deadline = DateTime.now().add(const Duration(seconds: 60));
    while (_running && mounted && _step < 4) {
      if (DateTime.now().isAfter(deadline)) {
        setState(() => _hint = 'Timed out. Make sure your face is well lit and try again.');
        _running = false;
        return;
      }
      try {
        final shot = await _cam!.takePicture();
        final faces = await _detector.processImage(InputImage.fromFilePath(shot.path));
        if (faces.length != 1) {
          setState(() => _hint = faces.isEmpty ? 'Keep your face inside the oval' : 'Only one face, please');
          continue;
        }
        final f = faces.first;
        final yaw = f.headEulerAngleY ?? 0;
        final eyes = ((f.leftEyeOpenProbability ?? 1) + (f.rightEyeOpenProbability ?? 1)) / 2;
        bool passed = false;
        String challenge = '';
        switch (_step) {
          case 0:
            if (yaw.abs() < 10 && eyes > 0.6) { _selfie = shot.path; passed = true; }
            break;
          case 1:
            if (eyes < 0.25) { challenge = 'BLINK'; passed = true; }
            break;
          case 2:
            if (yaw.abs() > 20) { _firstTurn = yaw; challenge = yaw > 0 ? 'TURN_LEFT' : 'TURN_RIGHT'; passed = true; }
            break;
          case 3:
            if (yaw.abs() > 20 && _firstTurn != null && yaw.sign != _firstTurn!.sign) {
              challenge = yaw > 0 ? 'TURN_LEFT' : 'TURN_RIGHT'; passed = true;
            }
            break;
        }
        if (passed) {
          if (_step > 0) _frames.add((shot.path, challenge));
          _step++;
          if (mounted) setState(() => _hint = _step < 4 ? _prompts[_step] : 'Done!');
        }
      } catch (_) {
        await Future.delayed(const Duration(milliseconds: 300));
      }
    }
    if (_step >= 4 && mounted) _upload();
  }

  Future<void> _upload() async {
    final api = context.read<Session>().api;
    final ok = await busy(context, () async {
      await api.upload('/api/kyc/documents', _selfie!, {'docType': 'SELFIE', 'captureMode': 'LIVE'});
      for (final f in _frames) {
        await api.upload('/api/kyc/documents', f.$1, {'docType': 'LIVENESS_FRAME', 'captureMode': 'LIVE', 'livenessChallenge': f.$2});
      }
      return true;
    });
    if (mounted) Navigator.pop(context, ok == true);
  }

  @override
  void dispose() {
    _running = false;
    _cam?.dispose();
    _detector.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(title: const Text('Live selfie'), backgroundColor: Colors.black, foregroundColor: Colors.white),
      body: _cam == null || !_cam!.value.isInitialized
          ? const Center(child: CircularProgressIndicator())
          : Stack(alignment: Alignment.center, children: [
              Center(child: AspectRatio(aspectRatio: 1 / _cam!.value.aspectRatio, child: CameraPreview(_cam!))),
              IgnorePointer(child: Container(
                width: 240, height: 320,
                decoration: BoxDecoration(border: Border.all(color: Colors.white, width: 3),
                    borderRadius: const BorderRadius.all(Radius.elliptical(120, 160))),
              )),
              Positioned(
                bottom: 40, left: 20, right: 20,
                child: Column(children: [
                  LinearProgressIndicator(value: _step / 4, color: Colors.greenAccent, backgroundColor: Colors.white24),
                  const SizedBox(height: 16),
                  Text(_hint, textAlign: TextAlign.center,
                      style: const TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.w600)),
                  if (!_running && _step < 4) ...[
                    const SizedBox(height: 12),
                    FilledButton(onPressed: () { setState(() { _step = 0; _frames.clear(); _hint = _prompts[0]; }); _loop(); },
                        child: const Text('Try again')),
                  ],
                ]),
              ),
            ]),
    );
  }
}
