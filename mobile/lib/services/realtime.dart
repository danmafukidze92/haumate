import 'dart:convert';
import 'package:stomp_dart_client/stomp_dart_client.dart';
import '../config.dart';

typedef JsonCallback = void Function(Map<String, dynamic> body);

/// STOMP over WebSocket (/ws). Subscriptions survive reconnects (mobile networks drop often).
class Realtime {
  Realtime._();
  static final Realtime instance = Realtime._();

  StompClient? _client;
  bool connected = false;
  int _seq = 0;
  final Map<int, (String, JsonCallback)> _subs = {};
  final Map<int, void Function({Map<String, String>? unsubscribeHeaders})> _active = {};

  void connect(String token) {
    disconnect();
    _client = StompClient(
      config: StompConfig(
        url: wsUrl,
        stompConnectHeaders: {'Authorization': 'Bearer $token'},
        reconnectDelay: const Duration(seconds: 4),
        heartbeatIncoming: const Duration(seconds: 10),
        heartbeatOutgoing: const Duration(seconds: 10),
        onConnect: (_) {
          connected = true;
          _active.clear();
          _subs.forEach((id, s) => _doSub(id, s.$1, s.$2));
        },
        onDisconnect: (_) => connected = false,
        onWebSocketDone: () => connected = false,
        onWebSocketError: (_) => connected = false,
      ),
    );
    _client!.activate();
  }

  void _doSub(int id, String dest, JsonCallback cb) {
    _active[id] = _client!.subscribe(destination: dest, callback: (f) {
      if (f.body == null) return;
      try {
        final d = jsonDecode(f.body!);
        if (d is Map<String, dynamic>) cb(d);
      } catch (_) {}
    });
  }

  /// Returns a function that cancels the subscription.
  void Function() subscribe(String dest, JsonCallback cb) {
    final id = ++_seq;
    _subs[id] = (dest, cb);
    if (connected && _client != null) _doSub(id, dest, cb);
    return () {
      _subs.remove(id);
      _active.remove(id)?.call();
    };
  }

  bool send(String dest, Map<String, dynamic> body) {
    if (!connected || _client == null) return false;
    _client!.send(destination: dest, body: jsonEncode(body));
    return true;
  }

  void disconnect() {
    _client?.deactivate();
    _client = null;
    connected = false;
    _active.clear();
  }
}
