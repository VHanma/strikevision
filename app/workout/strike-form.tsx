import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { CameraView, useCameraPermissions } from 'expo-camera';

export default function StrikeFormScreen() {
  const router = useRouter();
  const [permission, requestPermission] = useCameraPermissions();
  const [recording, setRecording] = useState(false);

  if (!permission?.granted) {
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
        <Text style={styles.title}>Form Check</Text>
        <Text style={styles.cue}>{recording ? 'Recording...' : 'Position yourself in frame'}</Text>
        <TouchableOpacity
          style={[styles.btn, recording && styles.btnStop]}
          onPress={() => {
            if (recording) {
              setRecording(false);
              router.push('/workout/results');
            } else {
              setRecording(true);
            }
          }}
        >
          <Text style={styles.btnText}>{recording ? 'Stop & Review' : 'Start Recording'}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.backBtn} onPress={() => router.back()}>
          <Text style={styles.backText}>✕</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0A0A0A' },
  overlay: { ...StyleSheet.absoluteFillObject, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(0,0,0,0.5)' },
  title: { fontSize: 22, fontWeight: '900', color: '#F5F5F5', marginBottom: 16, letterSpacing: 2 },
  cue: { color: '#A3FF12', marginBottom: 32, fontSize: 15 },
  btn: { backgroundColor: '#3B82F6', borderRadius: 12, paddingVertical: 14, paddingHorizontal: 36 },
  btnStop: { backgroundColor: '#EF4444' },
  btnText: { color: '#fff', fontWeight: '800', fontSize: 16 },
  permText: { color: '#F5F5F5', fontSize: 16, marginBottom: 16, textAlign: 'center', padding: 20 },
  backBtn: { position: 'absolute', top: 52, right: 24 },
  backText: { color: '#F5F5F5', fontSize: 22 },
});
