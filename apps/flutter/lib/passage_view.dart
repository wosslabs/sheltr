import 'package:flutter/material.dart';

import 'format_passage.dart';

/// Renders a stored passage with readable paragraphs, bullets, and headings.
class PassageView extends StatelessWidget {
  const PassageView(this.raw, {super.key, this.color});

  final String raw;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final blocks = parsePassage(raw);
    if (blocks.isEmpty) {
      return const SizedBox.shrink();
    }
    final bodyStyle = theme.textTheme.bodyLarge?.copyWith(
      height: 1.45,
      color: color,
    );
    final headingStyle = theme.textTheme.titleMedium?.copyWith(
      height: 1.3,
      color: color,
      fontWeight: FontWeight.w700,
    );

    final children = <Widget>[];
    for (var i = 0; i < blocks.length; i++) {
      final block = blocks[i];
      final next = i + 1 < blocks.length ? blocks[i + 1] : null;
      final bottom = switch ((block.kind, next?.kind)) {
        (PassageBlockKind.bullet, PassageBlockKind.bullet) => 10.0,
        (PassageBlockKind.heading, _) => 12.0,
        (_, PassageBlockKind.heading) => 18.0,
        (PassageBlockKind.paragraph, PassageBlockKind.bullet) => 14.0,
        _ => 14.0,
      };

      children.add(
        Padding(
          padding: EdgeInsets.only(bottom: i == blocks.length - 1 ? 0 : bottom),
          child: switch (block.kind) {
            PassageBlockKind.paragraph => Text(block.text, style: bodyStyle),
            PassageBlockKind.heading => Text(block.text, style: headingStyle),
            PassageBlockKind.bullet => Padding(
              padding: const EdgeInsets.only(left: 2),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 22,
                    child: Text('•', style: bodyStyle),
                  ),
                  Expanded(child: Text(block.text, style: bodyStyle)),
                ],
              ),
            ),
          },
        ),
      );
    }

    return SelectionArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: children,
      ),
    );
  }
}
