// Learn more https://docs.expo.dev/guides/customizing-metro
const { getDefaultConfig } = require('expo/metro-config');

/** @type {import('expo/metro-config').MetroConfig} */
const config = getDefaultConfig(__dirname);

// Bundle the RNNoise model as an asset so the app can copy it to storage.
config.resolver.assetExts.push('rnnn');

module.exports = config;
