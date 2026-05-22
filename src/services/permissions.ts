import { Camera } from 'expo-camera';
import { Audio } from 'expo-av';

export async function requestCameraPermission(): Promise<boolean> {
  const { status } = await Camera.requestCameraPermissionsAsync();
  return status === 'granted';
}

export async function requestMicPermission(): Promise<boolean> {
  const { status } = await Audio.requestPermissionsAsync();
  return status === 'granted';
}

export async function requestAllMediaPermissions(): Promise<boolean> {
  const [cam, mic] = await Promise.all([
    requestCameraPermission(),
    requestMicPermission(),
  ]);
  return cam && mic;
}
