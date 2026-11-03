import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sheltr/main.dart';

void main() {
  testWidgets('app opens on the home screen', (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale('en')];
    addTearDown(tester.platformDispatcher.clearLocalesTestValue);
    await tester.pumpWidget(const SheltrApp());
    await tester.pumpAndSettle();
    expect(find.text('Prepare for a disaster'), findsOneWidget);
  });
}
