/**
 * Ensures react-native-ffmpeg-kit has a react-native.config.js for autolinking.
 * Expo's autolinker doesn't detect it without this file.
 * Run automatically via postinstall.
 */
const fs = require('fs');
const path = require('path');

const configPath = path.join(
  __dirname,
  '..',
  'node_modules',
  'react-native-ffmpeg-kit',
  'react-native.config.js'
);

const content = `module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import com.arthenica.ffmpegkit.reactnative.FFmpegKitReactNativePackage;',
        packageInstance: 'new FFmpegKitReactNativePackage()',
      },
    },
  },
};
`;

if (fs.existsSync(path.dirname(configPath))) {
  fs.writeFileSync(configPath, content);
  console.log('fix-ffmpeg-kit: react-native.config.js written');

  // Patch the library's build.gradle for compatibility with Expo SDK 57
  // - Remove hardcoded compileSdkVersion 33 (use root project's version)
  // - Remove old AGP buildscript (use root project's AGP)
  const gradlePath = path.join(
    path.dirname(configPath),
    'android',
    'build.gradle'
  );
  if (fs.existsSync(gradlePath)) {
    let gradle = fs.readFileSync(gradlePath, 'utf8');
    // Replace hardcoded SDK versions with root project versions
    gradle = gradle.replace(
      /compileSdkVersion 33/,
      'compileSdkVersion rootProject.ext.compileSdkVersion'
    );
    gradle = gradle.replace(
      /targetSdkVersion 33/,
      'targetSdkVersion rootProject.ext.targetSdkVersion'
    );
    gradle = gradle.replace(
      /minSdkVersion 24/,
      'minSdkVersion rootProject.ext.minSdkVersion'
    );
    fs.writeFileSync(gradlePath, gradle);
    console.log('fix-ffmpeg-kit: build.gradle patched');
  }
} else {
  console.log('fix-ffmpeg-kit: package not found, skipping');
}
