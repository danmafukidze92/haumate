import 'dart:async';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../config.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';
import '../common/place_picker.dart';

/// Post a load: route, cargo, recommended price from the server, the shipper's own offer, payment method.
class PostLoadScreen extends StatefulWidget {
  const PostLoadScreen({super.key});
  @override
  State<PostLoadScreen> createState() => _PostLoadScreenState();
}

class _PostLoadScreenState extends State<PostLoadScreen> {
  Place? _from, _to;
  String _cargo = cargoTypes.first;
  final _desc = TextEditingController();
  final _weight = TextEditingController(text: '1');
  String? _class;
  String? _body;
  bool _hazardous = false, _fragile = false, _cold = false, _urgent = false;
  String _pay = 'ECOCASH';
  Map? _quote;
  final _offer = TextEditingController();
  Timer? _debounce;
  DateTime? _when;

  double get _w => double.tryParse(_weight.text) ?? 0;

  void _requote() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 500), () async {
      if (_from == null || _to == null || _w <= 0) return;
      try {
        final q = await context.read<Session>().api.post('/api/pricing/estimate', {
          'pickupLat': _from!.lat, 'pickupLng': _from!.lng, 'dropLat': _to!.lat, 'dropLng': _to!.lng,
          'weightTonnes': _w, 'truckClass': _class, 'hazardous': _hazardous, 'fragile': _fragile,
          'refrigerated': _cold, 'urgent': _urgent,
        });
        if (!mounted) return;
        setState(() {
          _quote = q;
          _class = q['truckClass'];
          _offer.text = '${(q['recommended'] as num).round()}';
        });
      } catch (e) {
        if (mounted) toast(context, e, error: true);
      }
    });
  }

  Future<void> _pick(bool from) async {
    final p = await Navigator.push<Place>(context, MaterialPageRoute(
        builder: (_) => PlacePicker(title: from ? 'Pickup location' : 'Drop-off location')));
    if (p == null) return;
    setState(() => from ? _from = p : _to = p);
    _requote();
  }

  Future<void> _post() async {
    if (_from == null || _to == null || _quote == null) return toast(context, 'Choose pickup and drop-off first', error: true);
    final api = context.read<Session>().api;
    final r = await busy(context, () => api.post('/api/loads', {
          'pickupAddress': _from!.name, 'pickupLat': _from!.lat, 'pickupLng': _from!.lng,
          'dropoffAddress': _to!.name, 'dropoffLat': _to!.lat, 'dropoffLng': _to!.lng,
          'cargoType': _cargo, 'cargoDescription': _desc.text, 'weightTonnes': _w,
          'truckClass': _class, 'bodyType': _body, 'hazardous': _hazardous, 'fragile': _fragile,
          'refrigerated': _cold, 'urgent': _urgent,
          'pickupFrom': (_when ?? DateTime.now()).toUtc().toIso8601String(),
          'offeredPrice': double.tryParse(_offer.text), 'paymentMethod': _pay,
        }));
    if (r != null && mounted) Navigator.pop(context, (r['id'] as num).toInt());
  }

  Widget _placeTile(bool from) {
    final p = from ? _from : _to;
    return ListTile(
      shape: RoundedRectangleBorder(side: BorderSide(color: Colors.grey.shade300), borderRadius: BorderRadius.circular(8)),
      leading: Icon(from ? Icons.trip_origin : Icons.location_on, color: from ? Colors.green : Colors.red),
      title: Text(p?.name ?? (from ? 'Where is the load?' : 'Where is it going?'), maxLines: 2, overflow: TextOverflow.ellipsis),
      onTap: () => _pick(from),
    );
  }

  @override
  Widget build(BuildContext context) {
    final rec = _quote?['recommended'] as num?;
    final offer = double.tryParse(_offer.text) ?? 0;
    return Scaffold(
      appBar: AppBar(title: const Text('Post a load')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        _placeTile(true), const SizedBox(height: 8), _placeTile(false),
        const SizedBox(height: 16),
        DropdownButtonFormField(initialValue: _cargo, decoration: const InputDecoration(labelText: 'What are you moving?'),
            items: cargoTypes.map((c) => DropdownMenuItem(value: c, child: Text(c))).toList(),
            onChanged: (v) => setState(() => _cargo = v!)),
        const SizedBox(height: 12),
        TextField(controller: _desc, maxLines: 2, decoration: const InputDecoration(labelText: 'Details (optional)', hintText: 'e.g. 40 bags of cement, need 2 loaders')),
        const SizedBox(height: 12),
        Row(children: [
          Expanded(child: TextField(controller: _weight, keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(labelText: 'Weight (tonnes)'), onChanged: (_) { _class = null; _requote(); })),
          const SizedBox(width: 12),
          Expanded(child: DropdownButtonFormField<String?>(initialValue: _body, decoration: const InputDecoration(labelText: 'Body'),
              items: [const DropdownMenuItem(value: null, child: Text('Any')),
                ...bodyTypes.entries.map((e) => DropdownMenuItem(value: e.key, child: Text(e.value)))],
              onChanged: (v) => setState(() { _body = v; _cold = v == 'REFRIGERATED'; _requote(); }))),
        ]),
        const SizedBox(height: 12),
        DropdownButtonFormField<String?>(key: ValueKey('class-$_class'), initialValue: _class, decoration: const InputDecoration(labelText: 'Truck size'),
            items: truckClasses.entries.map((e) => DropdownMenuItem(value: e.key, child: Text(e.value))).toList(),
            onChanged: (v) { setState(() => _class = v); _requote(); }),
        Wrap(spacing: 8, children: [
          FilterChip(label: const Text('Fragile'), selected: _fragile, onSelected: (v) { setState(() => _fragile = v); _requote(); }),
          FilterChip(label: const Text('Hazardous'), selected: _hazardous, onSelected: (v) { setState(() => _hazardous = v); _requote(); }),
          FilterChip(label: const Text('Cold chain'), selected: _cold, onSelected: (v) { setState(() => _cold = v); _requote(); }),
          FilterChip(label: const Text('Urgent'), selected: _urgent, onSelected: (v) { setState(() => _urgent = v); _requote(); }),
        ]),
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.schedule),
          title: Text(_when == null ? 'Pickup: as soon as possible' : 'Pickup: ${_when!.toLocal().toString().substring(0, 16)}'),
          trailing: const Icon(Icons.edit_calendar),
          onTap: () async {
            final d = await showDatePicker(context: context, firstDate: DateTime.now(), lastDate: DateTime.now().add(const Duration(days: 60)));
            if (d == null || !mounted) return;
            final t = await showTimePicker(context: context, initialTime: const TimeOfDay(hour: 8, minute: 0));
            setState(() => _when = DateTime(d.year, d.month, d.day, t?.hour ?? 8, t?.minute ?? 0));
          },
        ),
        const Divider(),
        if (_quote != null) ...[
          Text('Recommended price for ${_quote!['distanceKm']} km', style: const TextStyle(color: Colors.grey)),
          Text(fmtMoney(rec), style: const TextStyle(fontSize: 32, fontWeight: FontWeight.bold)),
          Text('Fair range ${fmtMoney(_quote!['low'])} - ${fmtMoney(_quote!['high'])} · about ${((_quote!['durationMinutes'] as num) / 60).toStringAsFixed(1)} h on the road',
              style: const TextStyle(fontSize: 13)),
          const SizedBox(height: 12),
          TextField(controller: _offer, keyboardType: TextInputType.number, onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(labelText: 'Your offer (USD)', prefixText: '\$ ')),
          if (rec != null && offer > 0 && offer < rec * 0.88)
            const Padding(padding: EdgeInsets.only(top: 6),
                child: Text('Below the usual range: fewer drivers will accept. They may send counter-offers.',
                    style: TextStyle(color: Colors.orange, fontSize: 13))),
          const SizedBox(height: 12),
          DropdownButtonFormField(initialValue: _pay, decoration: const InputDecoration(labelText: 'How will you pay?'),
              items: const [
                DropdownMenuItem(value: 'ECOCASH', child: Text('EcoCash (held until delivery)')),
                DropdownMenuItem(value: 'ONEMONEY', child: Text('OneMoney (held until delivery)')),
                DropdownMenuItem(value: 'INNBUCKS', child: Text('InnBucks (held until delivery)')),
                DropdownMenuItem(value: 'PAYNOW_WEB', child: Text('Card / other via Paynow')),
                DropdownMenuItem(value: 'CASH', child: Text('Cash to the driver on delivery')),
              ],
              onChanged: (v) => setState(() => _pay = v!)),
          const SizedBox(height: 20),
          FilledButton(onPressed: _post, child: Text('Find a truck for ${fmtMoney(offer)}')),
        ] else
          const Padding(padding: EdgeInsets.all(20), child: Text('Choose pickup and drop-off to see the recommended price',
              textAlign: TextAlign.center, style: TextStyle(color: Colors.grey))),
      ]),
    );
  }
}
