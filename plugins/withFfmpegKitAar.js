const { withDangerousMod, withProjectBuildGradle } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Copies the bundled ffmpeg-kit AAR into android/libs/ and adds the
 * flatDir repository so Gradle can resolve the local AAR.
 * (The upstream build.gradle looks for the AAR but never declares flatDir.)
 */
module.exports = function withFfmpegKitAar(config) {
  config = withDangerousMod(config, [
    'android',
    async (modConfig) => {
      const projectRoot = modConfig.modRequest.projectRoot;
      const src = path.join(projectRoot, 'assets', 'libs', 'ffmpeg-kit-https.aar');
      const destDir = path.join(modConfig.modRequest.platformProjectRoot, 'libs');
      const dest = path.join(destDir, 'ffmpeg-kit-https.aar');
      if (!fs.existsSync(src)) {
        throw new Error(`withFfmpegKitAar: missing ${src}`);
      }
      fs.mkdirSync(destDir, { recursive: true });
      fs.copyFileSync(src, dest);
      return modConfig;
    },
  ]);

  // Add flatDir so `implementation(name: ..., ext: 'aar')` resolves.
  config = withProjectBuildGradle(config, (modConfig) => {
    const gradle = modConfig.modResults.contents;
    if (!gradle.includes('flatDir')) {
      modConfig.modResults.contents = gradle.replace(
        /allprojects\s*\{\s*\n(\s*)repositories\s*\{/,
        (m, indent) =>
          `allprojects {\n${indent}repositories {\n${indent}    flatDir { dirs "$rootDir/libs" }`
      );
    }
    return modConfig;
  });

  return config;
};
