import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:sheltr_core/sheltr_core.dart';

import 'l10n/strings.dart';
import 'scope.dart';
import 'screens/about.dart';
import 'screens/answer.dart';
import 'screens/home.dart';
import 'screens/search.dart';
import 'screens/settings.dart';

void main() {
  runApp(const SheltrApp());
}

/// Sheltr: offline disaster guidance.
class SheltrApp extends StatefulWidget {
  const SheltrApp({
    super.key,
    this.settings = AppSettings.defaults,
    this.initialRoute = '/',
    this.answer,
    this.documents = const [],
  });

  final AppSettings settings;
  final String initialRoute;
  final RetrievalResult? answer;
  final List<StoredDocument> documents;

  @override
  State<SheltrApp> createState() => _SheltrAppState();
}

class _SheltrAppState extends State<SheltrApp> {
  final _strings = const AppStrings();
  late AppSettings _settings;

  @override
  void initState() {
    super.initState();
    _settings = widget.settings;
  }

  @override
  void didUpdateWidget(SheltrApp oldWidget) {
    super.didUpdateWidget(oldWidget);
    _settings = widget.settings;
  }

  void _updateSettings(AppSettings next) {
    assertValidSettings(next);
    setState(() => _settings = next);
  }

  @override
  Widget build(BuildContext context) {
    final languageCode = resolveLanguage(
      _settings.language,
      WidgetsBinding.instance.platformDispatcher.locales,
    );
    return MaterialApp(
      title: _strings.t(languageCode, 'app_name'),
      locale: Locale(languageCode),
      supportedLocales: const [Locale('en'), Locale('es'), Locale('fr')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      theme: sheltrTheme(_settings, Brightness.light),
      darkTheme: sheltrTheme(_settings, Brightness.dark),
      builder: (context, child) {
        return MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(_settings.textScale)),
          child: child ?? const SizedBox.shrink(),
        );
      },
      home: SheltrScope(
        strings: _strings,
        settings: _settings,
        languageCode: languageCode,
        answer: widget.answer,
        documents: widget.documents,
        updateSettings: _updateSettings,
        child: Navigator(
          key: ValueKey(widget.initialRoute),
          initialRoute: widget.initialRoute,
          onGenerateRoute: (settings) {
            final name = settings.name ?? '/';
            return MaterialPageRoute<void>(
              settings: settings,
              builder: (context) =>
                  _Page(route: name, arguments: settings.arguments),
            );
          },
        ),
      ),
    );
  }
}

class _Page extends StatelessWidget {
  const _Page({required this.route, this.arguments});

  final String route;
  final Object? arguments;

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final title = switch (route) {
      '/search' => scope.t('search_title'),
      '/settings' => scope.t('settings_title'),
      '/about' => scope.t('about_title'),
      _ => scope.t('app_name'),
    };
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        automaticallyImplyLeading: false,
        leading: route == '/'
            ? null
            : SheltrIconButton(
                label: scope.t('back'),
                icon: Icons.arrow_back,
                onPressed: () {
                  final navigator = Navigator.of(context);
                  if (navigator.canPop()) {
                    navigator.pop();
                  }
                },
              ),
        actions: route == '/'
            ? [
                SheltrIconButton(
                  label: scope.t('home_settings_button_label'),
                  icon: Icons.settings,
                  onPressed: () => Navigator.of(context).pushNamed('/settings'),
                ),
              ]
            : null,
      ),
      body: _body(scope),
    );
  }

  Widget _body(SheltrScope scope) {
    switch (route) {
      case '/search':
        return SearchScreen(
          initialQuery: _argument('query') ?? '',
          initialHazard: _argument('hazard'),
        );
      case '/answer':
        return const AnswerScreen();
      case '/settings':
        return const SettingsScreen();
      case '/about':
        return const AboutScreen();
      default:
        return const HomeScreen();
    }
  }

  String? _argument(String key) {
    final arguments = this.arguments;
    if (arguments is! Map) {
      return null;
    }
    final value = arguments[key];
    return value is String ? value : null;
  }
}
