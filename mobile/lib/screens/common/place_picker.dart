import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';
import 'package:provider/provider.dart';
import '../../services/location.dart';
import '../../services/session.dart';
import '../../widgets/map.dart';

class Place {
  final String name;
  final double lat, lng;
  Place(this.name, this.lat, this.lng);
}

/// Search an address (OpenStreetMap, Zimbabwe only), use current location, or drag the map to a spot.
class PlacePicker extends StatefulWidget {
  final String title;
  const PlacePicker({super.key, required this.title});
  @override
  State<PlacePicker> createState() => _PlacePickerState();
}

class _PlacePickerState extends State<PlacePicker> {
  final _q = TextEditingController();
  final _map = MapController();
  List<Map<String, dynamic>> _results = [];
  Timer? _debounce;
  bool _mapMode = false;
  LatLng _centre = zimCentre;

  void _search(String q) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 600), () async {
      if (q.trim().length < 3) return;
      try {
        final r = await context.read<Session>().api.searchPlaces(q);
        if (mounted) setState(() => _results = r);
      } catch (_) {}
    });
  }

  Future<void> _useCurrent() async {
    final p = await DriverLocation.current();
    if (p == null || !mounted) return;
    final name = await context.read<Session>().api.reverse(p.latitude, p.longitude);
    if (mounted) Navigator.pop(context, Place(name, p.latitude, p.longitude));
  }

  Future<void> _confirmMap() async {
    final name = await context.read<Session>().api.reverse(_centre.latitude, _centre.longitude);
    if (mounted) Navigator.pop(context, Place(name, _centre.latitude, _centre.longitude));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.title), actions: [
        TextButton.icon(onPressed: () => setState(() => _mapMode = !_mapMode),
            icon: Icon(_mapMode ? Icons.search : Icons.map), label: Text(_mapMode ? 'Search' : 'Map')),
      ]),
      body: _mapMode
          ? Stack(children: [
              FlutterMap(mapController: _map,
                  options: MapOptions(initialCenter: _centre, initialZoom: 7,
                      onPositionChanged: (pos, _) => _centre = pos.center),
                  children: [osmTiles()]),
              const Center(child: Padding(padding: EdgeInsets.only(bottom: 36), child: Icon(Icons.location_on, size: 44, color: Colors.red))),
              const MapAttribution(),
              Positioned(left: 16, right: 16, bottom: 24,
                  child: FilledButton(onPressed: _confirmMap, child: const Text('Use this spot'))),
            ])
          : Column(children: [
              Padding(
                padding: const EdgeInsets.all(12),
                child: TextField(controller: _q, autofocus: true, onChanged: _search,
                    decoration: const InputDecoration(hintText: 'Town, suburb, street or business', prefixIcon: Icon(Icons.search))),
              ),
              ListTile(leading: const Icon(Icons.my_location), title: const Text('Use my current location'), onTap: _useCurrent),
              const Divider(height: 1),
              Expanded(child: ListView.builder(
                itemCount: _results.length,
                itemBuilder: (_, i) => ListTile(
                  leading: const Icon(Icons.place_outlined),
                  title: Text(_results[i]['name'], maxLines: 2, overflow: TextOverflow.ellipsis),
                  onTap: () => Navigator.pop(context, Place(_results[i]['name'], _results[i]['lat'], _results[i]['lng'])),
                ),
              )),
            ]),
    );
  }
}
