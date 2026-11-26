import 'package:flutter_test/flutter_test.dart';
import 'package:haulmate_app/config.dart';

void main() {
  test('all truck classes have labels', () {
    expect(truckClasses.length, 5);
    expect(apiBase.startsWith('http'), true);
  });
}
