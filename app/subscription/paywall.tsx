import { View, Text, TouchableOpacity, StyleSheet, ScrollView } from 'react-native';
import { useRouter } from 'expo-router';
import { LinearGradient } from 'expo-linear-gradient';

const PERKS = [
  'Unlimited session history',
  'Advanced speed analytics',
  'Premium drill library',
  'AI coaching feedback',
  'Coach Chat access',
];

export default function PaywallScreen() {
  const router = useRouter();

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.inner}>
      <LinearGradient colors={['#1E3A5F', '#0A0A0A']} style={styles.hero}>
        <Text style={styles.badge}>PRO</Text>
        <Text style={styles.heroTitle}>Unlock StrikeVision Pro</Text>
        <Text style={styles.heroSub}>Train smarter. Hit harder.</Text>
      </LinearGradient>
      <View style={styles.perks}>
        {PERKS.map((p) => (
          <View key={p} style={styles.perkRow}>
            <Text style={styles.check}>✓</Text>
            <Text style={styles.perkText}>{p}</Text>
          </View>
        ))}
      </View>
      <TouchableOpacity style={styles.btn}>
        <Text style={styles.btnText}>Start 7-Day Free Trial</Text>
        <Text style={styles.btnSub}>then $9.99/month</Text>
      </TouchableOpacity>
      <TouchableOpacity style={styles.skipBtn} onPress={() => router.back()}>
        <Text style={styles.skipText}>Not now</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0A0A0A' },
  inner: { paddingBottom: 48 },
  hero: { padding: 40, alignItems: 'center' },
  badge: { backgroundColor: '#3B82F6', color: '#fff', fontWeight: '900', paddingHorizontal: 16, paddingVertical: 4, borderRadius: 20, marginBottom: 16, fontSize: 13, letterSpacing: 2 },
  heroTitle: { fontSize: 28, fontWeight: '900', color: '#F5F5F5', textAlign: 'center' },
  heroSub: { color: '#A3FF12', marginTop: 8, fontSize: 16 },
  perks: { padding: 28 },
  perkRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 16 },
  check: { color: '#A3FF12', fontSize: 18, fontWeight: '900', marginRight: 12 },
  perkText: { color: '#F5F5F5', fontSize: 15 },
  btn: { marginHorizontal: 28, backgroundColor: '#A3FF12', borderRadius: 14, paddingVertical: 16, alignItems: 'center' },
  btnText: { color: '#0A0A0A', fontWeight: '900', fontSize: 17 },
  btnSub: { color: '#0A0A0A', fontSize: 12, marginTop: 2, opacity: 0.7 },
  skipBtn: { alignItems: 'center', marginTop: 16 },
  skipText: { color: '#555' },
});
