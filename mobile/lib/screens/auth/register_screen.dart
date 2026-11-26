import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';

class RegisterScreen extends StatefulWidget {
  const RegisterScreen({super.key});
  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends State<RegisterScreen> {
  String _role = 'SHIPPER';
  final _name = TextEditingController(), _phone = TextEditingController(), _email = TextEditingController(),
      _company = TextEditingController(), _pass = TextEditingController(), _pass2 = TextEditingController();
  bool _agree = false;

  Widget _roleTile(String role, IconData icon, String title, String sub) {
    final sel = _role == role;
    final cs = Theme.of(context).colorScheme;
    return Expanded(
      child: GestureDetector(
        onTap: () => setState(() => _role = role),
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            border: Border.all(color: sel ? cs.primary : Colors.grey.shade300, width: sel ? 2 : 1),
            borderRadius: BorderRadius.circular(12),
            color: sel ? cs.primary.withValues(alpha: .06) : null,
          ),
          child: Column(children: [
            Icon(icon, color: sel ? cs.primary : Colors.grey, size: 30),
            const SizedBox(height: 6),
            Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
            Text(sub, textAlign: TextAlign.center, style: const TextStyle(fontSize: 11)),
          ]),
        ),
      ),
    );
  }

  Future<void> _submit() async {
    if (_pass.text != _pass2.text) return toast(context, 'Passwords do not match', error: true);
    if (!_agree) return toast(context, 'Please accept the terms and the identity check', error: true);
    final s = context.read<Session>();
    final ok = await busy(context, () => s.register({
          'fullName': _name.text.trim(), 'phone': _phone.text.trim(),
          'email': _email.text.trim().isEmpty ? null : _email.text.trim(),
          'companyName': _company.text.trim().isEmpty ? null : _company.text.trim(),
          'password': _pass.text, 'role': _role,
        }).then((_) => true));
    if (ok == true && mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Create account')),
      body: ListView(padding: const EdgeInsets.all(20), children: [
        const Text('I want to', style: TextStyle(fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        Row(children: [
          _roleTile('SHIPPER', Icons.inventory_2, 'Send goods', 'Post loads, get trucks'),
          const SizedBox(width: 8),
          _roleTile('DRIVER', Icons.local_shipping, 'Drive', 'Owner-driver, find loads'),
          const SizedBox(width: 8),
          _roleTile('FLEET_OWNER', Icons.apartment, 'Fleet', 'Several trucks & drivers'),
        ]),
        const SizedBox(height: 20),
        TextField(controller: _name, textCapitalization: TextCapitalization.words,
            decoration: const InputDecoration(labelText: 'Full name (as on your ID)')),
        const SizedBox(height: 12),
        TextField(controller: _phone, keyboardType: TextInputType.phone,
            decoration: const InputDecoration(labelText: 'Mobile number', hintText: '077 123 4567')),
        const SizedBox(height: 12),
        TextField(controller: _email, keyboardType: TextInputType.emailAddress,
            decoration: const InputDecoration(labelText: 'Email (optional)')),
        if (_role != 'DRIVER') ...[
          const SizedBox(height: 12),
          TextField(controller: _company, decoration: const InputDecoration(labelText: 'Company name (optional)')),
        ],
        const SizedBox(height: 12),
        TextField(controller: _pass, obscureText: true, decoration: const InputDecoration(labelText: 'Password (8+ characters)')),
        const SizedBox(height: 12),
        TextField(controller: _pass2, obscureText: true, decoration: const InputDecoration(labelText: 'Confirm password')),
        const SizedBox(height: 8),
        CheckboxListTile(
          value: _agree, onChanged: (v) => setState(() => _agree = v ?? false),
          contentPadding: EdgeInsets.zero, controlAffinity: ListTileControlAffinity.leading,
          title: const Text('I agree to the terms, and to verify my identity with my ID and a live selfie',
              style: TextStyle(fontSize: 13)),
        ),
        const SizedBox(height: 12),
        FilledButton(onPressed: _submit, child: const Text('Continue to verification')),
      ]),
    );
  }
}
