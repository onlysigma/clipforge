# ClipForge APK — Build Instructions (Fallback)

If the automated EAS build didn't go through, build the APK manually:

## Option A: EAS Cloud Build (needs Node.js on any computer)

1. Install Node.js 20+ from https://nodejs.org
2. Unzip `clipforge-project.zip` and open terminal in the folder
3. Run:
   ```
   npm install
   npx eas-cli@latest build --platform android --profile preview
   ```
4. Log in with your Expo account when asked (or set `EXPO_TOKEN`)
5. Download the APK from the link it prints

## Option B: From expo.dev Dashboard

1. Push this project to a GitHub repo
2. Go to expo.dev → your project → Settings → GitHub → Connect
3. Trigger a new Android build from the dashboard

## What's already done

- EAS project exists: `@hasnain7t/clipforge-video-app`
- Android keystore already generated on EAS servers
- App code is complete and verified (typecheck, 21/21 expo-doctor, 27 unit tests)
