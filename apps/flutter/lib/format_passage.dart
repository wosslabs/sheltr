/// Structured reading form of a stored passage.
///
/// PDF extracts often break mid-sentence and leave document chrome in a chunk.
/// Soft-wrapped lines are rejoined; incomplete edges and mastheads are dropped
/// for display. The stored chunk is unchanged, so the citation still quotes the
/// approved text.
library;

enum PassageBlockKind { paragraph, bullet, heading }

class PassageBlock {
  const PassageBlock(this.kind, this.text);

  final PassageBlockKind kind;
  final String text;

  @override
  bool operator ==(Object other) {
    return other is PassageBlock && other.kind == kind && other.text == text;
  }

  @override
  int get hashCode => Object.hash(kind, text);

  @override
  String toString() => 'PassageBlock($kind, $text)';
}

final _chromePatterns = <RegExp>[
  RegExp(
    r'^International Federation of Red Cross',
    caseSensitive: false,
  ),
  RegExp(
    r'^Public awareness and public education',
    caseSensitive: false,
  ),
  RegExp(r'^Key messages\b', caseSensitive: false),
  RegExp(r'^Context-specific details\b', caseSensitive: false),
];

/// Reading form as structured blocks for the answer screen.
List<PassageBlock> parsePassage(String raw) {
  final blocks = _parseBlocks(raw);
  final polished = _polishBlocks(blocks);
  if (polished.isNotEmpty) {
    return polished;
  }
  // Prefer a cleaned lead-in over an empty answer when polish removed everything.
  return _dropLeadingFragments(blocks);
}

/// Plain reading form for speech and simple Text widgets.
String formatPassage(String raw) {
  final parts = <String>[];
  for (final block in parsePassage(raw)) {
    switch (block.kind) {
      case PassageBlockKind.paragraph:
        parts.add(block.text);
      case PassageBlockKind.bullet:
        parts.add('• ${block.text}');
      case PassageBlockKind.heading:
        parts.add(block.text);
    }
  }
  return parts.join('\n\n');
}

List<PassageBlock> _parseBlocks(String raw) {
  var text = raw.replaceAll('\r\n', '\n').replaceAll('\r', '\n');
  text = text.replaceAllMapped(RegExp(r'-\n([a-z])'), (match) => match[1]!);
  // Page numbers glued to words: "reduction81TSUNAMI" or leading "46International".
  text = text.replaceAllMapped(
    RegExp(r'(?<=[a-z])\d{1,3}(?=[A-Z])'),
    (_) => '\n',
  );
  text = text.replaceAllMapped(
    RegExp(r'^(\d{1,3})(?=[A-Za-z])', multiLine: true),
    (_) => '',
  );

  final blocks = <PassageBlock>[];
  PassageBlockKind? kind;
  final buffer = StringBuffer();

  void flush() {
    final value = buffer.toString().replaceAll(RegExp(r' +'), ' ').trim();
    if (value.isNotEmpty && kind != null) {
      blocks.add(PassageBlock(kind!, value));
    }
    buffer.clear();
    kind = null;
  }

  void addHeading(String value) {
    final heading = value.replaceAll(RegExp(r' +'), ' ').trim();
    if (heading.isNotEmpty) {
      blocks.add(PassageBlock(PassageBlockKind.heading, heading));
    }
  }

  void writeLine(String line) {
    if (kind == null) {
      kind = PassageBlockKind.paragraph;
    } else if (buffer.isNotEmpty) {
      buffer.write(' ');
    }
    buffer.write(line);
  }

  final lines = [for (final line in text.split('\n')) line.trim()];
  for (var i = 0; i < lines.length; i++) {
    var trimmed = lines[i];
    if (trimmed.isEmpty ||
        RegExp(r'^\d{1,3}$').hasMatch(trimmed) ||
        RegExp(r'\.{8,}').hasMatch(trimmed)) {
      flush();
      continue;
    }

    final bulletMatch = RegExp(r'^(?:•|-|\*|–|—)\s+(.*)$').firstMatch(trimmed);
    if (bulletMatch != null) {
      flush();
      kind = PassageBlockKind.bullet;
      buffer.write(bulletMatch.group(1)!.trim());
      continue;
    }

    final trailingCaps = RegExp(
      r'^(.*[a-z].*)\s+([A-Z]{3,}(?:\s+[A-Z]{2,})*)$',
    ).firstMatch(trimmed);
    if (trailingCaps != null) {
      trimmed = trailingCaps.group(1)!.trim();
      final caps = trailingCaps.group(2)!.trim();
      if (kind != null &&
          buffer.isNotEmpty &&
          !_shouldContinue(buffer.toString(), trimmed)) {
        flush();
      }
      writeLine(trimmed);
      flush();
      addHeading(caps);
      continue;
    }

    final next = _nextContentLine(lines, i + 1);
    if (_isSectionHeading(trimmed, next)) {
      flush();
      addHeading(trimmed);
      continue;
    }

    if (kind != null &&
        buffer.isNotEmpty &&
        !_shouldContinue(buffer.toString(), trimmed)) {
      flush();
    }
    writeLine(trimmed);
  }
  flush();
  return blocks;
}

