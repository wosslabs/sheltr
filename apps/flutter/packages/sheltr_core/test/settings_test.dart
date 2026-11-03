import 'package:sheltr_core/sheltr_core.dart';
import 'package:test/test.dart';

void main() {
  test('damagedStoredSettingsFallBackToDefaults', () {
    expect(parseSettings(null), AppSettings.defaults);
    expect(parseSettings('not json'), AppSettings.defaults);
    expect(parseSettings('[1]'), AppSettings.defaults);
    expect(parseSettings('null'), AppSettings.defaults);

    expect(AppSettings.defaults.syncFrequency, SyncFrequency.weekly);
    expect(AppSettings.defaults.allowMobileData, isFalse);
    expect(AppSettings.defaults.country, isNull);
    expect(AppSettings.defaults.textScale, 1.0);
    expect(AppSettings.defaults.highContrast, isFalse);
    expect(AppSettings.defaults.language, Language.system);

    final damaged = parseSettings(
      '{"country":"gb","textScale":"HUGE","highContrast":true,"language":"de","syncFrequency":"yearly","allowMobileData":"no"}',
    );
    expect(damaged, AppSettings.defaults.copyWith(highContrast: true));

    expect(
      parseSettings(
        '{"syncFrequency":"manual","allowMobileData":true,"country":"NZ","textScale":1.2,"language":"es"}',
      ),
      AppSettings.defaults.copyWith(
        syncFrequency: SyncFrequency.manual,
        allowMobileData: true,
        country: 'NZ',
        textScale: 1.2,
        language: Language.es,
      ),
    );
    expect(parseSettings('{"textScale":1.0}').textScale, 1.0);
    expect(parseSettings('{"textScale":1.5}').textScale, 1.0);
    expect(parseSettings('{"textScale":1.4}').textScale, 1.4);
  });

  test('accepts two uppercase letters and null', () {
    expect(
      () => assertValidSettings(AppSettings.defaults.copyWith(country: 'GB')),
      returnsNormally,
    );
    expect(
      () => assertValidSettings(AppSettings.defaults.copyWith(country: null)),
      returnsNormally,
    );
  });

  test('rejects other country values', () {
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

  test('has the specified intervals', () {
    expect(textScaleFactors, {1.0, 1.2, 1.4});
    expect(syncIntervalSeconds[SyncFrequency.manual], isNull);
    expect(syncIntervalSeconds[SyncFrequency.daily], 86400);
    expect(syncIntervalSeconds[SyncFrequency.weekly], 604800);
    expect(syncIntervalSeconds[SyncFrequency.monthly], 30 * 86400);
  });
}
