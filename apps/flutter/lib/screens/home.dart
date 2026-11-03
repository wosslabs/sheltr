import 'package:flutter/material.dart';
import 'package:sheltr_core/sheltr_core.dart';

import '../scope.dart';

/// Home: twelve hazards, or a question.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _openSearch(String query) {
    final trimmed = query.trim();
    if (trimmed.isEmpty) {
      return;
    }
    Navigator.of(context).pushNamed('/search', arguments: {'query': trimmed});
  }

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final hazards = Rules.packaged().disasterTypes;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(
          scope.t('home_title'),
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 16),
        SheltrTextField(
          label: scope.t('home_search_hint_description'),
          hint: scope.t('home_search_placeholder'),
          controller: _controller,
          onSubmitted: _openSearch,
        ),
        const SizedBox(height: 16),
        LayoutBuilder(
          builder: (context, constraints) {
            final width = (constraints.maxWidth - 12) / 2;
            return Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                for (final hazard in hazards)
                  SizedBox(
                    width: width,
                    child: FilledButton.tonal(
                      onPressed: () {
                        Navigator.of(
                          context,
                        ).pushNamed('/search', arguments: {'hazard': hazard});
                      },
                      child: Text(
                        scope.t('hazard_${hazard.toLowerCase()}'),
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ),
              ],
            );
          },
        ),
      ],
    );
  }
}
