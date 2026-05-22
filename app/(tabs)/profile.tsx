import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { useRouter } from 'expo-router';

export default function ProfileScreen() {
  const router = useRouter();

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Profile</Text>
      <View style={styles.avatar}>
        <Text style={styles.avatarText}>SV</Text>
      </View>
      <Text style={styles.name}>Athlete</Text>
      <TouchableOpacity style={styles.proBtn} onPress={() => router.push('/subscription/paywall')}>
        <Text style={styles.proBtnText}>Upgrade to Pro</Text>
      </TouchableOpacity>
      <TouchableOpacity style={styles.logoutBtn} onPress={() => router.replace('/auth/login')}>
        <Text style={styles.logoutText}>Log Out</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0A0A0A', paddingTop: 64, paddingHorizontal: 20, alignItems: 'center' },
  title: { fontSize: 24, fontWeight: '800', color: '#F5F5F5', alignSelf: 'flex-start', marginBottom: 32 },
  avatar: { width: 80, height: 80, borderRadius: 40, backgroundColor: '#3B82F6', alignItems: 'center', justifyContent: 'center' },
  avatarText: { fontSize: 28, fontWeight: '900', color: '#fff' },
  name: { color: '#F5F5F5', fontSize: 20, fontWeight: '700', marginTop: 12 },
  proBtn: { marginTop: 32, backgroundColor: '#A3FF12', borderRadius: 12, paddingVertical: 14, paddingHorizontal: 40 },
  proBtnText: { color: '#0A0A0A', fontWeight: '800', fontSize: 16 },
  logoutBtn: { marginTop: 16, padding: 12 },
  logoutText: { color: '#555' },
});
