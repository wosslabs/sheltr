import 'package:flutter_test/flutter_test.dart';
import 'package:sheltr/sync/https_fetch.dart';
import 'package:sheltr/sync/run_sync.dart';
import 'package:sheltr_core/sheltr_core.dart';

void main() {
  test('an http URL is refused before a connection', () async {
    await expectLater(
      httpsSyncFetch('http://127.0.0.1:9/bundle.json'),
      throwsA(
        isA<SyncHttpError>().having(
          (error) => error.message,
          'message',
          contains('https'),
        ),
      ),
    );
  });

  test('an empty key list does not fetch', () async {
    final result = await runPublishedSync(
      baseUrl: 'http://127.0.0.1:9/bundle',
      keys: const [],
      store: null,
    );
    expect(result, isNull);
    expect(
      publishedBundleConfigured('https://example.org/bundle', const []),
      isFalse,
    );
    expect(publishedBundleConfigured('', const []), isFalse);
  });
}
