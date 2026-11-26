import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../config.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';

class TruckForm extends StatefulWidget {
  const TruckForm({super.key});
  @override
  State<TruckForm> createState() => _TruckFormState();
}

class _TruckFormState extends State<TruckForm> {
  final _reg = TextEditingController(), _make = TextEditingController(), _model = TextEditingController(),
      _cap = TextEditingController();
  String _class = 'LIGHT_3T', _body = 'OPEN';

  Future<void> _save() async {
    final api = context.read<Session>().api;
    final r = await busy(context, () => api.post('/api/trucks', {
          'regNumber': _reg.text, 'make': _make.text, 'model': _model.text,
          'truckClass': _class, 'bodyType': _body,
          'capacityTonnes': double.tryParse(_cap.text),
        }));
    if (r != null && mounted) Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Add a truck')),
        body: ListView(padding: const EdgeInsets.all(16), children: [
          TextField(controller: _reg, textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(labelText: 'Registration number', hintText: 'ABC 1234')),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(child: TextField(controller: _make, decoration: const InputDecoration(labelText: 'Make', hintText: 'Isuzu'))),
            const SizedBox(width: 12),
            Expanded(child: TextField(controller: _model, decoration: const InputDecoration(labelText: 'Model', hintText: 'FTR 850'))),
          ]),
          const SizedBox(height: 12),
          DropdownButtonFormField(initialValue: _class, decoration: const InputDecoration(labelText: 'Size'),
              items: truckClasses.entries.map((e) => DropdownMenuItem(value: e.key, child: Text(e.value))).toList(),
              onChanged: (v) => setState(() => _class = v!)),
          const SizedBox(height: 12),
          DropdownButtonFormField(initialValue: _body, decoration: const InputDecoration(labelText: 'Body type'),
              items: bodyTypes.entries.map((e) => DropdownMenuItem(value: e.key, child: Text(e.value))).toList(),
              onChanged: (v) => setState(() => _body = v!)),
          const SizedBox(height: 12),
          TextField(controller: _cap, keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(labelText: 'Payload capacity (tonnes)')),
          const SizedBox(height: 20),
          FilledButton(onPressed: _save, child: const Text('Save truck')),
        ]),
      );
}
