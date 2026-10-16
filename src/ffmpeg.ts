/**
 * Pure ffmpeg command builders for ClipForge.
 * These are intentionally side-effect free so they can be unit tested.
 * The filter chains mirror the proven desktop pipeline:
 *   video: scale/pad -> premium grade (unsharp+curves+colorbalance+hue) -> fps/format
 *   audio: highpass -> arnndn (RNNoise) -> afftdn -> loudness normalize
 */

export interface ClipInfo {
  uri: string;
  duration: number; // seconds
  width: number;
  height: number;
  hasAudio: boolean;
}

export interface BuildOptions {
  noiseCancellation: boolean;
  premiumFilter: boolean;
  orientation: 'landscape' | 'portrait';
  /** absolute path to the RNNoise .rnnn model file on device */
  modelPath: string;
}

export function outputSize(orientation: 'landscape' | 'portrait'): [number, number] {
  return orientation === 'landscape' ? [1920, 1080] : [1080, 1920];
}

/** Premium "CapCut-style" food grade. NOTE: 'eq' filter not in this FFmpeg build — use hue for saturation. */
export const PREMIUM_GRADE =
  "unsharp=5:5:1.0:5:5:0.0," +
  "curves=all='0/0 0.5/0.56 1/1'," +
  "colorbalance=rs=0.08:gs=0.02:bs=-0.06:rm=0.05:gm=0.01:bm=-0.04," +
  "hue=s=1.28";

export function buildVideoFilter(opts: BuildOptions): string {
  const [w, h] = outputSize(opts.orientation);
  let vf =
    `scale=${w}:${h}:force_original_aspect_ratio=decrease` +
    `,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black`;
  if (opts.premiumFilter) vf += `,${PREMIUM_GRADE}`;
  vf += `,fps=30,format=yuv420p`;
  return vf;
}

export function buildAudioFilter(opts: BuildOptions): string {
  let af = '';
  if (opts.noiseCancellation) {
    // RNNoise keeps the foreground voice, kills bg voices + hum; afftdn polishes.
    af += `highpass=f=120,arnndn=m=${toFsPath(opts.modelPath)},afftdn=nr=6:nf=-40,`;
  }
  af += `aresample=44100,aformat=sample_rates=44100:channel_layouts=stereo`;
  return af;
}

/** Strip file:// prefix — FFmpeg needs plain filesystem paths, not URIs. */
export function toFsPath(uri: string): string {
  return uri.startsWith('file://') ? uri.slice(7) : uri;
}

/**
 * Builds a shell-safe ffmpeg command for one segment.
 * Video is re-encoded (filter + 1080p), audio is rebuilt from the source.
 * Clips without an audio track get generated silence so concat stays uniform.
 */
export function buildSegmentCommand(
  clip: ClipInfo,
  outputUri: string,
  opts: BuildOptions,
  fadeIn: boolean,
  fadeOut: boolean,
): string {
  const vfBase = buildVideoFilter(opts);
  const afBase = buildAudioFilter(opts);

  let vf = vfBase;
  let af = afBase;
  if (fadeIn) {
    vf += `,fade=t=in:st=0:d=1`;
    af += `,afade=t=in:st=0:d=1`;
  }
  if (fadeOut) {
    const st = Math.max(0, clip.duration - 1).toFixed(3);
    vf += `,fade=t=out:st=${st}:d=1`;
    af += `,afade=t=out:st=${st}:d=1`;
  }

  const q = (s: string) => `"${s}"`;
  // NOTE: FFmpegKit.executeAsync() takes arguments WITHOUT the 'ffmpeg' executable prefix.
  // Use plain filesystem paths (not file:// URIs) — FFmpeg can't parse URIs.
  let cmd = `-y -i ${q(toFsPath(clip.uri))} `;
  let audioMap = '0:a';
  if (!clip.hasAudio) {
    // silent stereo track matching the clip length
    cmd += `-f lavfi -i ${q(`anullsrc=r=44100:cl=stereo:d=${clip.duration.toFixed(2)}`)} `;
    audioMap = '1:a';
  }
  cmd +=
    `-vf ${q(vf)} -af ${q(af)} ` +
    `-map 0:v -map ${audioMap} ` +
    `-c:v h264_mediacodec -b:v 10M ` +
    `-c:a aac -b:a 128k -ar 44100 -ac 2 ` +
    `-movflags +faststart -shortest ${q(toFsPath(outputUri))}`;
  return cmd;
}

/** Concat demuxer command (stream copy — all segments share identical params). */
export function buildConcatCommand(listFileUri: string, outputUri: string): string {
  // NOTE: no 'ffmpeg' prefix — FFmpegKit.executeAsync() takes raw arguments.
  return `-y -f concat -safe 0 -i "${toFsPath(listFileUri)}" -c copy -movflags +faststart "${toFsPath(outputUri)}"`;
}

/** Contents of the concat list file. */
export function buildConcatList(segmentUris: string[]): string {
  return segmentUris.map((u) => `file '${toFsPath(u).replace(/'/g, "'\\''")}'`).join('\n') + '\n';
}
