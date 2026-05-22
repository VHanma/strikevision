import * as SecureStore from 'expo-secure-store';
import type { SessionResult, UserProfile } from '../types';

const KEYS = {
  sessions: 'sv_sessions',
  profile: 'sv_profile',
};

export async function saveSessions(sessions: SessionResult[]): Promise<void> {
  await SecureStore.setItemAsync(KEYS.sessions, JSON.stringify(sessions));
}

export async function loadSessions(): Promise<SessionResult[]> {
  const raw = await SecureStore.getItemAsync(KEYS.sessions);
  return raw ? JSON.parse(raw) : [];
}

export async function addSession(result: SessionResult): Promise<void> {
  const existing = await loadSessions();
  await saveSessions([result, ...existing]);
}

export async function saveProfile(profile: UserProfile): Promise<void> {
  await SecureStore.setItemAsync(KEYS.profile, JSON.stringify(profile));
}

export async function loadProfile(): Promise<UserProfile | null> {
  const raw = await SecureStore.getItemAsync(KEYS.profile);
  return raw ? JSON.parse(raw) : null;
}
