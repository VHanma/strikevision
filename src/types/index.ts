export type WorkoutMode = 'strike_speed' | 'form_check' | 'fight_camp';

export interface SessionResult {
  id: string;
  userId: string;
  mode: WorkoutMode;
  durationSec: number;
  estimatedSpeed?: number;
  impactScore?: number;
  createdAt: string;
}

export interface UserProfile {
  id: string;
  email: string;
  displayName: string;
  isPro: boolean;
  createdAt: string;
}

export interface WorkoutStats {
  bestSpeed: number | null;
  avgSpeed: number | null;
  totalSessions: number;
  currentStreak: number;
}
