import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:provider/provider.dart';
import '../../services/location.dart';
import '../../services/realtime.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';
import '../../widgets/map.dart';
import '../common/load_detail_screen.dart';
import 'truck_form.dart';

class DriverHome extends StatefulWidget {
  const DriverHome({super.key});
  @override
  State<DriverHome> createState() => _DriverHomeState();
}

class _DriverHomeState extends State<DriverHome> {
  bool _online = false;
  double _radius = 100;
  List _loads = [];
  Map? _trip;
  List _trucks = [];
  int _tab = 0;
  List _history = [];
  Timer? _timer;
  void Function()? _alerts;
  final _map = MapController();

  @override
  void initState() {
    super.initState();
    _init();
    DriverLocation.instance.addListener(_onLoc);
    _alerts = Realtime.instance.subscribe('/user/queue/alerts', (_) => _refresh());
    _timer = Timer.periodic(const Duration(seconds: 25), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    _alerts?.call();
    DriverLocation.instance.removeListener(_onLoc);
    super.dispose();
  }

  void _onLoc() { if (mounted) setState(() {}); }

  Future<void> _init() async {
    final api = context.read<Session>().api;
    try {
      _trucks = await api.get('/api/trucks');
      final t = await api.get('/api/driver/trip');
      _trip = t is Map && t.isNotEmpty ? t : null;
    } catch (_) {}
    // Stay online automatically if a trip is in progress, so the shipper keeps seeing the truck
    if (_trip != null) await _goOnline();
    if (mounted) setState(() {});
  }

  Future<void> _goOnline() async {
    final err = await DriverLocation.ensurePermission();
    if (err != null) { if (mounted) toast(context, err, error: true); return; }
    final api = context.read<Session>().api;
    final p = await DriverLocation.current();
    final r = await busy(context, () => api.post('/api/driver/online',
        p == null ? null : {'lat': p.latitude, 'lng': p.longitude}));
    if (r == null) return;
    await DriverLocation.instance.start(api);
    setState(() => _online = true);
    if (p != null) _map.move(LatLng(p.latitude, p.longitude), 10);
    _refresh();
  }

  Future<void> _goOffline() async {
    if (_trip != null) return toast(context, 'You have a trip in progress. Finish it before going offline.', error: true);
    await DriverLocation.instance.stop();
    await context.read<Session>().api.post('/api/driver/offline');
    setState(() { _online = false; _loads = []; });
  }

  Future<void> _refresh() async {
    final s = context.read<Session>();
    try {
      final t = await s.api.get('/api/driver/trip');
      _trip = t is Map && t.isNotEmpty ? t : null;
      if (_online) _loads = await s.api.get('/api/driver/loads/available', {'radiusKm': _radius});
      if (_tab == 1) _history = await s.api.get('/api/loads/mine');
      s.refresh();
    } catch (_) {}
    if (mounted) setState(() {});
  }

  void _open(int id) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => LoadDetailScreen(loadId: id)));
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final s = context.watch<Session>();
    final me = DriverLocation.instance.last;
    final truck = _trucks.isNotEmpty ? _trucks.first : null;

    return Scaffold(
      appBar: AppBar(
        title: Text(_online ? 'Online' : 'Offline', style: TextStyle(color: _online ? Colors.green.shade700 : Colors.grey)),
        actions: [
          Center(child: Padding(padding: const EdgeInsets.only(right: 8),
              child: Text('Wallet ${fmtMoney(s.user?['walletBalance'] ?? 0)}', style: const TextStyle(fontWeight: FontWeight.w600)))),
          Switch(value: _online, onChanged: (v) => v ? _goOnline() : _goOffline()),
          PopupMenuButton(itemBuilder: (_) => [
            const PopupMenuItem(value: 'truck', child: Text('Add another truck')),
            const PopupMenuItem(value: 'out', child: Text('Sign out')),
          ], onSelected: (v) async {
            if (v == 'out') { await DriverLocation.instance.stop(); s.logout(); }
            if (v == 'truck') { await Navigator.push(context, MaterialPageRoute(builder: (_) => const TruckForm())); _init(); }
          }),
        ],
      ),
      body: _tab == 1
          ? RefreshIndicator(onRefresh: _refresh,
              child: ListView(children: _history.map((l) => LoadCard(load: l, onTap: () => _open(l['id']))).toList()))
          : Column(children: [
              if (truck != null && truck['verified'] != true)
                MaterialBanner(content: const Text('Your truck is still being verified. You can browse loads but not accept them yet.'),
                    actions: [TextButton(onPressed: _init, child: const Text('Refresh'))]),
              if (_trip != null)
                LoadCard(load: _trip!, onTap: () => _open(_trip!['id']),
                    trailing: const Text('Current trip - tap to continue', style: TextStyle(fontWeight: FontWeight.bold, color: Color(0xFF1F3BB3)))),
              SizedBox(
                height: 220,
                child: FlutterMap(mapController: _map,
                    options: MapOptions(initialCenter: me == null ? zimCentre : LatLng(me.latitude, me.longitude),
                        initialZoom: me == null ? 6.5 : 10),
                    children: [
                      osmTiles(),
                      MarkerLayer(markers: [
                        ..._loads.map((a) => pin(LatLng((a['load']['pickupLat'] as num).toDouble(), (a['load']['pickupLng'] as num).toDouble()), Colors.red, Icons.inventory_2, size: 28)),
                        if (me != null) truckMarker(LatLng(me.latitude, me.longitude), color: Colors.green),
                      ]),
                    ]),
              ),
              if (_online)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Row(children: [
                    const Text('Loads within'),
                    Expanded(child: Slider(value: _radius, min: 10, max: 1000, divisions: 20,
                        label: '${_radius.round()} km', onChanged: (v) => setState(() => _radius = v), onChangeEnd: (_) => _refresh())),
                    Text('${_radius.round()} km'),
                  ]),
                ),
              Expanded(
                child: !_online
                    ? Center(child: Padding(padding: const EdgeInsets.all(32), child: Column(mainAxisSize: MainAxisSize.min, children: [
                        const Icon(Icons.power_settings_new, size: 64, color: Colors.grey),
                        const SizedBox(height: 12),
                        Text(truck == null ? 'Add your truck to start' : 'Go online to see loads near ${truck['regNumber']}',
                            textAlign: TextAlign.center),
                        const SizedBox(height: 16),
                        FilledButton(onPressed: truck == null
                            ? () async { await Navigator.push(context, MaterialPageRoute(builder: (_) => const TruckForm())); _init(); }
                            : _goOnline, child: Text(truck == null ? 'Add truck' : 'Go online')),
                      ])))
                    : RefreshIndicator(
                        onRefresh: _refresh,
                        child: _loads.isEmpty
                            ? ListView(children: const [SizedBox(height: 60), Center(child: Text('No open loads nearby yet.\nWe will alert you when one is posted.', textAlign: TextAlign.center))])
                            : ListView(children: _loads.map((a) => LoadCard(
                                load: a['load'],
                                onTap: () => _open(a['load']['id']),
                                extra: '${a['distanceToPickupKm']} km to pickup · ~${a['etaToPickupMinutes']} min'
                                    '${a['alreadyOffered'] == true ? ' · offer sent' : ''}',
                              )).toList()),
                      ),
              ),
            ]),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) { setState(() => _tab = i); _refresh(); },
        destinations: const [
          NavigationDestination(icon: Icon(Icons.search), label: 'Find loads'),
          NavigationDestination(icon: Icon(Icons.history), label: 'My trips'),
        ],
      ),
    );
  }
}
