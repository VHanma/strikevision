import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { GestureHandlerRootView } from 'react-native-gesture-handler';

export default function RootLayout() {
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <StatusBar style="light" />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: '#0A0A0A' } }}>
        <Stack.Screen name="(tabs)" />
        <Stack.Screen name="auth/login" />
        <Stack.Screen name="auth/signup" />
        <Stack.Screen name="workout/velocity-test" />
        <Stack.Screen name="workout/strike-form" />
        <Stack.Screen name="workout/live-session" />
        <Stack.Screen name="workout/results" />
        <Stack.Screen name="subscription/paywall" />
      </Stack>
    </GestureHandlerRootView>
  );
}
