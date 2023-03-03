import { Frame } from 'react-native-vision-camera'

/**
 * Scans QR codes.
 */
export default function scanQR(frame, screenHeight, screenWidth) {
  'worklet'
  return __scanQR(frame)
}