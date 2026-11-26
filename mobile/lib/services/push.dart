import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import '../api/api.dart';

/// Firebase Cloud Messaging. Push is optional: if Firebase isn't configured yet
/// (no google-services.json / GoogleService-Info.plist) the app still works with in-app alerts.
class Push {
  static bool ready = false;
  static void Function(RemoteMessage m)? onForeground;
  static void Function(Map<String, dynamic> data)? onTap;

  static Future<void> init() async {
    try {
      await Firebase.initializeApp();
      ready = true;
      FirebaseMessaging.onMessage.listen((m) => onForeground?.call(m));
      FirebaseMessaging.onMessageOpenedApp.listen((m) => onTap?.call(m.data));
      final initial = await FirebaseMessaging.instance.getInitialMessage();
      if (initial != null) Future.delayed(const Duration(seconds: 2), () => onTap?.call(initial.data));
    } catch (e) {
      debugPrint('Push disabled: $e');
    }
  }

  static Future<void> register(Api api) async {
    if (!ready) return;
    try {
      await FirebaseMessaging.instance.requestPermission();
      final t = await FirebaseMessaging.instance.getToken();
      if (t != null) await api.put('/api/me/fcm-token', {'token': t});
      FirebaseMessaging.instance.onTokenRefresh.listen((t) => api.put('/api/me/fcm-token', {'token': t}));
    } catch (e) {
      debugPrint('FCM token not registered: $e');
    }
  }
}
