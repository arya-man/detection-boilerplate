import React, { useEffect, useState, useMemo, } from 'react'
import { StyleSheet, Text, View, PermissionsAndroid } from 'react-native'
import { Camera, useCameraDevices, useFrameProcessor, } from 'react-native-vision-camera';
import scanQR from './plugin';
import { runOnJS } from 'react-native-reanimated';
import { Canvas, RoundedRect, useValue, useImage, Image } from "@shopify/react-native-skia";


export default function App() {

  const devices = useCameraDevices()
  const device = devices.back

  const [cameraPermission, setCameraPermission] = useState(true)
  const [microphonePermission, setMicrophonePermission] = useState(true)
  const [screenHeight, setScreenHeight] = useState(0)
  const [screenWidth, setScreenWidth] = useState(0)
  const [faces, setFaces] = useState([])
  const skia_faces = useValue([]);
  const [latency, setLatency] = useState(0)

  useEffect(() => {

    async function perm() {

      await Camera.requestCameraPermission()
      await Camera.requestMicrophonePermission()

      let cameraPerm = await Camera.getCameraPermissionStatus()
      let microphonePerm = await Camera.getMicrophonePermissionStatus()

      setCameraPermission(cameraPerm === 'authorized')
      setMicrophonePermission(microphonePerm === 'authorized')
    }

    perm()

  }, [])

  const format = useMemo(() => {
    if (device) {
      for (var i = 0; i < device?.formats.length; i += 1) {
        if (device?.formats[i].photoWidth && device?.formats[i].photoHeight) {
          if (device?.formats[i].photoHeight === 480) {
            // console.log("&&&&", device?.formats[i])
            return device?.formats[i]
          }
        }
      }
    }
  }, [device?.formats])

  const frameProcessor = useFrameProcessor((frame) => {
    'worklet'

    let result = scanQR(frame)
    // to se state
    // runOnJS(setFaces)(result) -> where setFaces is a state setter
    console.log("***", result)

  }, [screenHeight, screenWidth])

  useEffect(() => {
    skia_faces.current = faces;
  }, [faces])

  if (device && cameraPermission && microphonePermission) {
    return (
      <View style={styles.container}>
        <Camera
          onLayout={(e) => {
            setScreenHeight(e.nativeEvent.layout.height)
            setScreenWidth(e.nativeEvent.layout.width)
          }}
          style={{ height: '100%', width: "100%" }}
          device={device}
          isActive={true}
          format={format}
          frameProcessorFps={2}
          frameProcessor={frameProcessor}
        />
      </View>
    )
  }

  return (
    <View style={{ flex: 1, backgroundColor: 'white' }}>
    </View>
  )

}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
})