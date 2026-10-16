/**
 * ClipForge processing pipeline.
 * Probes clips, ensures the RNNoise model is on device, encodes each
 * segment with the ffmpeg command builders, then concatenates.
 *
 * NOTE: react-native-ffmpeg-kit is loaded lazily (not at import time)
 * because its JS wrapper creates a NativeEventEmitter at module scope,
 * which throws on startup if the native module is missing. Lazy loading
 * keeps the app launchable and surfaces a friendly error instead.
 */
import { File, Directory, Paths } from 'expo-file-system';
import { Asset } from 'expo-asset';
import {
  ClipInfo,
  BuildOptions,
  buildSegmentCommand,
  buildConcatCommand,
  buildConcatList,
} from './ffmpeg';

export { ClipInfo, BuildOptions };

type FFmpegKitModule = typeof import('react-native-ffmpeg-kit');

let cachedKit: FFmpegKitModule | null = null;

/** Lazily load the native video engine. Throws a friendly error if unavailable. */
function getKit(): FFmpegKitModule {
  if (cachedKit) return cachedKit;
  let mod: FFmpegKitModule;
  try {
    mod = require('react-native-ffmpeg-kit');
  } catch (e) {
    throw new Error(
      'Video engine failed to start. Please reinstall ClipForge and try again.' +
        (e instanceof Error ? ` (${e.message})` : ''),
    );
  }
  if (!mod || !mod.FFmpegKit || !mod.FFprobeKit) {
    throw new Error('Video engine is not available on this device. Please reinstall ClipForge.');
  }
  cachedKit = mod;
  return mod;
}

const MODEL_ASSET = require('../assets/models/std.rnnn');
const MODEL_NAME = 'std.rnnn';

/** Copy the bundled RNNoise model to app storage on first run. Returns its absolute path. */
export async function ensureModel(): Promise<string> {
  const dest = new File(Paths.document, MODEL_NAME);
  if (!dest.exists) {
    const asset = Asset.fromModule(MODEL_ASSET);
    await asset.downloadAsync();
    if (!asset.localUri) throw new Error('Noise model asset could not be loaded.');
    const src = new File(asset.localUri);
    await src.copy(dest);
  }
  if (!dest.exists || (dest.size ?? 0) < 100000) {
    throw new Error('Noise model file is missing or corrupt. Reinstall the app.');
  }
  return dest.uri;
}

export async function probeClip(uri: string): Promise<ClipInfo> {
  const { FFprobeKit } = getKit();
  const session = await FFprobeKit.getMediaInformation(uri);
  const info = session.getMediaInformation();
  if (!info) throw new Error('Could not read this video file.');
  // NOTE: getDuration() is typed as number but ACTUALLY returns a string at runtime
  // (library type definition bug). Must parse to number or .toFixed() crashes.
  const duration = parseFloat(String(info.getDuration() ?? '0'));
  if (!duration || duration <= 0) throw new Error('Could not read video duration.');
  const streams = info.getStreams() ?? [];
  const video = streams.find((s) => s.getType() === 'video');
  if (!video) throw new Error('No video stream found in this file.');
  const audio = streams.find((s) => s.getType() === 'audio');
  return {
    uri,
    duration,
    width: video.getWidth() ?? 0,
    height: video.getHeight() ?? 0,
    hasAudio: !!audio,
  };
}

function workDir(): Directory {
  const dir = new Directory(Paths.cache, 'clipforge');
  if (!dir.exists) dir.create();
  return dir;
}

export interface ProgressEvent {
  clipIndex: number;
  clipCount: number;
  /** 0..1 within the current clip */
  fraction: number;
  phase: 'encoding' | 'joining';
}

export async function buildFinal(
  clips: ClipInfo[],
  opts: BuildOptions,
  onProgress: (p: ProgressEvent) => void,
): Promise<string> {
  if (clips.length === 0) throw new Error('Add at least one clip first.');
  const dir = workDir();
  const segmentUris: string[] = [];

  for (let i = 0; i < clips.length; i++) {
    const clip = clips[i];
    const outFile = new File(dir.uri, `seg_${i}.mp4`);
    if (outFile.exists) outFile.delete();

    const { FFmpegKit, FFmpegKitConfig, ReturnCode } = getKit();
    const cmd = buildSegmentCommand(clip, outFile.uri, opts, i === 0, i === clips.length - 1);

    FFmpegKitConfig.enableStatisticsCallback((stats) => {
      const t = stats.getTime() / 1000;
      onProgress({
        clipIndex: i,
        clipCount: clips.length,
        fraction: Math.min(1, Math.max(0, t / clip.duration)),
        phase: 'encoding',
      });
    });
    try {
      const session = await FFmpegKit.executeAsync(cmd);
      const rc = await session.getReturnCode();
      if (!ReturnCode.isSuccess(rc)) {
        const logs = await session.getAllLogsAsString();
        throw new Error(`Clip ${i + 1} failed to encode (${String(rc)}). ${logs.slice(-300)}`);
      }
    } finally {
      FFmpegKitConfig.disableStatistics();
    }
    segmentUris.push(outFile.uri);
  }

  onProgress({ clipIndex: clips.length, clipCount: clips.length, fraction: 1, phase: 'joining' });
  const listFile = new File(dir.uri, 'concat.txt');
  if (listFile.exists) listFile.delete();
  listFile.create();
  listFile.write(buildConcatList(segmentUris));

  const finalFile = new File(dir.uri, `clipforge_final_${Date.now()}.mp4`);
  if (finalFile.exists) finalFile.delete();
  const { FFmpegKit: Kit2, ReturnCode: RC2 } = getKit();
  const concatSession = await Kit2.executeAsync(
    buildConcatCommand(listFile.uri, finalFile.uri),
  );
  const concatRc = await concatSession.getReturnCode();
  if (!RC2.isSuccess(concatRc)) {
    throw new Error('Could not join the clips together.');
  }
  if (!finalFile.exists || (finalFile.size ?? 0) < 50000) {
    throw new Error('Final video file was not created correctly.');
  }
  return finalFile.uri;
}

/** Cancel any running ffmpeg task (wired to the Cancel button). */
export function cancelBuild(): void {
  try {
    getKit().FFmpegKit.cancel();
  } catch {
    // engine not loaded — nothing to cancel
  }
}
