import { View, Text, ScrollView, StyleSheet } from 'react-native';

const STATS = [
  { label: 'Best Speed', value: '—', unit: 'km/h' },
  { label: 'Avg Speed', value: '—', unit: 'km/h' },
  { label: 'Total Sessions', value: '0', unit: '' },
  { label: 'Current Streak', value: '0', unit: 'days' },
];

export default function ProgressScreen() {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>Progress</Text>
      <ScrollView contentContainerStyle={styles.grid}>
        {STATS.map((s) => (
          <View key={s.label} style={styles.card}>
            <Text style={styles.value}>{s.value}<Text style={styles.unit}> {s.unit}</Text></Text>
            <Text style={styles.label}>{s.label}</Text>
          </View>
        ))}
      </ScrollView>
      <Text style={styles.empty}>Complete your first session to see stats here.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0A0A0A', paddingTop: 64, paddingHorizontal: 20 },
  title: { fontSize: 24, fontWeight: '800', color: '#F5F5F5', marginBottom: 24 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  card: { width: '47%', backgroundColor: '#171717', borderRadius: 12, padding: 16, borderWidth: 1, borderColor: '#222' },
  value: { fontSize: 28, fontWeight: '900', color: '#A3FF12' },
  unit: { fontSize: 14, color: '#555', fontWeight: '400' },
  label: { color: '#888', marginTop: 4, fontSize: 12 },
  empty: { color: '#444', textAlign: 'center', marginTop: 32 },
});
