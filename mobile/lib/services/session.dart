import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../api/api.dart';
import '../config.dart' as cfg;
import 'realtime.dart';
import 'push.dart';
import 'discovery.dart';

/// Logged-in user state shared across the app.
class Session extends ChangeNotifier {
  final Api api = Api();
  final _store = const FlutterSecureStorage();
  Map<String, dynamic>? user;
  bool loading = true;

  Session() {
    api.onUnauthorized = logout;
  }

  String get role => user?['role'] ?? '';
  bool get isDriver => role == 'DRIVER' || role == 'FLEET_OWNER';
  String get kyc => user?['kycStatus'] ?? 'NONE';
  int get id => (user?['id'] as num?)?.toInt() ?? 0;

  Future<void> restore() async {
    final saved = await _store.read(key: 'server');
    if (saved != null && saved.isNotEmpty) {
      cfg.apiBase = saved;
      api.setBase(saved);
    }
    await reconnect();
    api.token = await _store.read(key: 'token');
    if (api.token != null) {
      try {
        user = Map<String, dynamic>.from(await api.get('/api/me'));
        _afterLogin();
      } catch (_) {
        api.token = null;
      }
    }
    loading = false;
    notifyListeners();
  }

  Future<void> _setToken(Map res) async {
    api.token = res['token'];
    user = Map<String, dynamic>.from(res['user']);
    await _store.write(key: 'token', value: api.token);
    _afterLogin();
    notifyListeners();
  }

  void _afterLogin() {
    Realtime.instance.connect(api.token!);
    Push.register(api);
  }

  /// Finds a reachable server (saved one, live domain, or a PC on this Wi-Fi) and switches to it.
  Future<bool> reconnect() async {
    final saved = await _store.read(key: 'server');
    final found = await Discovery.resolve([cfg.apiBase, saved, cfg.defaultApiBase]);
    if (found == null) return false;
    if (found != cfg.apiBase) {
      cfg.apiBase = found;
      api.setBase(found);
      await _store.write(key: 'server', value: found);
      notifyListeners();
    }
    return true;
  }

  /// Runs a request; if the server can't be reached, looks for it again (the PC's IP may
  /// have changed) and retries once.
  Future<T> _withRetry<T>(Future<T> Function() f) async {
    try {
      return await f();
    } on ApiError catch (e) {
      if (!e.message.startsWith('No connection') || !await reconnect()) rethrow;
      return await f();
    }
  }

  Future<void> login(String phone, String password) async => _setToken(
      await _withRetry(() => api.post('/api/auth/login', {'phone': phone, 'password': password})));

  Future<void> register(Map<String, dynamic> body) async =>
      _setToken(await _withRetry(() => api.post('/api/auth/register', body)));

  Future<void> refresh() async {
    user = Map<String, dynamic>.from(await api.get('/api/me'));
    notifyListeners();
  }

  String get server => cfg.apiBase;

  /// Saves a new server address, e.g. http://192.168.40.10:8670 or http://localhost:8670 (USB + adb reverse).
  Future<void> setServer(String url) async {
    url = url.trim().replaceAll(RegExp(r'/+$'), '');
    if (url.isEmpty) url = cfg.defaultApiBase;
    if (!url.startsWith('http')) url = 'http://$url';
    cfg.apiBase = url;
    api.setBase(url);
    await _store.write(key: 'server', value: url);
    notifyListeners();
  }

  Future<void> logout() async {
    if (isDriver) {
      try { await api.post('/api/driver/offline'); } catch (_) {}
    }
    Realtime.instance.disconnect();
    api.token = null;
    user = null;
    await _store.delete(key: 'token');
    notifyListeners();
  }
}
