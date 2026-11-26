import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import '../api/api.dart';
import 'realtime.dart';

/// Driver GPS. Uses the phone's GPS with a foreground-service notification on Android and
/// background location on iOS, so tracking continues with the screen off.
/// Points are buffered while offline (dead zones on long routes) and flushed in batches.
class DriverLocation extends ChangeNotifier {
  DriverLocation._();
  static final DriverLocation instance = DriverLocation._();

  StreamSubscription<Position>? _sub;
  Position? last;
  final List<Map<String, dynamic>> _buffer = [];
  Api? _api;
  Timer? _flush;

  bool get running => _sub != null;

  static Future<String?> ensurePermission({bool background = true}) async {
    if (!await Geolocator.isLocationServiceEnabled()) return 'Turn on location (GPS) on your phone';
    var p = await Geolocator.checkPermission();
    if (p == LocationPermission.denied) p = await Geolocator.requestPermission();
    if (p == LocationPermission.denied || p == LocationPermission.deniedForever) {
      return 'HaulMate needs location access. Allow it in Settings.';
    }
    if (background && p == LocationPermission.whileInUse) {
      // Ask for "Allow all the time" so shippers can track the truck while the driver's screen is off
      p = await Geolocator.requestPermission();
    }
    return null;
  }

  static Future<Position?> current() async {
    if (await ensurePermission(background: false) != null) return null;
    try {
      return await Geolocator.getCurrentPosition(locationSettings: const LocationSettings(accuracy: LocationAccuracy.high));
    } catch (_) {
      return Geolocator.getLastKnownPosition();
    }
  }

  Future<void> start(Api api) async {
    if (running) return;
    _api = api;
    final LocationSettings settings = defaultTargetPlatform == TargetPlatform.android
        ? AndroidSettings(
            accuracy: LocationAccuracy.high,
            distanceFilter: 20,
            intervalDuration: const Duration(seconds: 8),
            foregroundNotificationConfig: const ForegroundNotificationConfig(
              notificationTitle: 'HaulMate is sharing your location',
              notificationText: 'Shippers can see your truck while you are online',
              enableWakeLock: true,
            ),
          )
        : AppleSettings(
            accuracy: LocationAccuracy.high,
            activityType: ActivityType.automotiveNavigation,
            distanceFilter: 20,
            pauseLocationUpdatesAutomatically: false,
            showBackgroundLocationIndicator: true,
            allowBackgroundLocationUpdates: true,
          );
    _sub = Geolocator.getPositionStream(locationSettings: settings).listen(_onPosition);
    _flush = Timer.periodic(const Duration(seconds: 30), (_) => _flushBuffer());
    notifyListeners();
  }

  void _onPosition(Position p) {
    last = p;
    final ping = {
      'lat': p.latitude, 'lng': p.longitude,
      'speedKmh': p.speed >= 0 ? p.speed * 3.6 : null,
      'heading': p.heading >= 0 ? p.heading : null,
      'ts': p.timestamp.millisecondsSinceEpoch,
    };
    if (!Realtime.instance.send('/app/location', ping)) {
      _buffer.add(ping);
      if (_buffer.length > 2000) _buffer.removeAt(0);
    } else if (_buffer.isNotEmpty) {
      _flushBuffer();
    }
    notifyListeners();
  }

  Future<void> _flushBuffer() async {
    if (_buffer.isEmpty || _api == null) return;
    final batch = List<Map<String, dynamic>>.from(_buffer);
    try {
      await _api!.post('/api/driver/location', batch);
      _buffer.removeRange(0, batch.length);
    } catch (_) {/* keep for next attempt */}
  }

  Future<void> stop() async {
    await _flushBuffer();
    await _sub?.cancel();
    _sub = null;
    _flush?.cancel();
    notifyListeners();
  }
}
