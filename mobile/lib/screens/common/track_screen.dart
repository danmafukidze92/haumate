import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:provider/provider.dart';
import '../../config.dart';
import '../../services/realtime.dart';
import '../../services/session.dart';
import '../../widgets/map.dart';
import 'load_detail_screen.dart';

/// Live map of every truck currently carrying one of my loads.
/// Positions stream in over WebSocket; a full refresh runs every 30 s as a fallback.
class TrackScreen extends StatefulWidget {
  const TrackScreen({super.key});
  @override
  State<TrackScreen> createState() => _TrackScreenState();
}

class _TrackScreenState extends State<TrackScreen> {
  final _map = MapController();
  final Map<int, Map<String, dynamic>> _loads = {};
  final Map<int, void Function()> _subs = {};
  Timer? _timer;
  bool _loading = true, _fitted = false;
  int? _selected;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 30), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    for (final u in _subs.values) { u(); }
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final list = await context.read<Session>().api.get('/api/loads/tracking') as List;
      final ids = <int>{};
      for (final raw in list) {
        final l = Map<String, dynamic>.from(raw);
        final id = (l['id'] as num).toInt();
        ids.add(id);
        _loads[id] = {...?_loads[id], ...l};
        _subs[id] ??= Realtime.instance.subscribe('/topic/loads/$id/location', (m) {
          if (!mounted || !_loads.containsKey(id)) return;
          setState(() => _loads[id] = {..._loads[id]!, ...m});
        });
      }
      for (final gone in _loads.keys.where((k) => !ids.contains(k)).toList()) {
        _subs.remove(gone)?.call();
        _loads.remove(gone);
      }
      if (!mounted) return;
      setState(() => _loading = false);
      if (!_fitted) { _fitted = true; _fitAll(); }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  LatLng? _truck(Map l) => l['lat'] == null ? null : LatLng((l['lat'] as num).toDouble(), (l['lng'] as num).toDouble());
  LatLng _drop(Map l) => LatLng((l['dropoffLat'] as num).toDouble(), (l['dropoffLng'] as num).toDouble());

  void _fitAll() {
    final pts = <LatLng>[for (final l in _loads.values) ...[if (_truck(l) != null) _truck(l)!, _drop(l)]];
    if (pts.isEmpty) return;
    if (pts.length == 1) { _map.move(pts.first, 12); return; }
    _map.fitCamera(CameraFit.bounds(bounds: LatLngBounds.fromPoints(pts), padding: const EdgeInsets.all(60)));
  }

  void _open(int id) => Navigator.push(context, MaterialPageRoute(builder: (_) => LoadDetailScreen(loadId: id)));

  @override
  Widget build(BuildContext context) {
    final sel = _selected == null ? null : _loads[_selected];
    return Stack(children: [
      FlutterMap(
        mapController: _map,
        options: MapOptions(initialCenter: zimCentre, initialZoom: 6.5, onTap: (_, __) => setState(() => _selected = null)),
        children: [
          osmTiles(),
          PolylineLayer(polylines: [
            for (final l in _loads.values)
              if (_truck(l) != null)
                Polyline(points: [_truck(l)!, _drop(l)], strokeWidth: 2, color: Colors.black26),
          ]),
          MarkerLayer(markers: [
            for (final l in _loads.values) pin(_drop(l), Colors.red, Icons.flag, size: 26),
            for (final e in _loads.entries)
              if (_truck(e.value) != null)
                Marker(point: _truck(e.value)!, width: 44, height: 44,
                    child: GestureDetector(
                      onTap: () => setState(() => _selected = e.key),
                      child: truckMarker(_truck(e.value)!,
                          color: _selected == e.key ? const Color(0xFFF29F67) : const Color(0xFF1F3BB3)).child,
                    )),
          ]),
        ],
      ),
      const MapAttribution(),
      Positioned(top: 12, left: 12, right: 12, child: Card(child: ListTile(
        leading: const Icon(Icons.gps_fixed, color: Colors.green),
        title: Text(_loading ? 'Loading...' : _loads.isEmpty ? 'No trucks on the road for you right now'
            : '${_loads.length} load${_loads.length == 1 ? '' : 's'} on the move'),
        subtitle: const Text('Live positions update as trucks move'),
        trailing: IconButton(icon: const Icon(Icons.center_focus_strong), onPressed: _fitAll),
      ))),
      if (sel != null)
        Positioned(left: 12, right: 12, bottom: 12, child: Card(child: ListTile(
          leading: const Icon(Icons.local_shipping, color: Color(0xFF1F3BB3)),
          title: Text('${sel['ref'] ?? 'Load'} · ${statusLabels[sel['status']] ?? sel['status']}'),
          subtitle: Text([
            if (sel['driverName'] != null) sel['driverName'],
            if (sel['etaMinutes'] != null) 'ETA ${sel['etaMinutes']} min',
            if (sel['remainingKm'] != null) '${sel['remainingKm']} km left',
            if (sel['dropoffAddress'] != null) 'to ${sel['dropoffAddress']}',
          ].join(' · ')),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => _open(_selected!),
        ))),
    ]);
  }
}
