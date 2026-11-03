import 'package:flutter/material.dart';
import 'package:sheltr_core/sheltr_core.dart';

import '../scope.dart';

/// A question, optionally narrowed to one hazard.
class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key, this.initialQuery = '', this.initialHazard});

  final String initialQuery;
  final String? initialHazard;

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  late final TextEditingController _controller;
  late String? _hazard;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initialQuery);
    final types = Rules.packaged().disasterTypes;
    final hazard = widget.initialHazard;
    _hazard = hazard != null && types.contains(hazard) ? hazard : null;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  bool get _canSubmit => _controller.text.trim().isNotEmpty;

  void _submit() {
    final trimmed = _controller.text.trim();
    if (trimmed.isEmpty) {
      return;
    }
    Navigator.of(context).pushNamed(
      '/answer',
      arguments: {'text': trimmed, if (_hazard != null) 'hazard': _hazard},
    );
  }

  @override
  Widget build(BuildContext context) {
    final scope = SheltrScope.of(context);
    final theme = Theme.of(context);
    final hazard = _hazard;
    return SheltrPageBackground(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
        children: [
          const CountryLine(),
          const SizedBox(height: 16),
          if (hazard != null) ...[
            Align(
              alignment: Alignment.centerLeft,
              child: InputChip(
                avatar: const Icon(Icons.filter_alt, size: 18),
                label: Text(
                  scope.t('search_hazard_chip', {
                    'p1': scope.t('hazard_${hazard.toLowerCase()}'),
                  }),
                ),
                onDeleted: () => setState(() => _hazard = null),
                deleteButtonTooltipMessage: scope.t(
                  'search_active_hazard_chip_description',
                ),
              ),
            ),
            const SizedBox(height: 16),
          ],
          Semantics(
            header: true,
            child: Text(
              scope.t('search_title'),
              style: theme.textTheme.headlineSmall,
            ),
          ),
          const SizedBox(height: 12),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: SheltrTextField(
                  label: scope.t('search_placeholder'),
                  controller: _controller,
                  onChanged: (_) => setState(() {}),
                  onSubmitted: (_) => _submit(),
                ),
              ),
              if (_controller.text.isNotEmpty)
                SheltrIconButton(
                  label: scope.t('search_clear_description'),
                  icon: Icons.close,
                  onPressed: () {
                    _controller.clear();
                    setState(() {});
                  },
                ),
            ],
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _canSubmit ? _submit : null,
            icon: const Icon(Icons.search),
            label: Text(scope.t('search_submit_description')),
          ),
        ],
      ),
    );
  }
}
