import 'dart:async';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';

/// Paynow: EcoCash / OneMoney / InnBucks push a prompt to the phone; card & others open Paynow's page.
/// The money is held by HaulMate and released to the driver when delivery is confirmed with the PIN.
Future<void> showPaySheet(BuildContext context, Map load) async {
  final s = context.read<Session>();
  String method = load['paymentMethod'] == 'CASH' ? 'ECOCASH' : load['paymentMethod'];
  final phone = TextEditingController(text: (s.user?['phone'] ?? '').toString().replaceFirst('+263', '0'));
  await showModalBottomSheet(context: context, isScrollControlled: true, builder: (ctx) => StatefulBuilder(
    builder: (ctx, set) => Padding(
      padding: EdgeInsets.fromLTRB(20, 20, 20, MediaQuery.of(ctx).viewInsets.bottom + 20),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('Pay ${fmtMoney(load['agreedPrice'])}', style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
        const Text('Held safely and released to the driver only after delivery.'),
        const SizedBox(height: 12),
        RadioGroup<String>(
          groupValue: method,
          onChanged: (v) => set(() => method = v!),
          child: Column(children: [
            for (final m in const [('ECOCASH', 'EcoCash'), ('ONEMONEY', 'OneMoney'), ('INNBUCKS', 'InnBucks'), ('PAYNOW_WEB', 'Card / other (Paynow page)')])
              RadioListTile<String>(value: m.$1, title: Text(m.$2)),
          ]),
        ),
        if (method != 'PAYNOW_WEB')
          TextField(controller: phone, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Wallet number')),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: () async {
            final p = await busy(ctx, () => s.api.post('/api/payments', {'loadId': load['id'], 'method': method, 'phone': phone.text}));
            if (p == null || !ctx.mounted) return;
            if (p['browserUrl'] != null) {
              await launchUrl(Uri.parse(p['browserUrl']), mode: LaunchMode.externalApplication);
            }
            if (ctx.mounted) Navigator.pop(ctx);
            if (context.mounted) await _waitForPayment(context, (p['id'] as num).toInt(), p['instructions']);
          },
          child: const Text('Pay now'),
        ),
      ]),
    ),
  ));
}

Future<void> _waitForPayment(BuildContext context, int paymentId, String? instructions) async {
  final api = context.read<Session>().api;
  String status = 'SENT';
  Timer? t;
  await showDialog(context: context, barrierDismissible: false, builder: (ctx) => StatefulBuilder(builder: (ctx, set) {
    t ??= Timer.periodic(const Duration(seconds: 5), (timer) async {
      try {
        final p = await api.get('/api/payments/$paymentId');
        set(() => status = p['status']);
        if (['PAID', 'AWAITING_DELIVERY', 'DELIVERED', 'CANCELLED', 'FAILED'].contains(status) || timer.tick > 36) {
          timer.cancel();
        }
      } catch (_) {}
    });
    final done = ['PAID', 'AWAITING_DELIVERY', 'DELIVERED'].contains(status);
    final failed = ['CANCELLED', 'FAILED'].contains(status);
    return AlertDialog(
      title: Text(done ? 'Payment received' : failed ? 'Payment not completed' : 'Waiting for payment'),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        if (!done && !failed) const LinearProgressIndicator(),
        const SizedBox(height: 12),
        Text(done ? 'Thank you. The money is held until your goods are delivered.'
            : failed ? 'The payment was cancelled or failed. You can try again.'
            : (instructions ?? 'Approve the payment prompt on your phone (enter your PIN).')),
      ]),
      actions: [TextButton(onPressed: () { t?.cancel(); Navigator.pop(ctx); }, child: Text(done || failed ? 'OK' : 'Close'))],
    );
  }));
  t?.cancel();
}
