import 'package:flutter/material.dart';
import 'package:sheltr_core/sheltr_core.dart';

import '../scope.dart';

/// Country, language, text size, contrast, and sync.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  String? _syncMessage;

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final settings = scope.settings;
    final country = settings.country == null
        ? scope.t('settings_country_not_set')
        : countryNames[settings.country]?.forLanguage(scope.languageCode) ??
              settings.country!;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _Section(
          title: scope.t('settings_country_label'),
          children: [
            OutlinedButton(
              onPressed: () => _pickCountry(scope),
              child: Text(country, textAlign: TextAlign.center),
            ),
          ],
        ),
        _Section(
          title: scope.t('settings_language_label'),
          children: [
            _Choice(
              label: scope.t('settings_language_system_default'),
              selected: settings.language == Language.system,
              onPressed: () =>
                  _update(scope, settings.copyWith(language: Language.system)),
            ),
            _Choice(
              label: 'English',
              selected: settings.language == Language.en,
              onPressed: () =>
                  _update(scope, settings.copyWith(language: Language.en)),
            ),
            _Choice(
              label: 'Español',
              selected: settings.language == Language.es,
              onPressed: () =>
                  _update(scope, settings.copyWith(language: Language.es)),
            ),
            _Choice(
              label: 'Français',
              selected: settings.language == Language.fr,
              onPressed: () =>
                  _update(scope, settings.copyWith(language: Language.fr)),
            ),
            Text(scope.t('settings_language_notice')),
          ],
        ),
        _Section(
          title: scope.t('settings_text_size_label'),
          children: [
            _Choice(
              label: scope.t('settings_text_size_normal'),
              selected: settings.textScale == 1.0,
              onPressed: () =>
                  _update(scope, settings.copyWith(textScale: 1.0)),
            ),
            _Choice(
              label: scope.t('settings_text_size_large'),
              selected: settings.textScale == 1.2,
              onPressed: () =>
                  _update(scope, settings.copyWith(textScale: 1.2)),
            ),
            _Choice(
              label: scope.t('settings_text_size_extra_large'),
              selected: settings.textScale == 1.4,
              onPressed: () =>
                  _update(scope, settings.copyWith(textScale: 1.4)),
            ),
          ],
        ),
        _Section(
          title: scope.t('settings_high_contrast_label'),
          children: [
            _Toggle(
              label: scope.t('settings_high_contrast_label'),
              value: settings.highContrast,
              onChanged: (value) {
                _update(scope, settings.copyWith(highContrast: value));
              },
            ),
          ],
        ),
        _Section(
          title: scope.t('settings_sync_frequency_label'),
          children: [
            for (final frequency in SyncFrequency.values)
              _Choice(
                label: scope.t(_frequencyKey(frequency)),
                selected: settings.syncFrequency == frequency,
                onPressed: () {
                  _update(scope, settings.copyWith(syncFrequency: frequency));
                },
              ),
            _Toggle(
              label: scope.t('settings_mobile_data_label'),
              value: settings.allowMobileData,
              onChanged: (value) {
                _update(scope, settings.copyWith(allowMobileData: value));
              },
            ),
            Text(scope.t('settings_mobile_data_hint')),
          ],
        ),
        _Section(
          title: scope.t('settings_sync_now_label'),
          children: [
            Text(
              scope.t('settings_last_sync_label', {
                'p1': scope.t('settings_last_sync_never'),
              }),
            ),
            FilledButton(
              onPressed: () {
                setState(() {
                  _syncMessage = scope.t('settings_sync_unavailable_message');
                });
              },
              child: Text(scope.t('settings_sync_now_label')),
            ),
            if (_syncMessage != null) ...[
              Text(_syncMessage!),
              OutlinedButton(
                onPressed: () => setState(() => _syncMessage = null),
                child: Text(scope.t('settings_sync_dismiss_label')),
              ),
            ],
          ],
        ),
        OutlinedButton(
          onPressed: () => Navigator.of(context).pushNamed('/about'),
          child: Text(scope.t('settings_about_button_label')),
        ),
      ],
    );
  }

  void _update(SheltrScope scope, AppSettings next) {
    assertValidSettings(next);
    scope.updateSettings(next);
  }

  Future<void> _pickCountry(SheltrScope scope) async {
    final choice = await showDialog<_CountryChoice>(
      context: context,
      useRootNavigator: false,
      builder: (context) => _CountryDialog(
        selected: scope.settings.country,
        languageCode: scope.languageCode,
      ),
    );
    if (!mounted || choice == null) {
      return;
    }
    _update(scope, scope.settings.copyWith(country: choice.code));
  }
}

