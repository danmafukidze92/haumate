import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:provider/provider.dart';
import '../../services/location.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';
import '../../widgets/map.dart';
import '../common/load_detail_screen.dart';
import '../common/track_screen.dart';
import 'post_load_screen.dart';

class ShipperHome extends StatefulWidget {
  const ShipperHome({super.key});
  @override
  State<ShipperHome> createState() => _ShipperHomeState();
}

class _ShipperHomeState extends State<ShipperHome> {
  int _tab = 0;
  LatLng? _me;
  List _nearby = [];
  List _loads = [];
  final _map = MapController();
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _locate();
    _refreshLoads();
    _timer = Timer.periodic(const Duration(seconds: 30), (_) { _nearbyTrucks(); _refreshLoads(); });
  }

  @override
  void dispose() { _timer?.cancel(); super.dispose(); }

  Future<void> _locate() async {
    final p = await DriverLocation.current();
    if (p == null || !mounted) return;
    setState(() => _me = LatLng(p.latitude, p.longitude));
    _map.move(_me!, 12);
    _nearbyTrucks();
  }

  Future<void> _nearbyTrucks() async {
    if (_me == null) return;
    try {
      final r = await context.read<Session>().api.get('/api/drivers/nearby',
          {'lat': _me!.latitude, 'lng': _me!.longitude, 'radiusKm': 50});
      if (mounted) setState(() => _nearby = r['drivers']);
    } catch (_) {}
  }

  Future<void> _refreshLoads() async {
    try {
      final r = await context.read<Session>().api.get('/api/loads/mine');
      if (mounted) setState(() => _loads = r);
    } catch (_) {}
  }

  void _open(int id) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => LoadDetailScreen(loadId: id)));
    _refreshLoads();
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watch<Session>();
    final active = _loads.where((l) => !['COMPLETED', 'CANCELLED'].contains(l['status'])).toList();
    return Scaffold(
      appBar: AppBar(title: Text('Hi ${(s.user?['fullName'] ?? '').toString().split(' ').first}'), actions: [
        PopupMenuButton(itemBuilder: (_) => [const PopupMenuItem(value: 'out', child: Text('Sign out'))],
            onSelected: (_) => s.logout()),
      ]),
      body: _tab == 0
          ? Stack(children: [
              FlutterMap(mapController: _map,
                  options: MapOptions(initialCenter: _me ?? zimCentre, initialZoom: _me == null ? 6.5 : 12),
                  children: [
                    osmTiles(),
                    MarkerLayer(markers: [
                      ..._nearby.map((d) => truckMarker(LatLng((d['lat'] as num).toDouble(), (d['lng'] as num).toDouble()))),
                      if (_me != null) pin(_me!, Colors.red, Icons.my_location, size: 28),
                    ]),
                  ]),
              const MapAttribution(),
              Positioned(
                top: 12, left: 12, right: 12,
                child: Card(child: ListTile(
                  leading: const Icon(Icons.local_shipping),
                  title: Text(_nearby.isEmpty ? 'Looking for trucks near you...' : '${_nearby.length} trucks available within 50 km'),
                  subtitle: const Text('Your load will reach drivers nationwide if none are close'),
                )),
              ),
              if (active.isNotEmpty)
                Positioned(left: 0, right: 0, bottom: 90,
                    child: LoadCard(load: active.first, onTap: () => _open(active.first['id']))),
              Positioned(left: 16, right: 16, bottom: 20,
                  child: FilledButton.icon(
                    icon: const Icon(Icons.add),
                    label: const Text('Post a load'),
                    onPressed: () async {
                      final id = await Navigator.push<int>(context, MaterialPageRoute(builder: (_) => const PostLoadScreen()));
                      if (id != null) _open(id);
                    },
                  )),
            ])
          : _tab == 1
          ? const TrackScreen()
          : RefreshIndicator(
              onRefresh: _refreshLoads,
              child: _loads.isEmpty
                  ? ListView(children: const [SizedBox(height: 200), Center(child: Text('No loads yet'))])
                  : ListView(children: _loads.map((l) => LoadCard(load: l, onTap: () => _open(l['id']))).toList()),
            ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.map), label: 'Book'),
          NavigationDestination(icon: Icon(Icons.gps_fixed), label: 'Track'),
          NavigationDestination(icon: Icon(Icons.list_alt), label: 'My loads'),
        ],
      ),
    );
  }
}
