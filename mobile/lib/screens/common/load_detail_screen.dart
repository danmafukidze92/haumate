import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:image_picker/image_picker.dart';
import 'package:latlong2/latlong.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../config.dart';
import '../../services/realtime.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';
import '../../widgets/map.dart';
import 'call_screen.dart';
import 'chat_screen.dart';
import 'pay_sheet.dart';

/// One screen for a load, for both sides:
///  shipper: search progress, offers, live truck + ETA, chat/call, pay, delivery PIN, rating
///  driver:  accept / counter-offer, trip steps, navigation, proof of delivery, chat/call
class LoadDetailScreen extends StatefulWidget {
  final int loadId;
  const LoadDetailScreen({super.key, required this.loadId});
  @override
  State<LoadDetailScreen> createState() => _LoadDetailScreenState();
}

class _LoadDetailScreenState extends State<LoadDetailScreen> {
  Map? _d;
  List _offers = [];
  LatLng? _truck;
  int? _eta;
  double? _remainingKm;
  String _search = '';
  final List<LatLng> _trail = [];
  final _map = MapController();
  final List<void Function()> _subs = [];

  Map get load => _d!['load'];
  int get id => widget.loadId;

  @override
  void initState() {
    super.initState();
    _reload();
    _subs.add(Realtime.instance.subscribe('/topic/loads/$id/status', (m) {
      if (m['type'] == 'search') {
        final d = m['data'];
        setState(() => _search = '${d['totalAlerted']} drivers alerted ${d['label']}');
      } else {
        _reload();
      }
    }));
    _subs.add(Realtime.instance.subscribe('/topic/loads/$id/location', (m) {
      final p = LatLng((m['lat'] as num).toDouble(), (m['lng'] as num).toDouble());
      setState(() {
        _truck = p;
        _trail.add(p);
        _eta = (m['etaMinutes'] as num?)?.toInt();
        _remainingKm = (m['remainingKm'] as num?)?.toDouble();
      });
    }));
  }

  @override
  void dispose() {
    for (final u in _subs) { u(); }
    super.dispose();
  }

  Future<void> _reload() async {
    final s = context.read<Session>();
    try {
      final d = await s.api.get('/api/loads/$id');
      List offers = [];
      if (d['load']['shipperId'] == s.id && d['load']['status'] == 'OPEN') offers = await s.api.get('/api/loads/$id/offers');
      if (_trail.isEmpty && d['load']['driverId'] != null) {
        final t = await s.api.get('/api/loads/$id/trail');
        _trail.addAll((t as List).map((p) => LatLng((p['lat'] as num).toDouble(), (p['lng'] as num).toDouble())));
      }
      if (!mounted) return;
      setState(() {
        _d = d;
        _offers = offers.where((o) => o['status'] == 'PENDING').toList();
        final dp = d['driverPosition'];
        if (dp != null) _truck = LatLng((dp['lat'] as num).toDouble(), (dp['lng'] as num).toDouble());
        final st = d['load']['status'];
        _eta ??= (['LOADED', 'IN_TRANSIT'].contains(st) ? d['load']['etaDropoffMinutes'] : d['load']['etaPickupMinutes']) as int?;
        if (_search.isEmpty && st == 'OPEN') {
          _search = '${d['load']['driversAlerted']} drivers alerted within ${d['load']['searchRadiusKm']} km';
        }
      });
    } catch (e) {
      if (mounted) toast(context, e, error: true);
    }
  }

  // ----------------------------------------------------------------- actions
  Future<void> _act(Future Function() f) async {
    final r = await busy(context, f);
    if (r != null) _reload();
  }

