import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';

const DRILLS = ['JAB', 'CROSS', 'JAB-CROSS', 'JAB-CROSS-HOOK'];
const ROUND_DURATION = 30;

export default function LiveSessionScreen() {
  const router = useRouter();
  const [active, setActive] = useState(false);
  const [timeLeft, setTimeLeft] = useState(ROUND_DURATION);
  const [drillIndex, setDrillIndex] = useState(0);
  const [round, setRound] = useState(1);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!active) return;
    timerRef.current = setInterval(() => {
      setTimeLeft((t) => {
        if (t <= 1) {
          clearInterval(timerRef.current!);
          router.push('/workout/results');
          return 0;
        }
        if (t % 8 === 0) setDrillIndex((i) => (i + 1) % DRILLS.length);
        return t - 1;
      });
    }, 1000);
    return () => clearInterval(timerRef.current!);
  }, [active]);

  return (
    <View style={styles.container}>
      <Text style={styles.round}>Round {round}</Text>
      <Text style={styles.timer}>{timeLeft}</Text>
      <Text style={styles.drill}>{DRILLS[drillIndex]}</Text>
      {!active ? (
        <TouchableOpacity style={styles.btn} onPress={() => setActive(true)}>
          <Text style={styles.btnText}>Start Round</Text>
        </TouchableOpacity>
      ) : (
        <TouchableOpacity style={styles.stopBtn} onPress={() => { clearInterval(timerRef.current!); router.push('/workout/results'); }}>
          <Text style={styles.btnText}>End Session</Text>
        </TouchableOpacity>
      )}
      <TouchableOpacity style={styles.backBtn} onPress={() => router.back()}>
        <Text style={styles.backText}>✕</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0A0A0A', alignItems: 'center', justifyContent: 'center' },
  round: { color: '#555', fontSize: 16, letterSpacing: 2, marginBottom: 8 },
  timer: { fontSize: 120, fontWeight: '900', color: '#F5F5F5' },
  drill: { fontSize: 32, fontWeight: '900', color: '#A3FF12', letterSpacing: 4, marginVertical: 24 },
  btn: { backgroundColor: '#3B82F6', borderRadius: 12, paddingVertical: 14, paddingHorizontal: 48 },
  stopBtn: { backgroundColor: '#EF4444', borderRadius: 12, paddingVertical: 14, paddingHorizontal: 48 },
  btnText: { color: '#fff', fontWeight: '800', fontSize: 16 },
  backBtn: { position: 'absolute', top: 52, right: 24 },
  backText: { color: '#F5F5F5', fontSize: 22 },
});
