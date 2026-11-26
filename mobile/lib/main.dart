import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'services/session.dart';
import 'services/push.dart';
import 'services/realtime.dart';
import 'screens/auth/login_screen.dart';
import 'screens/kyc/kyc_screen.dart';
import 'screens/shipper/shipper_home.dart';
import 'screens/driver/driver_home.dart';
import 'screens/common/call_screen.dart';
import 'screens/common/load_detail_screen.dart';

final navKey = GlobalKey<NavigatorState>();
final messengerKey = GlobalKey<ScaffoldMessengerState>();

const brand = Color(0xFF1F3BB3);
const accent = Color(0xFFF95F53);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Push.init();
  final session = Session();
  await session.restore();
  runApp(ChangeNotifierProvider.value(value: session, child: const HaulMateApp()));
}

class HaulMateApp extends StatefulWidget {
  const HaulMateApp({super.key});
  @override
  State<HaulMateApp> createState() => _HaulMateAppState();
}

class _HaulMateAppState extends State<HaulMateApp> {
  void Function()? _callSub, _alertSub;
  int? _subscribedFor;

  void _wireGlobalListeners(Session s) {
    if (s.user == null) {
      _callSub?.call(); _alertSub?.call(); _subscribedFor = null;
      return;
    }
    if (_subscribedFor == s.id) return;
    _subscribedFor = s.id;
    // Incoming calls from any screen
    _callSub = Realtime.instance.subscribe('/user/queue/call', (m) {
      if (m['type'] == 'ring') {
        navKey.currentState?.push(MaterialPageRoute(
            builder: (_) => CallScreen(loadId: (m['loadId'] as num).toInt(), incoming: true)));
      }
    });
    // In-app alerts (new loads nearby, offers, status changes)
    _alertSub = Realtime.instance.subscribe('/user/queue/alerts', (m) {
      final loadId = (m['data']?['loadId'] as num?)?.toInt();
      messengerKey.currentState?.showSnackBar(SnackBar(
        content: Text('${m['title']}\n${m['body']}'),
        duration: const Duration(seconds: 6),
        action: loadId == null ? null : SnackBarAction(label: 'Open', onPressed: () => _openLoad(loadId)),
      ));
    });
    Push.onForeground = (msg) => messengerKey.currentState?.showSnackBar(
        SnackBar(content: Text('${msg.notification?.title ?? ''}\n${msg.notification?.body ?? ''}')));
    Push.onTap = (data) {
      final id = int.tryParse('${data['loadId'] ?? ''}');
      if (data['type'] == 'call' && id != null) {
        navKey.currentState?.push(MaterialPageRoute(builder: (_) => CallScreen(loadId: id, incoming: true)));
      } else if (id != null) {
        _openLoad(id);
      }
    };
  }

  void _openLoad(int id) =>
      navKey.currentState?.push(MaterialPageRoute(builder: (_) => LoadDetailScreen(loadId: id)));

  @override
  Widget build(BuildContext context) {
    final s = context.watch<Session>();
    WidgetsBinding.instance.addPostFrameCallback((_) => _wireGlobalListeners(s));
    return MaterialApp(
      title: 'HaulMate',
      navigatorKey: navKey,
      scaffoldMessengerKey: messengerKey,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: brand, primary: brand, secondary: accent),
        useMaterial3: true,
        inputDecorationTheme: const InputDecorationTheme(border: OutlineInputBorder()),
        filledButtonTheme: FilledButtonThemeData(style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(50))),
      ),
      home: s.loading
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : s.user == null
              ? const LoginScreen()
              : (s.kyc != 'VERIFIED')
                  ? const KycScreen()
                  : s.isDriver
                      ? const DriverHome()
                      : const ShipperHome(),
    );
  }
}
