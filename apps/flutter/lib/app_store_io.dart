import 'package:path_provider/path_provider.dart';
import 'package:sheltr_core/sheltr_core.dart';

/// Opens the on-device database. Content arrives by sync, not from the sample.
Future<ContentStore> openAppStore() async {
  final directory = await getApplicationSupportDirectory();
  return ContentStore.open(directory.path);
}
