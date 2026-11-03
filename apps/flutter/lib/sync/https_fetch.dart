import 'dart:io';
import 'dart:typed_data';

import 'package:sheltr_core/sheltr_core.dart';

/// Buffered bundle files stay under this size. The model file is streamed.
const httpsFetchByteCap = 8 * 1024 * 1024;

/// GET [url] and return the body. Refuses a non-https URL and any redirect.
///
/// The signature check still happens on the bytes this returns. A redirect is
/// refused so the host that was compiled in is the host that answered.
Future<SyncResponse> httpsSyncFetch(
  String url, {
  Duration timeout = const Duration(seconds: 20),
}) async {
  final client = HttpClient();
  try {
    final response = await _send(client, url, timeout);
    final builder = BytesBuilder(copy: false);
    await for (final chunk in response.timeout(timeout)) {
      if (builder.length + chunk.length > httpsFetchByteCap) {
        throw SyncHttpError('response exceeded $httpsFetchByteCap bytes');
      }
      builder.add(chunk);
    }
    return SyncResponse(
      ok: true,
      status: response.statusCode,
      bytes: builder.takeBytes(),
    );
  } finally {
    client.close(force: true);
  }
}

/// GET [url] and leave the body as a stream. The stream closes the client.
Future<SyncStreamResponse> httpsSyncStream(
  String url, {
  Duration timeout = const Duration(seconds: 20),
}) async {
  final client = HttpClient();
  try {
    final response = await _send(client, url, timeout);
    return SyncStreamResponse(
      ok: true,
      status: response.statusCode,
      body: _bind(client, response, timeout),
    );
  } catch (error) {
    client.close(force: true);
    rethrow;
  }
}

Future<HttpClientResponse> _send(
  HttpClient client,
  String url,
  Duration timeout,
) async {
  final parsed = Uri.tryParse(url);
  if (parsed == null ||
      parsed.scheme.toLowerCase() != 'https' ||
      parsed.host.isEmpty) {
    throw SyncHttpError('bundle base URL must use https: $url');
  }
  client.connectionTimeout = timeout;
  client.findProxy = HttpClient.findProxyFromEnvironment;
  final request = await client.getUrl(parsed).timeout(timeout);
  request.followRedirects = false;
  final response = await request.close().timeout(timeout);
  if (response.statusCode >= 300 && response.statusCode < 400) {
    await response.drain<void>();
    throw SyncHttpError('redirect refused for $url');
  }
  if (response.statusCode != 200) {
    await response.drain<void>();
    throw SyncHttpError('GET $url returned ${response.statusCode}');
  }
  return response;
}

Stream<List<int>> _bind(
  HttpClient client,
  HttpClientResponse response,
  Duration timeout,
) async* {
  try {
    yield* response.timeout(timeout);
  } finally {
    client.close(force: true);
  }
}
