import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:provider/provider.dart';
import '../../services/session.dart';
import '../../widgets/common.dart';
import '../driver/truck_form.dart';
import 'liveness_screen.dart';

/// Identity verification: ID or passport, driver's licence (drivers), vehicle papers, live selfie + liveness.
class KycScreen extends StatefulWidget {
  const KycScreen({super.key});
  @override
  State<KycScreen> createState() => _KycScreenState();
}

class _KycScreenState extends State<KycScreen> {
  List docs = [];
  List trucks = [];
  bool loading = true;
  bool usePassport = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = context.read<Session>();
    try {
      docs = await s.api.get('/api/kyc/documents');
      if (s.isDriver) trucks = await s.api.get('/api/trucks');
      await s.refresh();
    } catch (_) {}
    if (mounted) setState(() => loading = false);
  }

  bool _has(String t) => docs.any((d) => d['docType'] == t && d['status'] != 'REJECTED');

  Future<void> _capture(String type, String title) async {
    final source = await showModalBottomSheet<ImageSource>(context: context, builder: (_) => SafeArea(
      child: Wrap(children: [
        ListTile(title: Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
            subtitle: const Text('Place the document on a flat, well-lit surface. All four corners must be visible.')),
        ListTile(leading: const Icon(Icons.photo_camera), title: const Text('Take a photo (recommended)'),
            onTap: () => Navigator.pop(context, ImageSource.camera)),
        ListTile(leading: const Icon(Icons.photo_library), title: const Text('Upload from gallery'),
            onTap: () => Navigator.pop(context, ImageSource.gallery)),
      ]),
    ));
    if (source == null) return;
    final img = await ImagePicker().pickImage(source: source, imageQuality: 85, maxWidth: 2000);
    if (img == null || !mounted) return;
    final api = context.read<Session>().api;
    await busy(context, () => api.uploadXFile('/api/kyc/documents', img,
        {'docType': type, 'captureMode': source == ImageSource.camera ? 'LIVE' : 'UPLOAD'}));
    _load();
  }

  Future<void> _liveness() async {
    final ok = await Navigator.push<bool>(context, MaterialPageRoute(builder: (_) => const LivenessScreen()));
    if (ok == true) _load();
  }

  Future<void> _submit() async {
    final s = context.read<Session>();
    final r = await busy(context, () => s.api.post('/api/kyc/submit'));
    if (r != null) {
      await s.refresh();
      if (mounted) setState(() {});
    }
  }

  Widget _step(String title, String sub, bool done, VoidCallback onTap) => Card(
        child: ListTile(
          leading: CircleAvatar(
            backgroundColor: done ? Colors.green : Colors.grey.shade300,
            child: Icon(done ? Icons.check : Icons.camera_alt, color: done ? Colors.white : Colors.black54),
          ),
          title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
          subtitle: Text(done ? 'Received - tap to retake' : sub),
          trailing: const Icon(Icons.chevron_right),
          onTap: onTap,
        ),
      );

  @override
  Widget build(BuildContext context) {
    final s = context.watch<Session>();
    final pending = s.kyc == 'PENDING';
    final rejected = s.kyc == 'REJECTED';
    final idDone = usePassport ? _has('PASSPORT') : _has('NATIONAL_ID');
    final selfieDone = _has('SELFIE') && (kIsWeb || docs.where((d) => d['docType'] == 'LIVENESS_FRAME').length >= 2);
    final licenceDone = s.role != 'DRIVER' || _has('DRIVERS_LICENCE');
    final truckDone = !s.isDriver || (trucks.isNotEmpty && _has('VEHICLE_REG'));
    final ready = idDone && selfieDone && licenceDone && truckDone;

    return Scaffold(
      appBar: AppBar(title: const Text('Verify your identity'), actions: [
        IconButton(icon: const Icon(Icons.refresh), onPressed: _load),
        IconButton(icon: const Icon(Icons.logout), onPressed: s.logout),
      ]),
      body: loading
          ? const Center(child: CircularProgressIndicator())
          : pending
              ? _pendingView()
              : ListView(padding: const EdgeInsets.all(16), children: [
                  if (rejected)
                    Card(color: Colors.red.shade50, child: const ListTile(
                        leading: Icon(Icons.error, color: Colors.red),
                        title: Text('Your verification was not approved'),
                        subtitle: Text('Check the notification we sent, retake the documents and submit again.'))),
                  Text('Hi ${s.user?['fullName'] ?? ''}. Every HaulMate user is verified, so shippers and drivers know who they deal with.',
                      style: const TextStyle(fontSize: 15)),
                  const SizedBox(height: 16),
                  SegmentedButton<bool>(
                    segments: const [ButtonSegment(value: false, label: Text('National ID')), ButtonSegment(value: true, label: Text('Passport'))],
                    selected: {usePassport},
                    onSelectionChanged: (v) => setState(() => usePassport = v.first),
                  ),
                  const SizedBox(height: 8),
                  _step(usePassport ? 'Passport photo page' : 'National ID card',
                      usePassport ? 'The page with your photo and the two code lines at the bottom' : 'Front of your ID card',
                      idDone, () => _capture(usePassport ? 'PASSPORT' : 'NATIONAL_ID', usePassport ? 'Passport' : 'National ID')),
                  if (s.role == 'DRIVER')
                    _step("Driver's licence", 'Must cover the class of truck you drive', _has('DRIVERS_LICENCE'),
                        () => _capture('DRIVERS_LICENCE', "Driver's licence")),
                  if (s.isDriver) ...[
                    _step(trucks.isEmpty ? 'Your truck' : 'Truck: ${trucks.first['regNumber']}',
                        'Registration, size and body type', trucks.isNotEmpty, () async {
                      await Navigator.push(context, MaterialPageRoute(builder: (_) => const TruckForm()));
                      _load();
                    }),
                    _step('Vehicle registration book', 'Photo of the registration book for this truck', _has('VEHICLE_REG'),
                        () => _capture('VEHICLE_REG', 'Vehicle registration book')),
                    _step('Insurance / ZINARA disc (optional)', 'Speeds up approval', _has('INSURANCE') || _has('ZINARA_DISC'),
                        () => _capture('INSURANCE', 'Insurance certificate or ZINARA disc')),
                  ],
                  _step('Live selfie', 'A quick face check: blink and turn your head', selfieDone, _liveness),
                  const SizedBox(height: 20),
                  FilledButton(onPressed: ready ? _submit : null, child: const Text('Submit for verification')),
                  const SizedBox(height: 8),
                  const Text('Your documents are encrypted and only used to verify you, as required by the Cyber and Data Protection Act.',
                      style: TextStyle(fontSize: 12, color: Colors.grey), textAlign: TextAlign.center),
                ]),
    );
  }

  Widget _pendingView() => Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          const Icon(Icons.hourglass_top, size: 72, color: Colors.orange),
          const SizedBox(height: 16),
          const Text('Verification in progress', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          const Text('We are checking your documents. This usually takes less than an hour during working hours. We will notify you.',
              textAlign: TextAlign.center),
          const SizedBox(height: 24),
          OutlinedButton.icon(onPressed: _load, icon: const Icon(Icons.refresh), label: const Text('Check status')),
        ]),
      );
}
