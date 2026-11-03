import 'package:flutter/material.dart';

import '../scope.dart';

/// What Sheltr is, and the licence of every installed document.
class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final fromStore = scope.store?.documents();
    final documents = fromStore != null && fromStore.isNotEmpty
        ? fromStore
        : scope.documents;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(
          scope.t('about_title'),
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 12),
        Text(scope.t('about_description')),
        const SizedBox(height: 16),
        Text(
          scope.t('about_disclaimer_title'),
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        Text(scope.t('about_disclaimer')),
        const SizedBox(height: 16),
        Text(
          scope.t('about_privacy_title'),
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        Text(scope.t('about_privacy')),
        const SizedBox(height: 16),
        Text(
          scope.t('about_sources_title'),
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        if (documents.isEmpty)
          Text(scope.t('about_sources_empty'))
        else ...[
          Text(scope.t('about_sources_intro')),
          for (final document in documents) ...[
            const SizedBox(height: 12),
            Text(document.title, style: Theme.of(context).textTheme.titleSmall),
            Text(
              scope.t('about_source_details', {
                'p1': document.agency,
                'p2': document.publishedDate,
                'p3': document.country ?? 'GLOBAL',
              }),
            ),
            Text(scope.t('about_source_reviewed', {'p1': document.reviewedOn})),
            Text(scope.t('about_source_expires', {'p1': document.expiresOn})),
            Text(scope.t('about_source_licence', {'p1': document.licence})),
            Text(scope.t('about_source_link', {'p1': document.sourceUri})),
          ],
        ],
        const SizedBox(height: 16),
        Text(scope.t('about_code_licence')),
      ],
    );
  }
}
