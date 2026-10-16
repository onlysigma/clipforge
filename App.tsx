import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  FlatList,
  Image,
  SafeAreaView,
  StyleSheet,
  Switch,
  Text,
  TouchableOpacity,
  View,
  ActivityIndicator,
  ScrollView,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import * as SplashScreen from 'expo-splash-screen';
import * as ImagePicker from 'expo-image-picker';
import { Asset as MediaAsset, requestPermissionsAsync as requestMediaPermissions } from 'expo-media-library';
import * as Sharing from 'expo-sharing';
import {
  ClipInfo,
  BuildOptions,
  ensureModel,
  probeClip,
  buildFinal,
  cancelBuild,
  ProgressEvent,
} from './src/pipeline';

SplashScreen.preventAutoHideAsync().catch(() => {});

type Phase = 'idle' | 'preparing' | 'building' | 'done' | 'error';

const ACCENT = '#7C5CFF';
const ACCENT_DARK = '#5A3FD4';
const BG = '#0E0E1A';
const CARD = '#1A1A2E';
const TEXT = '#F2F2FA';
const MUTED = '#9A9AB5';

const LOGO = require('./assets/splash-icon.png');

export default function App() {
  const [clips, setClips] = useState<ClipInfo[]>([]);
  const [noiseCancellation, setNoiseCancellation] = useState(true);
  const [premiumFilter, setPremiumFilter] = useState(true);
  const [orientation, setOrientation] = useState<'landscape' | 'portrait'>('landscape');
  const [phase, setPhase] = useState<Phase>('idle');
  const [progress, setProgress] = useState<ProgressEvent | null>(null);
  const [statusText, setStatusText] = useState('');
  const [finalUri, setFinalUri] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const cancelledRef = useRef(false);

  useEffect(() => {
    // Warm up: make sure the noise model is on device before first build.
    ensureModel()
      .catch((e) => console.warn('Model setup will retry at build time:', e))
      .finally(() => {
        setReady(true);
        SplashScreen.hideAsync().catch(() => {});
      });
  }, []);

  const addClips = useCallback(async () => {
    try {
      const perm = await ImagePicker.requestMediaLibraryPermissionsAsync();
      if (!perm.granted) {
        Alert.alert('Permission needed', 'Please allow access to your videos to add clips.');
        return;
      }
      const result = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ['videos'],
        allowsMultipleSelection: true,
        quality: 1,
      });
      if (result.canceled) return;
      setBusy(true);
      const fresh: ClipInfo[] = [];
      for (const asset of result.assets) {
        try {
          // DIAGNOSTIC: bypass probeClip to isolate crash source
          const uri = asset.uri;
          const name = uri.split('/').pop() || 'video.mp4';
          fresh.push({ uri, name, width: 1920, height: 1080, duration: 10, size: 0 });
        } catch (e) {
          Alert.alert(
            'Unreadable clip',
            `Skipped one video: ${e instanceof Error ? e.message : 'unknown error'}`,
          );
        }
      }
      if (fresh.length > 0) setClips((prev) => [...prev, ...fresh]);
    } catch (e) {
      Alert.alert('Error', e instanceof Error ? e.message : 'Could not add clips.');
    } finally {
      setBusy(false);
    }
  }, []);

  const moveClip = useCallback((index: number, dir: -1 | 1) => {
    setClips((prev) => {
      const next = [...prev];
      const j = index + dir;
      if (j < 0 || j >= next.length) return prev;
      [next[index], next[j]] = [next[j], next[index]];
      return next;
    });
  }, []);

  const removeClip = useCallback((index: number) => {
    setClips((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const startBuild = useCallback(async () => {
    if (clips.length === 0) {
      Alert.alert('No clips', 'Add at least one video clip first.');
      return;
    }
    cancelledRef.current = false;
    setPhase('preparing');
    setStatusText('Preparing noise cancellation model…');
    setFinalUri(null);
    try {
      const modelPath = await ensureModel();
      const opts: BuildOptions = { noiseCancellation, premiumFilter, orientation, modelPath };
      setPhase('building');
      const uri = await buildFinal(clips, opts, (p) => {
        setProgress(p);
        if (p.phase === 'encoding') {
          setStatusText(`Clip ${p.clipIndex + 1} of ${p.clipCount} — ${Math.round(p.fraction * 100)}%`);
        } else {
          setStatusText('Joining clips…');
        }
      });
      if (cancelledRef.current) {
        setPhase('idle');
        setStatusText('Cancelled.');
        return;
      }
      setFinalUri(uri);
      setPhase('done');
      setStatusText('Final video is ready!');
    } catch (e) {
      if (cancelledRef.current) {
        setPhase('idle');
        setStatusText('Cancelled.');
      } else {
        setPhase('error');
        setStatusText(e instanceof Error ? e.message : 'Build failed.');
      }
    }
  }, [clips, noiseCancellation, premiumFilter, orientation]);

  const onCancel = useCallback(() => {
    cancelledRef.current = true;
    cancelBuild();
  }, []);

  const saveToGallery = useCallback(async () => {
    if (!finalUri) return;
    try {
      const { status } = await requestMediaPermissions();
      if (status !== 'granted') {
        Alert.alert('Permission needed', 'Allow gallery access to save the video.');
        return;
      }
      await MediaAsset.create(finalUri);
      Alert.alert('Saved', 'Final video saved to your gallery.');
    } catch (e) {
      Alert.alert('Error', e instanceof Error ? e.message : 'Could not save.');
    }
  }, [finalUri]);

  const shareVideo = useCallback(async () => {
    if (!finalUri) return;
    try {
      if (!(await Sharing.isAvailableAsync())) {
        Alert.alert('Not available', 'Sharing is not available on this device.');
        return;
      }
      await Sharing.shareAsync(finalUri);
    } catch (e) {
      Alert.alert('Error', e instanceof Error ? e.message : 'Could not share.');
    }
  }, [finalUri]);

  const totalDuration = clips.reduce((s, c) => s + c.duration, 0);
  const building = phase === 'preparing' || phase === 'building';

  const renderClip = ({ item, index }: { item: ClipInfo; index: number }) => (
    <View style={styles.clipRow}>
      <View style={styles.clipBadge}>
        <Text style={styles.clipBadgeText}>{index + 1}</Text>
      </View>
      <View style={styles.clipInfo}>
        <Text style={styles.clipTitle}>Scene {index + 1}</Text>
        <Text style={styles.clipSub}>
          {item.duration.toFixed(1)}s · {item.width}×{item.height}
          {item.hasAudio ? '' : ' · no audio'}
        </Text>
      </View>
      <View style={styles.clipActions}>
        <TouchableOpacity
          style={[styles.iconBtn, (index === 0 || building) && styles.iconBtnDisabled]}
          onPress={() => moveClip(index, -1)}
          disabled={index === 0 || building}
        >
          <Text style={styles.iconBtnText}>↑</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.iconBtn, (index === clips.length - 1 || building) && styles.iconBtnDisabled]}
          onPress={() => moveClip(index, 1)}
          disabled={index === clips.length - 1 || building}
        >
          <Text style={styles.iconBtnText}>↓</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.iconBtn, styles.dangerBtn, building && styles.iconBtnDisabled]}
          onPress={() => removeClip(index)}
          disabled={building}
        >
          <Text style={styles.iconBtnText}>✕</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

  if (!ready) return null;

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar style="light" />
      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <View style={styles.header}>
          <Image source={LOGO} style={styles.logo} />
          <View>
            <Text style={styles.title}>ClipForge</Text>
            <Text style={styles.subtitle}>Noise-free · Premium look · 1080p</Text>
          </View>
        </View>

        <TouchableOpacity
          style={[styles.addBtn, (busy || building) && styles.btnDisabled]}
          onPress={addClips}
          disabled={busy || building}
          activeOpacity={0.8}
        >
          <Text style={styles.addBtnText}>{busy ? 'Reading clips…' : '＋  Add Clips'}</Text>
        </TouchableOpacity>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Scenes ({clips.length})</Text>
          {clips.length === 0 ? (
            <Text style={styles.empty}>No clips yet — add videos in scene order.</Text>
          ) : (
            <FlatList
              data={clips}
              keyExtractor={(item, i) => `${item.uri}-${i}`}
              renderItem={renderClip}
              scrollEnabled={false}
            />
          )}
          {clips.length > 0 && (
            <Text style={styles.total}>
              Total: {totalDuration.toFixed(1)}s across {clips.length} clip{clips.length > 1 ? 's' : ''}
            </Text>
          )}
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>Enhancements</Text>
          <View style={styles.optionRow}>
            <View style={styles.optionTextWrap}>
              <Text style={styles.optionLabel}>Noise cancellation</Text>
              <Text style={styles.optionHint}>Keep speaker voice, remove background</Text>
            </View>
            <Switch
              value={noiseCancellation}
              onValueChange={setNoiseCancellation}
              disabled={building}
              trackColor={{ false: '#3A3A55', true: ACCENT }}
              thumbColor="#fff"
            />
          </View>
          <View style={styles.divider} />
          <View style={styles.optionRow}>
            <View style={styles.optionTextWrap}>
              <Text style={styles.optionLabel}>Premium filter</Text>
              <Text style={styles.optionHint}>CapCut-style color & clarity</Text>
            </View>
            <Switch
              value={premiumFilter}
              onValueChange={setPremiumFilter}
              disabled={building}
              trackColor={{ false: '#3A3A55', true: ACCENT }}
              thumbColor="#fff"
            />
          </View>
          <View style={styles.divider} />
          <View style={styles.optionRow}>
            <View style={styles.optionTextWrap}>
              <Text style={styles.optionLabel}>Output format</Text>
              <Text style={styles.optionHint}>1080p final video</Text>
            </View>
            <View style={styles.segRow}>
              <TouchableOpacity
                style={[styles.segBtn, orientation === 'landscape' && styles.segActive]}
                onPress={() => setOrientation('landscape')}
                disabled={building}
              >
                <Text style={orientation === 'landscape' ? styles.segActiveText : styles.segText}>16:9</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.segBtn, orientation === 'portrait' && styles.segActive]}
                onPress={() => setOrientation('portrait')}
                disabled={building}
              >
                <Text style={orientation === 'portrait' ? styles.segActiveText : styles.segText}>9:16</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>

        {building ? (
          <View style={styles.card}>
            <ActivityIndicator size="large" color={ACCENT} />
            <Text style={styles.status}>{statusText}</Text>
            {progress && progress.phase === 'encoding' && (
              <View style={styles.barOuter}>
                <View style={[styles.barInner, { width: `${Math.round(progress.fraction * 100)}%` }]} />
              </View>
            )}
            <TouchableOpacity style={styles.cancelBtn} onPress={onCancel} activeOpacity={0.8}>
              <Text style={styles.cancelBtnText}>Cancel</Text>
            </TouchableOpacity>
          </View>
        ) : (
          <TouchableOpacity
            style={[styles.buildBtn, clips.length === 0 && styles.btnDisabled]}
            onPress={startBuild}
            disabled={clips.length === 0}
            activeOpacity={0.85}
          >
            <Text style={styles.buildBtnText}>🎬  Build Final Video</Text>
          </TouchableOpacity>
        )}

        {statusText !== '' && !building && (
          <Text style={[styles.status, phase === 'error' && styles.errorText]}>{statusText}</Text>
        )}

        {phase === 'done' && finalUri && (
          <View style={styles.row}>
            <TouchableOpacity style={[styles.halfBtn, styles.saveBtn]} onPress={saveToGallery} activeOpacity={0.85}>
              <Text style={styles.halfBtnText}>💾 Save</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.halfBtn, styles.shareBtn]} onPress={shareVideo} activeOpacity={0.85}>
              <Text style={styles.halfBtnText}>📤 Share</Text>
            </TouchableOpacity>
          </View>
        )}
        <View style={{ height: 24 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: BG },
  scroll: { padding: 16 },
  header: { flexDirection: 'row', alignItems: 'center', marginBottom: 18, marginTop: 4 },
  logo: { width: 52, height: 52, borderRadius: 14, marginRight: 12 },
  title: { fontSize: 26, fontWeight: '800', color: TEXT, letterSpacing: 0.5 },
  subtitle: { fontSize: 12, color: MUTED, marginTop: 2 },
  addBtn: {
    backgroundColor: CARD,
    borderWidth: 1.5,
    borderColor: ACCENT,
    borderStyle: 'dashed',
    borderRadius: 14,
    paddingVertical: 16,
    alignItems: 'center',
    marginBottom: 14,
  },
  addBtnText: { color: ACCENT, fontSize: 16, fontWeight: '700' },
  card: {
    backgroundColor: CARD,
    borderRadius: 16,
    padding: 14,
    marginBottom: 14,
  },
  cardTitle: { color: TEXT, fontSize: 15, fontWeight: '700', marginBottom: 10 },
  empty: { color: MUTED, fontSize: 13, textAlign: 'center', paddingVertical: 10 },
  total: { color: MUTED, fontSize: 12, textAlign: 'center', marginTop: 8 },
  clipRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#23233D',
    borderRadius: 12,
    padding: 10,
    marginVertical: 4,
  },
  clipBadge: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: ACCENT,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 10,
  },
  clipBadgeText: { color: '#fff', fontWeight: '800', fontSize: 14 },
  clipInfo: { flex: 1 },
  clipTitle: { color: TEXT, fontSize: 14, fontWeight: '700' },
  clipSub: { color: MUTED, fontSize: 11, marginTop: 2 },
  clipActions: { flexDirection: 'row', gap: 6 },
  iconBtn: {
    width: 34,
    height: 34,
    borderRadius: 10,
    backgroundColor: '#2E2E4D',
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconBtnDisabled: { opacity: 0.35 },
  iconBtnText: { color: TEXT, fontSize: 15, fontWeight: '700' },
  dangerBtn: { backgroundColor: '#5A2333' },
  optionRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 8 },
  optionTextWrap: { flex: 1, marginRight: 10 },
  optionLabel: { color: TEXT, fontSize: 14, fontWeight: '600' },
  optionHint: { color: MUTED, fontSize: 11, marginTop: 2 },
  divider: { height: 1, backgroundColor: '#2A2A45' },
  segRow: { flexDirection: 'row', gap: 8 },
  segBtn: { paddingHorizontal: 16, paddingVertical: 9, borderRadius: 10, backgroundColor: '#2E2E4D' },
  segActive: { backgroundColor: ACCENT },
  segText: { color: MUTED, fontSize: 13, fontWeight: '600' },
  segActiveText: { color: '#fff', fontSize: 13, fontWeight: '800' },
  buildBtn: {
    backgroundColor: ACCENT,
    borderRadius: 16,
    paddingVertical: 17,
    alignItems: 'center',
    marginBottom: 6,
    shadowColor: ACCENT,
    shadowOpacity: 0.4,
    shadowRadius: 12,
    elevation: 6,
  },
  buildBtnText: { color: '#fff', fontSize: 17, fontWeight: '800' },
  btnDisabled: { opacity: 0.4 },
  cancelBtn: {
    backgroundColor: '#5A2333',
    borderRadius: 12,
    paddingVertical: 12,
    alignItems: 'center',
    marginTop: 10,
  },
  cancelBtnText: { color: '#FF8FA3', fontSize: 14, fontWeight: '700' },
  status: { color: TEXT, textAlign: 'center', marginVertical: 10, fontSize: 13 },
  errorText: { color: '#FF8FA3' },
  barOuter: { height: 10, backgroundColor: '#2E2E4D', borderRadius: 5, overflow: 'hidden', marginVertical: 6 },
  barInner: { height: 10, backgroundColor: ACCENT, borderRadius: 5 },
  row: { flexDirection: 'row', gap: 10, marginTop: 4 },
  halfBtn: { flex: 1, borderRadius: 14, paddingVertical: 14, alignItems: 'center' },
  saveBtn: { backgroundColor: '#1F6F43' },
  shareBtn: { backgroundColor: ACCENT_DARK },
  halfBtnText: { color: '#fff', fontSize: 15, fontWeight: '700' },
});
