const {
  withMainApplication,
  withSettingsGradle,
  withAppBuildGradle,
} = require('@expo/config-plugins');

/**
 * Manually links react-native-ffmpeg-kit native module.
 * Expo's autolinker doesn't detect it (empty codegenConfig), so we
 * add the project to settings.gradle and the package to MainApplication.
 */
function withFfmpegKitNativeLink(config) {
  config = withSettingsGradle(config, (modConfig) => {
    const s = modConfig.modResults.contents;
    if (!s.includes('react-native-ffmpeg-kit')) {
      modConfig.modResults.contents = s.replace(
        /include ':app'/,
        `include ':app'\ninclude ':react-native-ffmpeg-kit'\nproject(':react-native-ffmpeg-kit').projectDir = new File(rootProject.projectDir, '../node_modules/react-native-ffmpeg-kit/android')`
      );
    }
    return modConfig;
  });

  config = withAppBuildGradle(config, (modConfig) => {
    const s = modConfig.modResults.contents;
    if (!s.includes('react-native-ffmpeg-kit')) {
      modConfig.modResults.contents = s.replace(
        /dependencies\s*\{/,
        `dependencies {\n    implementation project(':react-native-ffmpeg-kit')`
      );
    }
    return modConfig;
  });

  config = withMainApplication(config, (modConfig) => {
    let s = modConfig.modResults.contents;
    const importLine =
      'import com.arthenica.ffmpegkit.reactnative.FFmpegKitReactNativePackage;';
    if (!s.includes(importLine)) {
      // Kotlin file: add after the package line
      s = s.replace(
        /(package com\.clipforge\.app\n)/,
        `$1\n${importLine}\n`
      );
    }
    // Add to the ReactPackage list (Kotlin .apply block)
    if (!s.includes('FFmpegKitReactNativePackage()')) {
      s = s.replace(
        /(\/\/ Packages that cannot be autolinked yet can be added manually here, for example:)/,
        `$1\n          add(FFmpegKitReactNativePackage())`
      );
    }
    modConfig.modResults.contents = s;
    return modConfig;
  });

  return config;
}

module.exports = withFfmpegKitNativeLink;
