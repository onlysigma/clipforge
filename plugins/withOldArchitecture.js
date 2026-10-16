const { withGradleProperties } = require('@expo/config-plugins');

/**
 * Disables React Native New Architecture.
 *
 * react-native-ffmpeg-kit does not have a codegenConfig (it only supports
 * the Old Architecture / Bridge). With newArchEnabled=true, the native
 * module methods are not available to JS, causing
 * "TypeError: undefined is not a function" when calling FFprobeKit/FFmpegKit.
 */
module.exports = function withOldArchitecture(config) {
  return withGradleProperties(config, (modConfig) => {
    const props = modConfig.modResults;
    const idx = props.findIndex((p) => p.key === 'newArchEnabled');
    if (idx >= 0) {
      props[idx].value = 'false';
    } else {
      props.push({ key: 'newArchEnabled', value: 'false' });
    }
    return modConfig;
  });
};
