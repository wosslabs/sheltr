const path = require('path');
const { getDefaultConfig } = require('expo/metro-config');

const projectRoot = __dirname;
const config = getDefaultConfig(projectRoot);

// Retrieval constants are imported from the repo contract, which sits outside this app.
config.watchFolders = [...config.watchFolders, path.resolve(projectRoot, '../../contract')];

module.exports = config;
