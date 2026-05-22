import { View, Text, TouchableOpacity, ScrollView, StyleSheet } from 'react-native';
import { useRouter } from 'expo-router';
import { LinearGradient } from 'expo-linear-gradient';

const MODES = [
  { label: 'Strike Speed', sub: 'Test your punch velocity', route: '/workout/velocity-test', color: '#3B82F6' },
  { label: 'Form Check', sub: 'Record & review your technique', route: '/workout/strike-form', color: '#A3FF12' },
  { label: 'Fight Camp', sub: 'Timed round drills', route: '/workout/live-session', color: '#F59E0B' },
];

export default function HomeScreen() {
  const router = useRouter();

  return (
    <View style={styles.container}>
      <Text style={styles.logo}>STRIKE<Text style={{ color: '#3B82F6' }}>VISION</Text></Text>
      <Text style={styles.sub}>Choose your session</Text>
      <ScrollView contentContainerStyle={styles.cards}>
        {MODES.map((m) => (
          <TouchableOpacity key={m.label} style={styles.card} onPress={() => router.push(m.route as any)}>
            <LinearGradient colors={['#171717', '#0A0A0A']} style={styles.cardInner}>
              <View style={[styles.accent, { backgroundColor: m.color }]} />
              <Text style={styles.cardTitle}>{m.label}</Text>
              <Text style={styles.cardSub}>{m.sub}</Text>
            </LinearGradient>
          </TouchableOpacity>
        ))}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0A0A0A', paddingTop: 64, paddingHorizontal: 20 },
  logo: { fontSize: 28, fontWeight: '900', color: '#F5F5F5', letterSpacing: 2 },
  sub: { color: '#555', marginTop: 4, marginBottom: 24 },
  cards: { gap: 16 },
  card: { borderRadius: 12, overflow: 'hidden' },
  cardInner: { padding: 20, borderRadius: 12, borderWidth: 1, borderColor: '#222' },
  accent: { width: 32, height: 4, borderRadius: 2, marginBottom: 12 },
  cardTitle: { fontSize: 18, fontWeight: '700', color: '#F5F5F5' },
  cardSub: { color: '#888', marginTop: 4 },
});
