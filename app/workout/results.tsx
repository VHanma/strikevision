import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { useRouter, useLocalSearchParams } from 'expo-router';

export default function ResultsScreen() {
  const router = useRouter();
  const { speed } = useLocalSearchParams<{ speed?: string }>();

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Session Complete</Text>
      {speed && (
        <View style={styles.statCard}>
          <Text style={styles.statVal}>{speed}</Text>
          <Text style={styles.statUnit}>km/h</Text>
          <Text style={styles.statLabel}>Strike Speed</Text>
        </View>
      )}
      <Text style={styles.feedback}>Good session. Keep working on your form and consistency.</Text>
      <TouchableOpacity style={styles.btn} onPress={() => router.replace('/(tabs)')}>
        <Text style={styles.btnText}>Back to Home</Text>
      </TouchableOpacity>
      <TouchableOpacity style={styles.secondaryBtn} onPress={() => router.replace('/(tabs)/progress')}>
        <Text style={styles.secondaryText}>View Progress</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0A0A0A', alignItems: 'center', justifyContent: 'center', padding: 28 },
  title: { fontSize: 26, fontWeight: '900', color: '#F5F5F5', marginBottom: 32, letterSpacing: 1 },
  statCard: { backgroundColor: '#171717', borderRadius: 16, padding: 32, alignItems: 'center', borderWidth: 1, borderColor: '#222', marginBottom: 24 },
  statVal: { fontSize: 80, fontWeight: '900', color: '#A3FF12' },
  statUnit: { fontSize: 20, color: '#F5F5F5' },
  statLabel: { color: '#888', marginTop: 4 },
  feedback: { color: '#888', textAlign: 'center', marginBottom: 32, lineHeight: 22 },
  btn: { backgroundColor: '#3B82F6', borderRadius: 12, paddingVertical: 14, paddingHorizontal: 48, width: '100%', alignItems: 'center' },
  btnText: { color: '#fff', fontWeight: '800', fontSize: 16 },
  secondaryBtn: { marginTop: 12, padding: 12 },
  secondaryText: { color: '#555' },
});
