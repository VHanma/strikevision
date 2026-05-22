import type { SessionResult, WorkoutStats } from '../types';

export function computeStats(sessions: SessionResult[]): WorkoutStats {
  const speeds = sessions
    .map((s) => s.estimatedSpeed)
    .filter((v): v is number => v !== undefined);

  const bestSpeed = speeds.length ? Math.max(...speeds) : null;
  const avgSpeed = speeds.length ? Math.round(speeds.reduce((a, b) => a + b, 0) / speeds.length) : null;

  return {
    bestSpeed,
    avgSpeed,
    totalSessions: sessions.length,
    currentStreak: computeStreak(sessions),
  };
}

function computeStreak(sessions: SessionResult[]): number {
  if (!sessions.length) return 0;
  const dates = sessions.map((s) => s.createdAt.slice(0, 10));
  const unique = [...new Set(dates)].sort().reverse();
  let streak = 1;
  for (let i = 1; i < unique.length; i++) {
    const prev = new Date(unique[i - 1]);
    const curr = new Date(unique[i]);
    const diff = (prev.getTime() - curr.getTime()) / 86400000;
    if (diff === 1) streak++;
    else break;
  }
  return streak;
}
