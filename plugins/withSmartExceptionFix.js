const { withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/**
 * Adds the missing smart-exception-java replacement class.
 *
 * FFmpegKit was retired (Jan 2025) and its transitive dependency
 * com.arthenica:smart-exception-java is no longer on Maven. Without it,
 * FFmpegKitConfig.<clinit> throws NoClassDefFoundError at app startup,
 * crashing the app immediately on launch.
 *
 * This writes a minimal compatible Exceptions.java directly into the
 * Android app source (no external download needed).
 */
const EXCEPTIONS_JAVA = `package com.arthenica.smartexception.java;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Smart Exception library replacement for ffmpeg-kit compatibility.
 */
public class Exceptions {

    public static String getStackTraceString(Throwable throwable) {
        if (throwable == null) {
            return "\\n";
        }
        try {
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            throwable.printStackTrace(printWriter);
            printWriter.close();
            return "\\n" + stringWriter.toString();
        } catch (Exception e) {
            return "\\n<error getting stack trace: " + e.getMessage() + ">";
        }
    }

    public static void registerRootPackage(String packageName) {
        // No-op for compatibility
    }
}
`;

module.exports = function withSmartExceptionFix(config) {
  return withDangerousMod(config, [
    'android',
    async (modConfig) => {
      const projectRoot = modConfig.modRequest.platformProjectRoot;
      const dir = path.join(
        projectRoot,
        'app',
        'src',
        'main',
        'java',
        'com',
        'arthenica',
        'smartexception',
        'java'
      );
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, 'Exceptions.java'), EXCEPTIONS_JAVA);
      return modConfig;
    },
  ]);
};
