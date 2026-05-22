import { View, Text, TextInput, TouchableOpacity, StyleSheet } from 'react-native';
import { useRouter } from 'expo-router';
import { useState } from 'react';

export default function LoginScreen() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  return (
    <View style={styles.container}>
      <Text style={styles.logo}>STRIKE<Text style={{ color: '#3B82F6' }}>VISION</Text></Text>
      <Text style={styles.heading}>Welcome back</Text>
      <TextInput style={styles.input} placeholder="Email" placeholderTextColor="#555"
        value={email} onChangeText={setEmail} keyboardType="email-address" autoCapitalize="none" />
      <TextInput style={styles.input} placeholder="Password" placeholderTextColor="#555"
        value={password} onChangeText={setPassword} secureTextEntry />
      <TouchableOpacity style={styles.btn} onPress={() => router.replace('/(tabs)')}>
        <Text style={styles.btnText}>Log In</Text>
      </TouchableOpacity>
      <TouchableOpacity onPress={() => router.push('/auth/signup')}>
        <Text style={styles.link}>Don't have an account? <Text style={{ color: '#3B82F6' }}>Sign up</Text></Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0A0A0A', padding: 28, justifyContent: 'center' },
  logo: { fontSize: 26, fontWeight: '900', color: '#F5F5F5', letterSpacing: 2, marginBottom: 32, textAlign: 'center' },
  heading: { fontSize: 22, fontWeight: '800', color: '#F5F5F5', marginBottom: 24 },
  input: { backgroundColor: '#171717', borderWidth: 1, borderColor: '#333', borderRadius: 10, padding: 14, color: '#F5F5F5', marginBottom: 14 },
  btn: { backgroundColor: '#3B82F6', borderRadius: 12, padding: 16, alignItems: 'center', marginTop: 8 },
  btnText: { color: '#fff', fontWeight: '800', fontSize: 16 },
  link: { color: '#555', textAlign: 'center', marginTop: 20 },
});
