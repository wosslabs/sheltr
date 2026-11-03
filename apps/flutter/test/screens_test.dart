import 'dart:io';
import 'dart:ui' show CheckedState, Tristate;

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sheltr/main.dart';
import 'package:sheltr_core/sheltr_core.dart';

void main() {
  testWidgets('unsupportedDeviceLanguageShowsEnglish', (tester) async {
    await _pump(tester, locales: const [Locale('de')]);
    expect(find.text('Prepare for a disaster'), findsOneWidget);
    expect(find.text('Prepárate para un desastre'), findsNothing);
    expect(find.text('Préparez-vous à une catastrophe'), findsNothing);

    await _pump(tester, locales: const [Locale('es', 'MX')]);
    expect(find.text('Prepárate para un desastre'), findsOneWidget);

    await _pump(
      tester,
      locales: const [Locale('de')],
      settings: const AppSettings(language: Language.fr),
    );
    expect(find.text('Préparez-vous à une catastrophe'), findsOneWidget);
  });

  test('damagedStoredSettingsFallBackToDefaults', () {
    expect(parseSettings(null), AppSettings.defaults);
    expect(parseSettings('not json'), AppSettings.defaults);
    expect(parseSettings('[1]'), AppSettings.defaults);
    expect(parseSettings('null'), AppSettings.defaults);

    expect(AppSettings.defaults.allowMobileData, isFalse);
    expect(AppSettings.defaults.country, isNull);
    expect(AppSettings.defaults.textScale, 1.0);
    expect(AppSettings.defaults.syncFrequency, SyncFrequency.weekly);
    expect(AppSettings.defaults.language, Language.system);
    expect(textScaleFactors, {1.0, 1.2, 1.4});
    expect(syncIntervalSeconds[SyncFrequency.manual], isNull);
    expect(syncIntervalSeconds[SyncFrequency.daily], 86400);
    expect(syncIntervalSeconds[SyncFrequency.weekly], 604800);
    expect(syncIntervalSeconds[SyncFrequency.monthly], 2592000);

    final damaged = parseSettings(
      '{"country":"gb","textScale":"HUGE","highContrast":true,"language":"de","syncFrequency":"yearly","allowMobileData":"no"}',
    );
    expect(damaged.country, isNull);
    expect(damaged.textScale, 1.0);
    expect(damaged.highContrast, isTrue);
    expect(damaged.language, Language.system);
    expect(damaged.syncFrequency, SyncFrequency.weekly);
    expect(damaged.allowMobileData, isFalse);

    final kept = parseSettings(
      '{"syncFrequency":"daily","allowMobileData":true,"country":"NZ","textScale":1.4,"highContrast":false,"language":"fr"}',
    );
    expect(kept.syncFrequency, SyncFrequency.daily);
    expect(kept.allowMobileData, isTrue);
    expect(kept.country, 'NZ');
    expect(kept.textScale, 1.4);
    expect(kept.language, Language.fr);

    expect(
      () => assertValidSettings(AppSettings.defaults.copyWith(country: 'GB')),
      returnsNormally,
    );
    expect(
      () => assertValidSettings(AppSettings.defaults.copyWith(country: null)),
      returnsNormally,
    );
    for (final bad in [
      'gb',
      'GBR',
      '1A',
      '',
      'G',
      'G-',
      'Gb',
      'ÉS',
      'GB\u0000',
    ]) {
      expect(
        () => assertValidSettings(AppSettings.defaults.copyWith(country: bad)),
        throwsRangeError,
        reason: bad,
      );
    }
  });

  testWidgets('largestTextScaleKeepsEveryControlReachable', (tester) async {
    await tester.binding.setSurfaceSize(const Size(320, 568));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    const settings = AppSettings(textScale: 1.4);

    for (final route in ['/', '/search', '/answer', '/settings', '/about']) {
      await _pump(
        tester,
        settings: settings,
        route: route,
        answer: route == '/answer' ? _passages() : null,
      );
      final context = tester.element(find.byType(Scaffold).first);
      expect(MediaQuery.textScalerOf(context).scale(1), 1.4);

      final controls = _controls();
      expect(controls, findsWidgets, reason: route);
      final count = controls.evaluate().length;
      for (var i = 0; i < count; i++) {
        final item = controls.at(i);
        await tester.ensureVisible(item);
        expect(
          tester.getSize(item).shortestSide,
          greaterThanOrEqualTo(48),
          reason: '$route control $i',
        );
      }
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets('everyButtonHasASemanticsLabel', (tester) async {
    final handle = tester.ensureSemantics();
    try {
      for (final route in ['/', '/search', '/answer', '/settings', '/about']) {
        await _pump(
          tester,
          route: route,
          answer: route == '/answer' ? _passages() : null,
        );
        final controls = _semanticControls(tester);
        expect(controls, isNotEmpty, reason: route);
        for (final node in controls) {
          expect(node.label.trim(), isNotEmpty, reason: route);
        }
      }
    } finally {
      handle.dispose();
    }
  });

  testWidgets('textFieldKeepsItsValueAndSetTextAction', (tester) async {
    final handle = tester.ensureSemantics();
    try {
      await _pump(tester, route: '/search');
      await tester.showKeyboard(find.byType(TextField));
      await tester.enterText(find.byType(TextField), 'flood');
      await tester.pump();
      final fields = _semanticControls(tester)
          .where((node) => node.flagsCollection.isTextField);
      expect(fields, isNotEmpty);
      final data = fields.single.getSemanticsData();
      expect(data.value, 'flood');
      expect(data.label.trim(), isNotEmpty);
      expect(data.hasAction(SemanticsAction.setText), isTrue);
    } finally {
      handle.dispose();
    }
  });

  testWidgets('enabledButtonOrSwitchHasATapAction', (tester) async {
    final handle = tester.ensureSemantics();
    try {
      for (final route in ['/', '/search', '/answer', '/settings', '/about']) {
        await _pump(
          tester,
          route: route,
          answer: route == '/answer' ? _passages() : null,
        );
        for (final node in _semanticControls(tester)) {
          final flags = node.flagsCollection;
          final isSwitch = flags.isToggled != Tristate.none;
          if (!flags.isButton && !isSwitch) {
            continue;
          }
          if (node.label.trim().isEmpty ||
              flags.isEnabled == Tristate.isFalse) {
            continue;
          }
          expect(
            node.getSemanticsData().hasAction(SemanticsAction.tap),
            isTrue,
            reason: '$route "${node.label}"',
          );
        }
      }
    } finally {
      handle.dispose();
    }
  });

  testWidgets('answerShowsAgencyTitleDatePageAndLink', (tester) async {
    await _pump(tester, route: '/answer', answer: _passages());

    expect(find.text('FEMA'), findsOneWidget);
    expect(find.text('Flood Guide'), findsOneWidget);
    expect(find.text('Published 2024-01-15'), findsOneWidget);
    expect(find.text('Page 4'), findsOneWidget);
    expect(find.text('https://example.gov/flood'), findsOneWidget);
    expect(find.text('Move to higher ground.'), findsOneWidget);
    expect(find.byType(TextButton), findsNothing);
    expect(find.byType(SelectionArea), findsOneWidget);
  });

  testWidgets('refusalShowsOwnText', (tester) async {
    await _pump(
      tester,
      route: '/answer',
      answer: const RetrievalResult.refuse(),
    );

    expect(find.text("We can't answer that yet"), findsOneWidget);
    expect(
      find.text(
        "Sheltr doesn't have reliable guidance on this yet. Try different search words, or contact local emergency services for urgent help.",
      ),
      findsOneWidget,
    );
    expect(find.text('Move to higher ground.'), findsNothing);
    expect(find.text('FEMA'), findsNothing);
  });

  testWidgets('homeShowsTwelveHazards', (tester) async {
    await _pump(tester);

    const hazards = [
      'Earthquake',
      'Flood',
      'Tropical Cyclone',
      'Tornado',
      'Tsunami',
      'Wildfire',
      'Extreme Heat',
      'Winter Storm',
      'Landslide',
      'Glacial Lake Flood',
      'Volcano',
      'Drought',
    ];
    expect(hazards, hasLength(12));
    for (final hazard in hazards) {
      expect(find.text(hazard), findsOneWidget);
    }
    expect(find.text('Prepare for a disaster'), findsOneWidget);
  });

  testWidgets('searchSubmitDisabledWhenBlank', (tester) async {
    final handle = tester.ensureSemantics();
    try {
      await _pump(tester, route: '/search');

      expect(_searchButton(tester).flagsCollection.isEnabled, Tristate.isFalse);

      await tester.enterText(find.byType(TextField), '   ');
      await tester.pump();
      expect(_searchButton(tester).flagsCollection.isEnabled, Tristate.isFalse);

      await tester.enterText(find.byType(TextField), 'flood');
      await tester.pump();
      expect(_searchButton(tester).flagsCollection.isEnabled, Tristate.isTrue);
    } finally {
      handle.dispose();
    }
  });

  testWidgets('systemBackPopsTheAppNavigator', (tester) async {
    await _pump(tester);
    await tester.tap(find.text('Earthquake'));
    await tester.pumpAndSettle();
    expect(find.text('Hazard: Earthquake'), findsOneWidget);

    expect(await tester.binding.handlePopRoute(), isTrue);
    await tester.pumpAndSettle();
    expect(find.text('Prepare for a disaster'), findsOneWidget);
    expect(find.text('Hazard: Earthquake'), findsNothing);
  });

  testWidgets('switchTogglesOnce', (tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await _pump(tester, route: '/settings');
    final toggle = find.byType(Switch).first;
    expect(tester.widget<Switch>(toggle).value, isFalse);
    await tester.tap(toggle);
    await tester.pump();
    expect(tester.widget<Switch>(toggle).value, isTrue);
  });

  testWidgets('searchAnswersFromTheOpenStore', (tester) async {
    final store = await _openSampleStore();
    await _pump(
      tester,
      route: '/search',
      store: store,
      answer: const RetrievalResult.refuse(),
    );
    await _search(tester, 'what should I do during an earthquake');

    expect(find.text("We can't answer that yet"), findsNothing);
    await tester.scrollUntilVisible(_textFolded('cover your head'), 400);
    expect(_textFolded('cover your head'), findsWidgets);
    expect(
      find.textContaining('International Federation of Red Cross'),
      findsWidgets,
    );
  });

  testWidgets('emptyStoreRefuses', (tester) async {
    final store = await _openSampleStore(install: false);
    await _pump(tester, route: '/search', store: store, answer: _passages());
    await _search(tester, 'what should I do during an earthquake');

    expect(find.text("We can't answer that yet"), findsOneWidget);
    expect(find.text('Move to higher ground.'), findsNothing);
    expect(_textFolded('cover your head'), findsNothing);
  });

  testWidgets('failedRelevanceCheckRefuses', (tester) async {
    final store = await _openSampleStore();
    await _pump(tester, route: '/search', store: store);
    await _search(tester, 'how do I');

    expect(find.text("We can't answer that yet"), findsOneWidget);
    expect(_textFolded('cover your head'), findsNothing);
  });
}

Finder _textFolded(String needle) {
  final lower = needle.toLowerCase();
  return find.byWidgetPredicate((widget) {
    if (widget is! Text) {
      return false;
    }
    final data = widget.data ?? widget.textSpan?.toPlainText();
    return data != null && data.toLowerCase().contains(lower);
  });
}

Future<void> _pump(
  WidgetTester tester, {
  AppSettings settings = AppSettings.defaults,
  String route = '/',
  List<Locale>? locales,
  RetrievalResult? answer,
  ContentStore? store,
}) async {
  if (locales != null) {
    tester.platformDispatcher.localesTestValue = locales;
    addTearDown(tester.platformDispatcher.clearLocalesTestValue);
  }
  await tester.pumpWidget(
    SheltrApp(
      settings: settings,
      initialRoute: route,
      answer: answer,
      store: store,
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _search(WidgetTester tester, String text) async {
  await tester.enterText(find.byType(TextField), text);
  await tester.pump();
  await tester.tap(find.widgetWithText(FilledButton, 'Search'));
  await tester.pumpAndSettle();
}

Future<ContentStore> _openSampleStore({bool install = true}) async {
  final temp = Directory.systemTemp.createTempSync('sheltr-app-');
  addTearDown(() => temp.deleteSync(recursive: true));
  final store = ContentStore.open(temp);
  addTearDown(store.close);
  if (!install) {
    return store;
  }
  final bundleDir = Directory('../mobile/assets/sample-bundle');
  final manifest = parseBundleManifest(
    File('${bundleDir.path}/bundle.json').readAsStringSync(),
  );
  store.install(manifest, (document) {
    return parseDocumentFileBytes(
      File('${bundleDir.path}/documents/${document.id}.json').readAsBytesSync(),
    );
  });
  return store;
}

Finder _controls() {
  return find.byWidgetPredicate(
    (widget) =>
        widget is ButtonStyleButton ||
        widget is IconButton ||
        widget is TextField ||
        widget is Switch,
  );
}

List<SemanticsNode> _semanticControls(WidgetTester tester) {
  final root = tester.binding.pipelineOwner.semanticsOwner?.rootSemanticsNode;
  expect(root, isNotNull);
  final controls = <SemanticsNode>[];
  void walk(SemanticsNode node) {
    final flags = node.flagsCollection;
    final isControl =
        flags.isButton ||
        flags.isLink ||
        flags.isTextField ||
        flags.isChecked != CheckedState.none ||
        flags.isToggled != Tristate.none;
    if (isControl) {
      controls.add(node);
    }
    node.visitChildren((child) {
      walk(child);
      return true;
    });
  }

  walk(root!);
  return controls;
}

SemanticsNode _searchButton(WidgetTester tester) {
  final matches = _semanticControls(tester)
      .where((node) => node.flagsCollection.isButton && node.label == 'Search');
  expect(matches, hasLength(1));
  return matches.single;
}

RetrievalResult _passages() {
  const document = StoredDocument(
    id: 'doc-1',
    title: 'Flood Guide',
    agency: 'FEMA',
    publishedDate: '2024-01-15',
    licence: 'public-domain',
    country: 'US',
    hazards: ['FLOOD'],
    sourceUri: 'https://example.gov/flood',
    sourceType: 'html',
    checksum: 'abc',
    fileChecksum: 'def',
  );
  const chunk = StoredChunk(
    id: 'chunk-1',
    documentId: 'doc-1',
    content: 'Move to higher ground.',
    pageNumber: 4,
    position: 0,
  );
  return RetrievalResult.passages([
    const Passage(chunk: chunk, document: document, score: 2, rankScore: 2),
  ]);
}
