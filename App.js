import React, { useEffect, useState, useMemo, } from 'react'
import { ReadableNativeArray,WritableNativeArray,ReadableNativeMap,WritableNativeMap,StyleSheet, View, Text, PermissionsAndroid } from 'react-native'
import { Camera, useCameraDevices, useFrameProcessor, } from 'react-native-vision-camera';
import scanQR from './plugin';
import { runOnJS } from 'react-native-reanimated';
import { Canvas, RoundedRect, useValue, useImage, Image, Surface, SKCanvas, rrect, SKRect, SKPaint } from "@shopify/react-native-skia";
import { NativeModules, Component  } from 'react-native';

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

    let result = scanQR(frame).get("values")

    console.log(result.getDouble(0))

    // to se state
    return (
        <Canvas style={{ flex: 1 }}>
          <RoundedRect
            x={result.getDouble(0)}
            y={result.getDouble(1)}
            width={result.getDouble(2)}
            height={result.getDouble(3)}
            r={25}
            color="lightblue"
          />
        </Canvas>
      );
    // runOnJS(setFaces)(result) -> where setFaces is a state setter

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
}


const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
})