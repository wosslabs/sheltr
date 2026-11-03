import 'package:flutter_test/flutter_test.dart';
import 'package:sheltr/format_passage.dart';

void main() {
  test('joins a word split by a line break and drops a page number', () {
    expect(
      formatPassage('differ-\nent hazards\n117\nStore drinking water.'),
      'Store drinking water.',
    );
    expect(formatPassage('Move to higher ground.'), 'Move to higher ground.');
  });

  test('keeps soft-wrapped bullet lines as one bullet', () {
    expect(
      formatPassage('• Store water\nfor three days.'),
      '• Store water for three days.',
    );
    expect(
      parsePassage(
        '• Plan for about 2,100 calories per person\n'
        'per day. This should be simple:\n'
        '• a basic staple such as rice\n'
        'Group 1\n'
        '• dried fruit',
      ),
      [
        const PassageBlock(
          PassageBlockKind.bullet,
          'Plan for about 2,100 calories per person per day. This should be simple:',
        ),
        const PassageBlock(PassageBlockKind.bullet, 'a basic staple such as rice'),
        const PassageBlock(PassageBlockKind.heading, 'Group 1'),
        const PassageBlock(PassageBlockKind.bullet, 'dried fruit'),
      ],
    );
  });

  test('does not treat a soft-wrapped phrase as a heading', () {
    expect(
      formatPassage('Stockpile essential\nfoods\n• Store rice'),
      'Stockpile essential foods\n\n• Store rice',
    );
    expect(
      parsePassage('DROUGHT\n• Store water'),
      [
        const PassageBlock(PassageBlockKind.heading, 'DROUGHT'),
        const PassageBlock(PassageBlockKind.bullet, 'Store water'),
      ],
    );
  });

  test('drops incomplete chunk edges and document chrome', () {
    expect(
      formatPassage(
        'evacuation route signs.\n'
        '• Plan to evacuate on foot.\n'
        '• Plan to evacuate pets and livestock.\n'
        'Learn and be ready\n'
        'to act on',
      ),
      '• Plan to evacuate on foot.\n\n'
      '• Plan to evacuate pets and livestock.',
    );
    expect(
      parsePassage(
        'International Federation of Red Cross and Red Crescent Societies\n'
        'Public awareness and public education for disaster risk reduction\n'
        'DROUGHT\n'
        'Stockpile essential\n'
        'foods\n'
        '• Preserve and store dry food.\n'
        '• Store products such as yoghurt (milk',
      ),
      [
        const PassageBlock(PassageBlockKind.heading, 'DROUGHT'),
        const PassageBlock(
          PassageBlockKind.paragraph,
          'Stockpile essential foods',
        ),
        const PassageBlock(
          PassageBlockKind.bullet,
          'Preserve and store dry food.',
        ),
      ],
    );
  });

  test('starts a new block after a finished bullet sentence', () {
    expect(
      parsePassage(
        '• food and livelihoods insecure.\n'
        'Preserve and store\n'
        'food year round\n'
        '• Preserve and store dry food.',
      ),
      [
        const PassageBlock(
          PassageBlockKind.bullet,
          'food and livelihoods insecure.',
        ),
        const PassageBlock(
          PassageBlockKind.paragraph,
          'Preserve and store food year round',
        ),
        const PassageBlock(
          PassageBlockKind.bullet,
          'Preserve and store dry food.',
        ),
      ],
    );
  });
}
