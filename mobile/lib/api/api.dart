import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart' show XFile;
import '../config.dart';

class ApiError implements Exception {
  final String message;
  ApiError(this.message);
  @override
  String toString() => message;
}

/// Thin wrapper over the HaulMate REST API (see /api/docs on the server).
class Api {
  final Dio _dio = Dio(BaseOptions(baseUrl: apiBase, connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 30)));
  String? token;

  void setBase(String url) => _dio.options.baseUrl = url;
  void Function()? onUnauthorized;

  Api() {
    _dio.interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
      if (token != null) o.headers['Authorization'] = 'Bearer $token';
      h.next(o);
    }));
  }

  Future<dynamic> _call(Future<Response> Function() f) async {
    try {
      return (await f()).data;
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) onUnauthorized?.call();
      final d = e.response?.data;
      if (d is Map && d['error'] != null) throw ApiError(d['error'].toString());
      if (e.type == DioExceptionType.connectionError || e.type == DioExceptionType.connectionTimeout) {
        throw ApiError('No connection to HaulMate. Check your data or Wi-Fi.');
      }
      throw ApiError('Something went wrong (${e.response?.statusCode ?? e.type.name})');
    }
  }

  Future<dynamic> get(String p, [Map<String, dynamic>? q]) => _call(() => _dio.get(p, queryParameters: q));
  Future<dynamic> post(String p, [dynamic body]) => _call(() => _dio.post(p, data: body ?? {}));
  Future<dynamic> put(String p, [dynamic body]) => _call(() => _dio.put(p, data: body ?? {}));

  Future<dynamic> upload(String p, String filePath, Map<String, dynamic> fields, {String fileField = 'file'}) {
    return _call(() async => _dio.post(p, data: FormData.fromMap({
          ...fields,
          fileField: await MultipartFile.fromFile(filePath, filename: filePath.split(RegExp(r'[\\/]')).last, contentType: _imageType(filePath)),
        })));
  }

  /// Works on phones and in the browser (uses bytes, not a file path).
  Future<dynamic> uploadXFile(String p, XFile f, Map<String, dynamic> fields, {String fileField = 'file'}) {
    return _call(() async => _dio.post(p, data: FormData.fromMap({
          ...fields,
          fileField: MultipartFile.fromBytes(await f.readAsBytes(), filename: f.name,
              contentType: DioMediaType('image', f.name.toLowerCase().endsWith('.png') ? 'png' : 'jpeg')),
        })));
  }

  Future<dynamic> form(String p, Map<String, dynamic> fields, {String? filePath, String fileField = 'photo'}) {
    return _call(() async => _dio.post(p, data: FormData.fromMap({
          ...fields,
          if (filePath != null) fileField: await MultipartFile.fromFile(filePath, filename: filePath.split(RegExp(r'[\\/]')).last, contentType: _imageType(filePath)),
        })));
  }

  static DioMediaType _imageType(String path) =>
      DioMediaType('image', path.toLowerCase().endsWith('.png') ? 'png' : 'jpeg');

  // Free OpenStreetMap geocoding limited to Zimbabwe
  final Dio _osm = Dio(BaseOptions(baseUrl: 'https://nominatim.openstreetmap.org',
      headers: {'User-Agent': 'HaulMate/1.0 (cherai.nexigent-labs.co.zw)'}));

  Future<List<Map<String, dynamic>>> searchPlaces(String q) async {
    final r = await _osm.get('/search', queryParameters: {'q': q, 'format': 'json', 'countrycodes': 'zw', 'limit': 8});
    return (r.data as List).map((e) => {
          'name': e['display_name'] as String,
          'lat': double.parse(e['lat']),
          'lng': double.parse(e['lon']),
        }).toList();
  }

  Future<String> reverse(double lat, double lng) async {
    try {
      final r = await _osm.get('/reverse', queryParameters: {'lat': lat, 'lon': lng, 'format': 'json', 'zoom': 16});
      return (r.data['display_name'] as String?) ?? '${lat.toStringAsFixed(5)}, ${lng.toStringAsFixed(5)}';
    } catch (_) {
      return '${lat.toStringAsFixed(5)}, ${lng.toStringAsFixed(5)}';
    }
  }
}
