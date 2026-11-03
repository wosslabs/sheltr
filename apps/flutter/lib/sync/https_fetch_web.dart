import 'dart:async';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:sheltr_core/sheltr_core.dart';
import 'package:web/web.dart' as web;

/// Buffered bundle files stay under this size. Matches the `dart:io` client.
const httpsFetchByteCap = 8 * 1024 * 1024;

/// Browser fetch for a release that compiled an https host.
///
/// `redirect: manual` stops the browser following a redirect. An opaque
/// redirect (status 0) is refused, the same way the phone build refuses a
/// 3xx response.
Future<SyncResponse> httpsSyncFetch(
  String url, {
  Duration timeout = const Duration(seconds: 20),
}) async {
  final bytes = await _get(url, timeout);
  return SyncResponse(ok: true, status: 200, bytes: bytes);
}

Future<SyncStreamResponse> httpsSyncStream(
  String url, {
  Duration timeout = const Duration(seconds: 20),
}) async {
  final bytes = await _get(url, timeout);
  return SyncStreamResponse(
    ok: true,
    status: 200,
    body: Stream<List<int>>.value(bytes),
  );
}

Future<Uint8List> _get(String url, Duration timeout) async {
  final parsed = Uri.tryParse(url);
  if (parsed == null ||
      parsed.scheme.toLowerCase() != 'https' ||
      parsed.host.isEmpty) {
    throw SyncHttpError('bundle base URL must use https: $url');
  }
  final response = await web.window
      .fetch(url.toJS, web.RequestInit(redirect: 'manual'))
      .toDart
      .timeout(timeout);
  if (response.redirected ||
      response.type == 'opaqueredirect' ||
      response.status == 0 ||
      (response.status >= 300 && response.status < 400)) {
    throw SyncHttpError('redirect refused for $url');
  }
  if (response.status != 200) {
    throw SyncHttpError('GET $url returned ${response.status}');
  }
  final declared = int.tryParse(response.headers.get('content-length') ?? '');
  if (declared != null && declared > httpsFetchByteCap) {
    throw SyncHttpError('response exceeded $httpsFetchByteCap bytes');
  }
  final buffer = (await response.arrayBuffer().toDart).toDart;
  final bytes = buffer.asUint8List();
  if (bytes.length > httpsFetchByteCap) {
    throw SyncHttpError('response exceeded $httpsFetchByteCap bytes');
  }
  return bytes;
}
