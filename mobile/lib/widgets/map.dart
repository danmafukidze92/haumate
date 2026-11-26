import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

const zimCentre = LatLng(-19.0, 29.8);

TileLayer osmTiles() => TileLayer(
      urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      userAgentPackageName: 'zw.haulmate.app',
    );

Marker pin(LatLng p, Color c, IconData icon, {double size = 36}) => Marker(
      point: p, width: size, height: size,
      child: Icon(icon, color: c, size: size),
    );

Marker truckMarker(LatLng p, {Color color = const Color(0xFF1F3BB3)}) => Marker(
      point: p, width: 36, height: 36,
      child: Container(
        decoration: BoxDecoration(color: color, shape: BoxShape.circle,
            border: Border.all(color: Colors.white, width: 2),
            boxShadow: const [BoxShadow(blurRadius: 4, color: Colors.black26)]),
        child: const Icon(Icons.local_shipping, color: Colors.white, size: 20),
      ),
    );

class MapAttribution extends StatelessWidget {
  const MapAttribution({super.key});
  @override
  Widget build(BuildContext context) => const Align(
        alignment: Alignment.bottomRight,
        child: Padding(padding: EdgeInsets.all(4),
            child: Text('© OpenStreetMap', style: TextStyle(fontSize: 10, backgroundColor: Colors.white70))),
      );
}
