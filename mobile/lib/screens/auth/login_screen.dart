import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';
import 'register_screen.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  Future<void> _editServer(BuildContext context, Session s) async {
    final c = TextEditingController(text: s.server);
    final v = await showDialog<String>(context: context, builder: (_) => AlertDialog(
      title: const Text('Server address'),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        TextField(controller: c, keyboardType: TextInputType.url, autofocus: true,
            decoration: const InputDecoration(hintText: 'http://192.168.40.10:8670')),
        const SizedBox(height: 10),
        const Text('Testing on your PC: http://<PC IP>:8670\nPhone on USB: http://localhost:8670\nLive: https://cherai.nexigent-labs.co.zw',
            style: TextStyle(fontSize: 12, color: Colors.grey)),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, ''), child: const Text('Reset')),
        FilledButton(onPressed: () => Navigator.pop(context, c.text), child: const Text('Save')),
      ],
    ));
    if (v != null) await s.setServer(v);
  }

  final _phone = TextEditingController(), _pass = TextEditingController();
  bool _hide = true;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final s = context.watch<Session>();
    return Scaffold(
      body: SafeArea(
        child: ListView(padding: const EdgeInsets.all(24), children: [
          Align(
            alignment: Alignment.topRight,
            child: TextButton.icon(
              icon: const Icon(Icons.settings, size: 18),
              label: Text(s.server.replaceFirst(RegExp(r'^https?://'), ''), style: const TextStyle(fontSize: 12)),
              onPressed: () => _editServer(context, s),
            ),
          ),
          const SizedBox(height: 16),
          Icon(Icons.local_shipping, size: 64, color: cs.primary),
          const SizedBox(height: 8),
          Text.rich(TextSpan(children: [
            TextSpan(text: 'Haul', style: TextStyle(color: cs.primary)),
            TextSpan(text: 'Mate', style: TextStyle(color: cs.secondary)),
          ]), textAlign: TextAlign.center, style: const TextStyle(fontSize: 34, fontWeight: FontWeight.bold)),
          const Text('Trucks from 1 to 30 tonnes, anywhere in Zimbabwe', textAlign: TextAlign.center),
          const SizedBox(height: 40),
          TextField(controller: _phone, keyboardType: TextInputType.phone,
              decoration: const InputDecoration(labelText: 'Mobile number', hintText: '077 123 4567', prefixIcon: Icon(Icons.phone))),
          const SizedBox(height: 14),
          TextField(controller: _pass, obscureText: _hide,
              decoration: InputDecoration(labelText: 'Password', prefixIcon: const Icon(Icons.lock),
                  suffixIcon: IconButton(icon: Icon(_hide ? Icons.visibility : Icons.visibility_off),
                      onPressed: () => setState(() => _hide = !_hide)))),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: () => busy(context, () => context.read<Session>().login(_phone.text.trim(), _pass.text)),
            child: const Text('Sign in'),
          ),
          const SizedBox(height: 12),
          OutlinedButton(
            style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(50)),
            onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const RegisterScreen())),
            child: const Text('Create an account'),
          ),
        ]),
      ),
    );
  }
}
