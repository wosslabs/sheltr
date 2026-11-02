// Icon fonts load through expo-font, which has nothing to load in Jest. Render the icon name instead.
jest.mock('@expo/vector-icons', () => {
  const React = require('react');
  const { Text } = require('react-native');
  const Icon = ({ name }: { name: string }) => React.createElement(Text, null, `icon:${name}`);
  return { MaterialCommunityIcons: Icon };
});
