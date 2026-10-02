// Basic smoke tests for the Resurrect UI.

import 'package:flutter_test/flutter_test.dart';

import 'package:resurrect/main.dart';

void main() {
  testWidgets('starts disconnected with pairing controls', (tester) async {
    await tester.pumpWidget(const ResurrectApp());

    expect(find.text('Resurrect'), findsOneWidget);
    expect(find.text('Disconnected'), findsOneWidget);
    expect(find.text('Pair & Connect'), findsOneWidget);
    expect(find.text('Float over Settings to pair'), findsOneWidget);
    expect(find.text('Not connected'), findsOneWidget);
  });
}
