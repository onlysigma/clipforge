/** Unit tests for the pure ffmpeg command builders. Run: npx tsc && node lib/ffmpeg.test.js */
import {
  buildVideoFilter,
  buildAudioFilter,
  buildSegmentCommand,
  buildConcatCommand,
  buildConcatList,
  outputSize,
  ClipInfo,
  BuildOptions,
} from './ffmpeg';

let failures = 0;
function check(name: string, cond: boolean, detail?: string) {
  if (cond) {
    console.log(`  ok: ${name}`);
  } else {
    failures++;
    console.log(`  FAIL: ${name}${detail ? ' — ' + detail : ''}`);
  }
}

const MODEL = '/data/user/0/com.clipforge.app/files/std.rnnn';
const baseOpts: BuildOptions = {
  noiseCancellation: true,
  premiumFilter: true,
  orientation: 'landscape',
  modelPath: MODEL,
};

console.log('outputSize');
check('landscape is 1920x1080', JSON.stringify(outputSize('landscape')) === '[1920,1080]');
check('portrait is 1080x1920', JSON.stringify(outputSize('portrait')) === '[1080,1920]');

console.log('buildVideoFilter');
const vf = buildVideoFilter(baseOpts);
check('has scale 1920x1080', vf.includes('scale=1920:1080'));
check('has pad', vf.includes('pad=1920:1080'));
check('has premium grade (unsharp)', vf.includes('unsharp=5:5:1.0'));
check('has premium grade (colorbalance)', vf.includes('colorbalance='));
check('ends with fps+format', vf.endsWith('fps=30,format=yuv420p'), vf);
const vfPlain = buildVideoFilter({ ...baseOpts, premiumFilter: false });
check('no grade when disabled', !vfPlain.includes('unsharp'));
const vfPort = buildVideoFilter({ ...baseOpts, orientation: 'portrait' });
check('portrait scale', vfPort.includes('scale=1080:1920'));

console.log('buildAudioFilter');
const af = buildAudioFilter(baseOpts);
check('has highpass', af.includes('highpass=f=120'));
check('has arnndn with model', af.includes(`arnndn=m=${MODEL}`), af);
check('has afftdn', af.includes('afftdn='));
check('ends with aformat stereo', af.endsWith('aformat=sample_rates=44100:channel_layouts=stereo'), af);
const afPlain = buildAudioFilter({ ...baseOpts, noiseCancellation: false });
check('no denoise when disabled', !afPlain.includes('arnndn') && !afPlain.includes('highpass'));

console.log('buildSegmentCommand');
const clip: ClipInfo = { uri: '/sdcard/clip one.mp4', duration: 23.23, width: 848, height: 478, hasAudio: true };
const segCmd = buildSegmentCommand(clip, '/cache/seg_0.mp4', baseOpts, true, false);
check('starts with ffmpeg -y -i', segCmd.startsWith('ffmpeg -y -i '));
check('quotes input with space', segCmd.includes('"/sdcard/clip one.mp4"'), segCmd);
check('has fade in', segCmd.includes('fade=t=in:st=0:d=1'));
check('no fade out', !segCmd.includes('fade=t=out'));
check('maps 0:v and 0:a', segCmd.includes('-map 0:v -map 0:a'));
check('libx264 veryfast crf 20', segCmd.includes('-c:v libx264 -preset veryfast -crf 20'));
check('aac audio', segCmd.includes('-c:a aac'));
check('has faststart+shortest', segCmd.includes('-movflags +faststart -shortest'));

const noAudioClip: ClipInfo = { ...clip, hasAudio: false };
const segNoAudio = buildSegmentCommand(noAudioClip, '/cache/seg_1.mp4', baseOpts, false, true);
check('adds anullsrc for silent clip', segNoAudio.includes('anullsrc='), segNoAudio);
check('maps 1:a for silent clip', segNoAudio.includes('-map 0:v -map 1:a'));
check('has fade out at dur-1', segNoAudio.includes('fade=t=out:st=22.230:d=1'), segNoAudio);

console.log('buildConcatCommand / buildConcatList');
const list = buildConcatList(['/cache/seg_0.mp4', '/cache/seg_1.mp4']);
check('list format', list === "file '/cache/seg_0.mp4'\nfile '/cache/seg_1.mp4'\n", JSON.stringify(list));
const cc = buildConcatCommand('/cache/concat.txt', '/cache/final.mp4');
check('concat uses demuxer+copy', cc.includes('-f concat') && cc.includes('-c copy'), cc);

console.log(failures === 0 ? '\nALL TESTS PASSED' : `\n${failures} TESTS FAILED`);
process.exit(failures === 0 ? 0 : 1);