List<PassageBlock> _polishBlocks(List<PassageBlock> blocks) {
  var out = _dropChrome(blocks);
  out = _dropLeadingFragments(out);
  out = _trimTrailingIncomplete(out);
  return out;
}

List<PassageBlock> _dropChrome(List<PassageBlock> blocks) {
  return [
    for (final block in blocks)
      if (!_isChrome(block)) block,
  ];
}

bool _isChrome(PassageBlock block) {
  if (block.kind == PassageBlockKind.heading) {
    return false;
  }
  final text = block.text.trim();
  for (final pattern in _chromePatterns) {
    if (pattern.hasMatch(text)) {
      return true;
    }
  }
  return false;
}

List<PassageBlock> _dropLeadingFragments(List<PassageBlock> blocks) {
  final out = [...blocks];
  while (out.isNotEmpty) {
    final first = out.first;
    if (first.kind == PassageBlockKind.heading) {
      break;
    }
    final text = first.text.trim();
    // Chunk boundary mid-sentence: "evacuation route signs."
    // Bullets may legitimately start lowercase ("self-sufficient").
    if (first.kind == PassageBlockKind.paragraph &&
        RegExp(r'^[a-zà-öø-ÿ]').hasMatch(text)) {
      out.removeAt(0);
      continue;
    }
    // Tiny orphan sentence endings left by the previous chunk.
    if (first.kind == PassageBlockKind.paragraph &&
        text.length <= 48 &&
        RegExp(r'^[A-Za-z].*[.!?]"?$').hasMatch(text) &&
        text.split(RegExp(r'\s+')).length <= 6 &&
        !RegExp(
          r'^(Drop|Move|Stay|Go|Call|Plan|Store|Keep|Find|Learn|Post|Assess|Prepare|Stockpile|Preserve|Consider)\b',
          caseSensitive: false,
        ).hasMatch(text)) {
      out.removeAt(0);
      continue;
    }
    break;
  }
  return out;
}

List<PassageBlock> _trimTrailingIncomplete(List<PassageBlock> blocks) {
  if (blocks.isEmpty) {
    return blocks;
  }
  final out = [...blocks];
  while (out.isNotEmpty) {
    final last = out.last;
    if (last.kind == PassageBlockKind.heading) {
      break;
    }
    final trimmed = _trimIncompleteText(last.text);
    if (trimmed == null) {
      if (out.length == 1) {
        break;
      }
      out.removeLast();
      continue;
    }
    if (trimmed != last.text) {
      out[out.length - 1] = PassageBlock(last.kind, trimmed);
    }
    break;
  }
  return out;
}

/// Complete readable text, or null when the whole block should be dropped.
String? _trimIncompleteText(String text) {
  final value = text.trim();
  if (value.isEmpty) {
    return null;
  }
  if (_looksComplete(value)) {
    return value;
  }
  // Cut back to the last finished sentence inside the block.
  final match = RegExp(r'^(.*[.!?])(?:\s+.*)?$', dotAll: true).firstMatch(value);
  if (match != null) {
    final kept = match.group(1)!.trim();
    if (kept.length >= 24 && _looksComplete(kept)) {
      return kept;
    }
  }
  // Short list labels without a full stop are fine ("self-sufficient").
  if (_isShortLabel(value)) {
    return value;
  }
  return null;
}

bool _looksComplete(String text) {
  final value = text.trim();
  if (value.isEmpty) {
    return false;
  }
  if (RegExp(r'[.!?]"?$').hasMatch(value)) {
    return true;
  }
  if (RegExp(r':$').hasMatch(value)) {
    return true;
  }
  if (_isShortLabel(value)) {
    return true;
  }
  // Unbalanced brackets or a hanging comma mean the chunk was cut off.
  final opens = '('.allMatches(value).length;
  final closes = ')'.allMatches(value).length;
  if (opens != closes) {
    return false;
  }
  if (RegExp(r'[,;]$').hasMatch(value)) {
    return false;
  }
  return false;
}

bool _isShortLabel(String text) {
  final words = text.trim().split(RegExp(r'\s+'));
  return words.length <= 6 &&
      text.length <= 48 &&
      !text.contains('(') &&
      !RegExp(r'[,;]$').hasMatch(text);
}

String? _nextContentLine(List<String> lines, int from) {
  for (var i = from; i < lines.length; i++) {
    final line = lines[i];
    if (line.isEmpty ||
        RegExp(r'^\d{1,3}$').hasMatch(line) ||
        RegExp(r'\.{8,}').hasMatch(line)) {
      continue;
    }
    return line;
  }
  return null;
}

bool _shouldContinue(String current, String nextLine) {
  if (current.trim().isEmpty) {
    return false;
  }
  return RegExp(r'^[a-zà-öø-ÿ]').hasMatch(nextLine);
}

bool _isSectionHeading(String line, String? next) {
  if (RegExp(r'^Group\s+\d+\b', caseSensitive: false).hasMatch(line)) {
    return true;
  }
  if (next != null && RegExp(r'^[a-z]').hasMatch(next)) {
    return false;
  }
  final letters = line.replaceAll(RegExp(r'[^A-Za-z]'), '');
  if (letters.length >= 3 &&
      letters.length <= 40 &&
      letters == letters.toUpperCase() &&
      !line.endsWith('.') &&
      !line.endsWith(',')) {
    return true;
  }
  return false;
}
