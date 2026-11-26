import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show kIsWeb;

/// Finds a working HaulMate server, so the app never depends on a fixed IP.
/// Order: the last server that worked -> the live domain -> a HaulMate server
/// on this Wi-Fi (answers a UDP broadcast with its current address).
class Discovery {
  static const udpPort = 8671;

  static Future<bool> isHaulmate(String base) async {
    try {
      final r = await Dio(BaseOptions(connectTimeout: const Duration(seconds: 3), receiveTimeout: const Duration(seconds: 3)))
          .get('$base/api/ping');
      return r.data is Map && r.data['app'] == 'haulmate';
    } catch (_) {
      return false;
    }
  }

  /// Asks the local network "is there a HaulMate server?" and returns its address.
  static Future<String?> findOnLan({Duration wait = const Duration(seconds: 3)}) async {
    if (kIsWeb) return null;
    RawDatagramSocket? s;
    try {
      s = await RawDatagramSocket.bind(InternetAddress.anyIPv4, 0);
      s.broadcastEnabled = true;
      final found = Completer<String?>();
      s.listen((e) {
        if (e != RawSocketEvent.read) return;
        final d = s!.receive();
        if (d == null) return;
        final url = utf8.decode(d.data).trim();
        if (url.startsWith('http') && !found.isCompleted) found.complete(url);
      });
      final msg = utf8.encode('HAULMATE?');
      for (var i = 0; i < 3 && !found.isCompleted; i++) {
        s.send(msg, InternetAddress('255.255.255.255'), udpPort);
        await Future.any([found.future, Future.delayed(const Duration(milliseconds: 800))]);
      }
      return await found.future.timeout(wait, onTimeout: () => null);
    } catch (_) {
      return null;
    } finally {
      s?.close();
    }
  }

  /// First server that answers, or null if none can be reached.
  static Future<String?> resolve(List<String?> candidates) async {
    for (final c in candidates) {
      if (c != null && c.isNotEmpty && await isHaulmate(c)) return c;
    }
    final lan = await findOnLan();
    if (lan != null && await isHaulmate(lan)) return lan;
    return null;
  }
}