  Future<void> _counter() async {
    final c = TextEditingController(text: '${(load['offeredPrice'] as num).round()}');
    final v = await showDialog<double>(context: context, builder: (_) => AlertDialog(
      title: const Text('Your price'),
      content: TextField(controller: c, keyboardType: TextInputType.number, autofocus: true,
          decoration: const InputDecoration(prefixText: '\$ ')),
      actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(context, double.tryParse(c.text)), child: const Text('Send offer'))],
    ));
    if (v == null) return;
    final api = context.read<Session>().api;
    _act(() => api.post('/api/driver/loads/$id/offer', {'amount': v}));
  }

  Future<void> _editPrice() async {
    final c = TextEditingController(text: '${(load['offeredPrice'] as num).round()}');
    final v = await showDialog<double>(context: context, builder: (_) => AlertDialog(
      title: const Text('Change your offer'),
      content: TextField(controller: c, keyboardType: TextInputType.number, decoration: const InputDecoration(prefixText: '\$ ')),
      actions: [FilledButton(onPressed: () => Navigator.pop(context, double.tryParse(c.text)), child: const Text('Update'))],
    ));
    if (v == null) return;
    final api = context.read<Session>().api;
    _act(() => api.put('/api/loads/$id/price', {'offeredPrice': v}));
  }

  Future<void> _cancel() async {
    final c = TextEditingController();
    final ok = await showDialog<bool>(context: context, builder: (_) => AlertDialog(
      title: const Text('Cancel this load?'),
      content: TextField(controller: c, decoration: const InputDecoration(labelText: 'Reason')),
      actions: [TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Keep')),
        FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Cancel load'))],
    ));
    if (ok != true) return;
    final api = context.read<Session>().api;
    _act(() => api.post('/api/loads/$id/cancel', {'reason': c.text}));
  }

  Future<void> _advance(String to) async {
    final api = context.read<Session>().api;
    String? photo, pin;
    if (to == 'LOADED' || to == 'DELIVERED') {
      final img = await ImagePicker().pickImage(source: ImageSource.camera, imageQuality: 70, maxWidth: 1600);
      photo = img?.path;
    }
    if (to == 'DELIVERED') {
      final c = TextEditingController();
      pin = await showDialog<String>(context: context, builder: (_) => AlertDialog(
        title: const Text('Delivery PIN'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          const Text('Ask the person receiving the goods for the 4-digit PIN.'),
          const SizedBox(height: 12),
          TextField(controller: c, keyboardType: TextInputType.number, maxLength: 4, autofocus: true,
              style: const TextStyle(fontSize: 28, letterSpacing: 12), textAlign: TextAlign.center),
        ]),
        actions: [FilledButton(onPressed: () => Navigator.pop(context, c.text), child: const Text('Confirm delivery'))],
      ));
      if (pin == null) return;
    }
    _act(() => api.form('/api/driver/loads/$id/status', {'status': to, if (pin != null) 'pin': pin}, filePath: photo));
  }

  Future<void> _rate() async {
    int stars = 5;
    final c = TextEditingController();
    final ok = await showDialog<bool>(context: context, builder: (_) => StatefulBuilder(builder: (ctx, set) => AlertDialog(
      title: const Text('How did it go?'),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        Row(mainAxisAlignment: MainAxisAlignment.center, children: List.generate(5, (i) => IconButton(
            icon: Icon(i < stars ? Icons.star : Icons.star_border, color: Colors.amber, size: 34),
            onPressed: () => set(() => stars = i + 1)))),
        TextField(controller: c, decoration: const InputDecoration(labelText: 'Comment (optional)')),
      ]),
      actions: [FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Submit'))],
    )));
    if (ok != true) return;
    final api = context.read<Session>().api;
    _act(() => api.post('/api/loads/$id/rate', {'stars': stars, 'comment': c.text}));
  }

  void _navigate() {
    final st = load['status'];
    final toPickup = ['ASSIGNED', 'EN_ROUTE_PICKUP', 'AT_PICKUP'].contains(st);
    final lat = toPickup ? load['pickupLat'] : load['dropoffLat'];
    final lng = toPickup ? load['pickupLng'] : load['dropoffLng'];
    launchUrl(Uri.parse('https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving'),
        mode: LaunchMode.externalApplication);
  }

  // ----------------------------------------------------------------- UI
  @override
  Widget build(BuildContext context) {
    final s = context.watch<Session>();
    if (_d == null) return Scaffold(appBar: AppBar(), body: const Center(child: CircularProgressIndicator()));
    final st = load['status'] as String;
    final isShipper = load['shipperId'] == s.id;
    final isMyTrip = load['driverId'] == s.id;
    final pickup = LatLng((load['pickupLat'] as num).toDouble(), (load['pickupLng'] as num).toDouble());
    final drop = LatLng((load['dropoffLat'] as num).toDouble(), (load['dropoffLng'] as num).toDouble());
    final hasParty = load['driverId'] != null && (isShipper || isMyTrip);

    return Scaffold(
      appBar: AppBar(title: Text(load['ref']), actions: [
        if (hasParty) ...[
          IconButton(icon: const Icon(Icons.chat_bubble_outline), onPressed: () =>
              Navigator.push(context, MaterialPageRoute(builder: (_) => ChatScreen(loadId: id, title: load['ref'])))),
          IconButton(icon: const Icon(Icons.call), onPressed: () =>
              Navigator.push(context, MaterialPageRoute(builder: (_) => CallScreen(loadId: id, incoming: false)))),
        ],
      ]),
      body: RefreshIndicator(
        onRefresh: _reload,
        child: ListView(children: [
          SizedBox(
            height: 280,
            child: FlutterMap(mapController: _map,
                options: MapOptions(initialCameraFit: CameraFit.bounds(
                    bounds: LatLngBounds.fromPoints([pickup, drop, if (_truck != null) _truck!]),
                    padding: const EdgeInsets.all(40))),
                children: [
                  osmTiles(),
                  PolylineLayer(polylines: [
                    Polyline(points: [pickup, drop], strokeWidth: 3, color: Colors.grey),
                    if (_trail.length > 1) Polyline(points: _trail, strokeWidth: 4, color: const Color(0xFF1F3BB3)),
                  ]),
                  MarkerLayer(markers: [
                    pin(pickup, Colors.green, Icons.trip_origin, size: 26),
                    pin(drop, Colors.red, Icons.location_on),
                    if (_truck != null && hasParty) truckMarker(_truck!, color: const Color(0xFFF29F67)),
                  ]),
                ]),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [StatusChip(st), const Spacer(),
                Text(fmtMoney(load['agreedPrice'] ?? load['offeredPrice']), style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold))]),
              if (_eta != null && load['driverId'] != null && ['ASSIGNED', 'EN_ROUTE_PICKUP', 'LOADED', 'IN_TRANSIT'].contains(st))
                Padding(padding: const EdgeInsets.only(top: 8), child: Text(
                    '${['LOADED', 'IN_TRANSIT'].contains(st) ? 'Arrives at drop-off' : 'Arrives at pickup'} in about $_eta min'
                    '${_remainingKm != null ? ' · ${_remainingKm!.toStringAsFixed(1)} km' : ''}',
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: Color(0xFF1F3BB3)))),
              const SizedBox(height: 12),
              _row(Icons.trip_origin, Colors.green, load['pickupAddress']),
              _row(Icons.location_on, Colors.red, load['dropoffAddress']),
              const SizedBox(height: 6),
              Text('${load['cargoType']} · ${load['weightTonnes']} t · ${truckClasses[load['truckClass']]} · ${load['distanceKm']} km'),
              if ((load['cargoDescription'] ?? '').toString().isNotEmpty) Text(load['cargoDescription']),
              Text('Payment: ${load['paymentMethod']}${load['paid'] == true ? ' (paid)' : ''}'),
              const Divider(height: 28),
              if (isShipper) ..._shipperPanel(st) else ..._driverPanel(st, isMyTrip),
            ]),
          ),
        ]),
      ),
    );
  }

  Widget _row(IconData i, Color c, String t) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Icon(i, color: c, size: 18), const SizedBox(width: 8), Expanded(child: Text(t))]));

  List<Widget> _shipperPanel(String st) {
    final api = context.read<Session>().api;
    final driver = _d!['driver'];
    final truck = _d!['truck'];
    return [
      if (st == 'OPEN') ...[
        Row(children: [const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
          const SizedBox(width: 10), Expanded(child: Text(_search.isEmpty ? 'Searching for drivers...' : _search))]),
        const SizedBox(height: 12),
        Text('Offers (${_offers.length})', style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
        if (_offers.isEmpty) const Padding(padding: EdgeInsets.all(8), child: Text('Drivers who accept or counter your price appear here.')),
        ..._offers.map((o) => Card(child: ListTile(
              title: Text('${fmtMoney(o['amount'])}${o['counter'] == true ? '  (counter-offer)' : ''}',
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              subtitle: Text(o['etaMinutes'] != null ? '${o['distanceToPickupKm']} km away · ~${o['etaMinutes']} min to pickup' : 'Driver offer'),
              trailing: FilledButton(style: FilledButton.styleFrom(minimumSize: const Size(90, 40)),
                  onPressed: () => _act(() => api.post('/api/loads/$id/offers/${o['id']}/accept')), child: const Text('Accept')),
            ))),
        const SizedBox(height: 8),
        OutlinedButton(onPressed: _editPrice, child: const Text('Raise or change my offer')),
      ],
      if (driver != null) ...[
        ListTile(contentPadding: EdgeInsets.zero,
            leading: const CircleAvatar(child: Icon(Icons.person)),
            title: Text(driver['name']),
            subtitle: Text('★ ${driver['rating']} · ${driver['trips']} ratings'
                '${truck != null ? '\n${truck['make'] ?? ''} ${truck['model'] ?? ''} · ${truck['regNumber']}' : ''}')),
        if (_d!['deliveryPin'] != null && !['DELIVERED', 'COMPLETED', 'CANCELLED'].contains(st))
          Card(color: Colors.amber.shade50, child: ListTile(
            leading: const Icon(Icons.pin),
            title: Text('Delivery PIN: ${_d!['deliveryPin']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, letterSpacing: 2)),
            subtitle: const Text('Give this only to the person receiving the goods. The driver needs it to finish the trip.'),
          )),
        if (load['paid'] != true && load['paymentMethod'] != 'CASH' && !['CANCELLED'].contains(st))
          Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton.icon(
            icon: const Icon(Icons.lock), label: Text('Pay ${fmtMoney(load['agreedPrice'])} (held until delivery)'),
            onPressed: () async { await showPaySheet(context, load); _reload(); },
          )),
      ],
      if (['DELIVERED'].contains(st)) FilledButton(onPressed: _rate, child: const Text('Rate your driver')),
      if (['OPEN', 'ASSIGNED', 'EN_ROUTE_PICKUP', 'AT_PICKUP'].contains(st))
        TextButton(onPressed: _cancel, child: const Text('Cancel load', style: TextStyle(color: Colors.red))),
    ];
  }

  List<Widget> _driverPanel(String st, bool mine) {
    final api = context.read<Session>().api;
    if (st == 'OPEN') {
      return [
        Text('Shipper offers ${fmtMoney(load['offeredPrice'])} · recommended ${fmtMoney(load['recommendedPrice'])}'),
        if (_d!['shipper'] != null) Text('Shipper: ${_d!['shipper']['name']} ★ ${_d!['shipper']['rating']}'),
        const SizedBox(height: 16),
        FilledButton(onPressed: () => _act(() => api.post('/api/driver/loads/$id/offer', {})),
            child: Text('Take it for ${fmtMoney(load['offeredPrice'])}')),
        const SizedBox(height: 8),
        OutlinedButton(style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(50)),
            onPressed: _counter, child: const Text('Offer my price')),
        const Padding(padding: EdgeInsets.only(top: 8),
            child: Text('The shipper picks a driver. You will be notified straight away.', style: TextStyle(fontSize: 12, color: Colors.grey))),
      ];
    }
    if (!mine) return [const Text('This load has been taken.')];
    final next = <String, String>{
      'ASSIGNED': 'Start driving to pickup', 'EN_ROUTE_PICKUP': "I've arrived at pickup",
      'AT_PICKUP': 'Cargo loaded (take photo)', 'LOADED': 'Start delivery', 'IN_TRANSIT': 'Delivered (PIN + photo)',
    };
    final target = {'ASSIGNED': 'EN_ROUTE_PICKUP', 'EN_ROUTE_PICKUP': 'AT_PICKUP', 'AT_PICKUP': 'LOADED',
      'LOADED': 'IN_TRANSIT', 'IN_TRANSIT': 'DELIVERED'}[st];
    return [
      if (target != null) FilledButton(onPressed: () => _advance(target), child: Text(next[st]!)),
      if (target != null) ...[
        const SizedBox(height: 8),
        OutlinedButton.icon(style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(50)),
            onPressed: _navigate, icon: const Icon(Icons.navigation), label: const Text('Navigate')),
      ],
      if (st == 'DELIVERED') FilledButton(onPressed: _rate, child: const Text('Rate the shipper')),
      if (['ASSIGNED', 'EN_ROUTE_PICKUP', 'AT_PICKUP'].contains(st))
        TextButton(onPressed: _cancel, child: const Text('Cancel trip', style: TextStyle(color: Colors.red))),
    ];
  }
}
