// Two projects: the pure TypeScript core runs in plain Node (it uses Node's built-in SQLite as a stand-in for
// expo-sqlite); the React Native UI runs under jest-expo.
const transformIgnorePatterns = [
  'node_modules/(?!((jest-)?react-native|@react-native(-community)?|expo(nent)?|@expo(nent)?/.*|@expo-google-fonts/.*|react-navigation|@react-navigation/.*|@sentry/react-native|native-base|react-native-svg|@noble/.*))',
];

module.exports = {
  projects: [
    {
      displayName: 'core',
      preset: 'jest-expo',
      testEnvironment: 'node',
      testMatch: ['<rootDir>/src/core/**/*.test.ts', '<rootDir>/scripts/**/*.test.ts'],
      transformIgnorePatterns,
    },
    {
      displayName: 'ui',
      preset: 'jest-expo',
      setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
      testMatch: ['<rootDir>/src/ui/**/*.test.tsx', '<rootDir>/src/i18n/**/*.test.ts'],
      transformIgnorePatterns,
    },
  ],
};
