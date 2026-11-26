import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../config.dart';

final money = NumberFormat.currency(symbol: '\$', decimalDigits: 0);
String fmtMoney(dynamic v) => v == null ? '-' : money.format(num.parse('$v'));

void toast(BuildContext context, Object msg, {bool error = false}) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
    content: Text('$msg'),
    backgroundColor: error ? Colors.red.shade700 : null,
  ));
}

/// Runs an async action with a spinner dialog and shows any error.
Future<T?> busy<T>(BuildContext context, Future<T> Function() f) async {
  showDialog(context: context, barrierDismissible: false,
      builder: (_) => const Center(child: CircularProgressIndicator()));
  try {
    final r = await f();
    if (context.mounted) Navigator.of(context, rootNavigator: true).pop();
    return r;
  } catch (e) {
    if (context.mounted) {
      Navigator.of(context, rootNavigator: true).pop();
      toast(context, e, error: true);
    }
    return null;
  }
}

class StatusChip extends StatelessWidget {
  final String status;
  const StatusChip(this.status, {super.key});
  @override
  Widget build(BuildContext context) {
    final c = switch (status) {
      'OPEN' => Colors.red,
      'DELIVERED' || 'COMPLETED' => Colors.green,
      'CANCELLED' => Colors.grey,
      'LOADED' || 'IN_TRANSIT' => Colors.orange,
      _ => const Color(0xFF1F3BB3),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: c.withValues(alpha: .12), borderRadius: BorderRadius.circular(20)),
      child: Text(statusLabels[status] ?? status, style: TextStyle(color: c, fontWeight: FontWeight.w600, fontSize: 12)),
    );
  }
}

class LoadCard extends StatelessWidget {
  final Map load;
  final VoidCallback onTap;
  final Widget? trailing;
  final String? extra;
  const LoadCard({super.key, required this.load, required this.onTap, this.trailing, this.extra});
  @override
  Widget build(BuildContext context) {
    final price = load['agreedPrice'] ?? load['offeredPrice'];
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Text(load['ref'] ?? '', style: const TextStyle(fontWeight: FontWeight.bold)),
              const Spacer(),
              StatusChip(load['status'] ?? ''),
            ]),
            const SizedBox(height: 8),
            _place(Icons.trip_origin, Colors.green, load['pickupAddress']),
            _place(Icons.location_on, Colors.red, load['dropoffAddress']),
            const SizedBox(height: 8),
            Row(children: [
              Text('${load['weightTonnes']} t · ${truckClasses[load['truckClass']] ?? ''}',
                  style: TextStyle(color: Colors.grey.shade700, fontSize: 13)),
              const Spacer(),
              Text(fmtMoney(price), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            ]),
            if (extra != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(extra!, style: const TextStyle(fontSize: 13))),
            if (trailing != null) Padding(padding: const EdgeInsets.only(top: 8), child: trailing!),
          ]),
        ),
      ),
    );
  }

  Widget _place(IconData i, Color c, String? t) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(i, size: 16, color: c), const SizedBox(width: 6),
          Expanded(child: Text(t ?? '', maxLines: 2, overflow: TextOverflow.ellipsis)),
        ]),
      );
}