String _frequencyKey(SyncFrequency frequency) {
  return switch (frequency) {
    SyncFrequency.manual => 'sync_frequency_manual',
    SyncFrequency.daily => 'sync_frequency_daily',
    SyncFrequency.weekly => 'sync_frequency_weekly',
    SyncFrequency.monthly => 'sync_frequency_monthly',
  };
}

class _Choice extends StatelessWidget {
  const _Choice({
    required this.label,
    required this.selected,
    required this.onPressed,
  });

  final String label;
  final bool selected;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final button = selected
        ? FilledButton(
            onPressed: onPressed,
            child: Text(label, textAlign: TextAlign.center),
          )
        : OutlinedButton(
            onPressed: onPressed,
            child: Text(label, textAlign: TextAlign.center),
          );
    return Align(alignment: Alignment.centerLeft, child: button);
  }
}

class _Toggle extends StatelessWidget {
  const _Toggle({
    required this.label,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      enabled: true,
      label: label,
      toggled: value,
      onTap: () => onChanged(!value),
      excludeSemantics: true,
      child: SwitchListTile(
        contentPadding: EdgeInsets.zero,
        title: Text(label),
        value: value,
        onChanged: onChanged,
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          ...children,
        ],
      ),
    );
  }
}

class _CountryChoice {
  const _CountryChoice(this.code);

  final String? code;
}

class _CountryDialog extends StatefulWidget {
  const _CountryDialog({required this.selected, required this.languageCode});

  final String? selected;
  final String languageCode;

  @override
  State<_CountryDialog> createState() => _CountryDialogState();
}

class _CountryDialogState extends State<_CountryDialog> {
  final _query = TextEditingController();

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final needle = _query.text.trim().toLowerCase();
    final rows =
        [
          for (final entry in countryNames.entries)
            if (needle.isEmpty ||
                entry.key.toLowerCase() == needle ||
                entry.value
                    .forLanguage(widget.languageCode)
                    .toLowerCase()
                    .contains(needle))
              entry,
        ]..sort(
          (a, b) => a.value
              .forLanguage(widget.languageCode)
              .compareTo(b.value.forLanguage(widget.languageCode)),
        );
    return Dialog.fullscreen(
      child: Scaffold(
        appBar: AppBar(
          title: Text(scope.t('settings_country_label')),
          leading: SheltrIconButton(
            label: scope.t('back'),
            icon: Icons.close,
            onPressed: () => Navigator.of(context).pop(),
          ),
        ),
        body: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: SheltrTextField(
                label: scope.t('settings_country_search_placeholder'),
                controller: _query,
                onChanged: (_) => setState(() {}),
              ),
            ),
            _Choice(
              label: scope.t('settings_country_not_set'),
              selected: widget.selected == null,
              onPressed: () =>
                  Navigator.of(context).pop(const _CountryChoice(null)),
            ),
            Expanded(
              child: ListView.builder(
                itemCount: rows.length,
                itemBuilder: (context, index) {
                  final entry = rows[index];
                  return _Choice(
                    label: entry.value.forLanguage(widget.languageCode),
                    selected: widget.selected == entry.key,
                    onPressed: () {
                      Navigator.of(context).pop(_CountryChoice(entry.key));
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
