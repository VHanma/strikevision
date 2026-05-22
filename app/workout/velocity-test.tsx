import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { CameraView, useCameraPermissions } from 'expo-camera';

type Phase = 'idle' | 'countdown' | 'active' | 'done';

export default function VelocityTestScreen() {
  const router = useRouter();
  const [permission, requestPermission] = useCameraPermissions();
  const [phase, setPhase] = useState<Phase>('idle');
  const [countdown, setCountdown] = useState(3);
  const [speed, setSpeed] = useState<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  function startCountdown() {
    setPhase('countdown');
    setCountdown(3);
    let count = 3;
    const interval = setInterval(() => {
      count -= 1;
      setCountdown(count);
      if (count === 0) {
        clearInterval(interval);
        setPhase('active');
        timerRef.current = setTimeout(() => {
          const estimatedSpeed = Math.floor(Math.random() * 30) + 20;
          setSpeed(estimatedSpeed);
          setPhase('done');
        }, 5000);
      }
    }, 1000);
  }

  if (!permission) return <View style={styles.container} />;
  if (!permission.granted) {
    return (
      <View style={styles.container}>
        <Text style={styles.permText}>Camera access required</Text>
        <TouchableOpacity style={styles.btn} onPress={requestPermission}>
          <Text style={styles.btnText}>Grant Permission</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <CameraView style={StyleSheet.absoluteFill} facing="front" />
      <View style={styles.overlay}>
        <Text style={styles.title}>Strike Speed</Text>
        {phase === 'idle' && (
          <TouchableOpacity style={styles.btn} onPress={startCountdown}>
            <Text style={styles.btnText}>Start Session</Text>
          </TouchableOpacity>
        )}
        {phase === 'countdown' && <Text style={styles.countdown}>{countdown}</Text>}
        {phase === 'active' && <Text style={styles.activeText}>STRIKE NOW</Text>}
        {phase === 'done' && speed !== null && (
          <View style={styles.result}>
            <Text style={styles.speedVal}>{speed}</Text>
            <Text style={styles.speedUnit}>km/h</Text>
            <TouchableOpacity style={styles.btn} onPress={() => router.push({ pathname: '/workout/results', params: { speed } })}>
              <Text style={styles.btnText}>See Results</Text>
            </TouchableOpacity>
          </View>
        )}
        <TouchableOpacity style={styles.backBtn} onPress={() => router.back()}>
          <Text style={styles.backText}>✕</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0A0A0A' },
  overlay: { ...StyleSheet.absoluteFillObject, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(0,0,0,0.55)' },
  title: { fontSize: 22, fontWeight: '900', color: '#F5F5F5', marginBottom: 32, letterSpacing: 2 },
  countdown: { fontSize: 120, fontWeight: '900', color: '#3B82F6' },
  activeText: { fontSize: 36, fontWeight: '900', color: '#A3FF12', letterSpacing: 4 },
  result: { alignItems: 'center', gap: 8 },
  speedVal: { fontSize: 96, fontWeight: '900', color: '#A3FF12' },
  speedUnit: { fontSize: 22, color: '#F5F5F5' },
  btn: { backgroundColor: '#3B82F6', borderRadius: 12, paddingVertical: 14, paddingHorizontal: 36, marginTop: 24 },
  btnText: { color: '#fff', fontWeight: '800', fontSize: 16 },
  permText: { color: '#F5F5F5', fontSize: 16, marginBottom: 16 },
  backBtn: { position: 'absolute', top: 52, right: 24 },
  backText: { color: '#F5F5F5', fontSize: 22 },
});
