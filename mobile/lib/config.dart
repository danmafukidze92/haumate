/// Server address. Override at build time:
///   flutter run --dart-define=API_BASE=http://10.0.2.2:8670      (Android emulator -> your PC)
///   flutter run --dart-define=API_BASE=http://192.168.x.x:8670   (real phone on the same Wi-Fi)
const String defaultApiBase = String.fromEnvironment('API_BASE', defaultValue: 'https://cherai.nexigent-labs.co.zw');

/// Current server address. Can be changed in the app (gear icon on the sign-in screen),
/// so a new PC IP address doesn't need a new build.
String apiBase = defaultApiBase;

String get wsUrl => '${apiBase.replaceFirst('https://', 'wss://').replaceFirst('http://', 'ws://')}/ws';

const truckClasses = {
  'PICKUP_1T': '1 t pickup / bakkie',
  'LIGHT_3T': '3-5 t light truck',
  'MEDIUM_8T': '8-10 t medium truck',
  'HEAVY_15T': '15-20 t rigid / tipper',
  'SUPER_30T': '30 t+ horse & trailer',
};

const bodyTypes = {
  'OPEN': 'Open', 'CLOSED_BOX': 'Closed box', 'FLATBED': 'Flatbed', 'TIPPER': 'Tipper',
  'TAUTLINER': 'Tautliner', 'REFRIGERATED': 'Refrigerated', 'TANKER': 'Tanker', 'LOWBED': 'Lowbed',
};

const cargoTypes = ['General goods', 'Furniture / household', 'Building materials', 'Agricultural produce',
  'Fertiliser / seed', 'Mining equipment', 'Machinery', 'Livestock feed', 'Fuel / liquids', 'Retail stock', 'Other'];

const statusLabels = {
  'OPEN': 'Finding a truck', 'ASSIGNED': 'Driver assigned', 'EN_ROUTE_PICKUP': 'Driver on the way',
  'AT_PICKUP': 'Driver at pickup', 'LOADED': 'Loaded', 'IN_TRANSIT': 'In transit',
  'DELIVERED': 'Delivered', 'COMPLETED': 'Completed', 'CANCELLED': 'Cancelled',
};
