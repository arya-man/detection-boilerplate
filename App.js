/* eslint-disable react/self-closing-comp */
/* eslint-disable react-native/no-inline-styles */
/* eslint-disable react-hooks/exhaustive-deps */
import React, {useEffect, useState, useMemo} from 'react';
import {StyleSheet, View, PermissionsAndroid, Text} from 'react-native';
import {
  Camera,
  useCameraDevices,
  useFrameProcessor,
} from 'react-native-vision-camera';
import scanQR from './plugin';
import {runOnJS} from 'react-native-reanimated';
import {
  Canvas,
  RoundedRect,
  useValue,
  useImage,
  Image,
  Rect,
  Group,
  // Text,
  useFont,
} from '@shopify/react-native-skia';

export default function App() {
  const devices = useCameraDevices();
  const device = devices.back;

  const [cameraPermission, setCameraPermission] = useState(true);
  const [microphonePermission, setMicrophonePermission] = useState(true);
  const [screenHeight, setScreenHeight] = useState(0);
  const [screenWidth, setScreenWidth] = useState(0);
  const [faces, setFaces] = useState([]);
  const skia_faces = useValue([]);
  const [latency, setLatency] = useState(0);
  const [results, setResults] = useState([]);
  const [layout, setLayout] = useState(null);

  useEffect(() => {
    async function perm() {
      await Camera.requestCameraPermission();
      await Camera.requestMicrophonePermission();

      let cameraPerm = await Camera.getCameraPermissionStatus();
      let microphonePerm = await Camera.getMicrophonePermissionStatus();

      setCameraPermission(cameraPerm === 'authorized');
      setMicrophonePermission(microphonePerm === 'authorized');
    }

    perm();
  }, []);

  const format = useMemo(() => {
    if (device) {
      for (var i = 0; i < device?.formats.length; i += 1) {
        if (device?.formats[i].photoWidth && device?.formats[i].photoHeight) {
          if (device?.formats[i].photoHeight === 480) {
            // console.log("&&&&", device?.formats[i])
            return device?.formats[i];
          }
        }
      }
    }
  }, [device?.formats]);

  const updateResults = res => setResults(res);

  const frameProcessor = useFrameProcessor(
    frame => {
      'worklet';

      let result = scanQR(frame);
      // to se state
      // runOnJS(setFaces)(result) -> where setFaces is a state setter

      // console.log(layout.width, layout.height);
      // console.log('***', result);
      const object = JSON.parse(result);

      if (layout) {
        const imageWidth = 640;
        const imageHeight = 640;
        const scale = Math.max(
          layout.width / imageWidth,
          layout.height / imageHeight,
        );
        const displayWidth = imageWidth * scale;
        const displayHeight = imageHeight * scale;
        const offsetX = (layout.width - displayWidth) / 2;
        const offsetY = (layout.height - displayHeight) / 2;

        object.mResults.forEach(element => {
          element.x = Math.floor(offsetX + element.x * scale);
          element.y = Math.floor(offsetY + element.y * scale);
          element.w = Math.floor(element.w * scale);
          element.h = Math.floor(element.h * scale);
        });
        // console.log(frame.width, frame.height, scale);
      }

      // console.log('----', object.mResults);

      runOnJS(updateResults)(object.mResults);
    },
    [screenHeight, screenWidth],
  );

  useEffect(() => {
    skia_faces.current = faces;
  }, [faces]);

  const getStyle = result => {
    return {
      position: 'absolute',
      top: result.y,
      left: result.x,
      color: 'red',
    };
  };

  if (device && cameraPermission && microphonePermission) {
    return (
      <View style={styles.container}>
        <Camera
          onLayout={e => {
            setScreenHeight(e.nativeEvent.layout.height);
            setScreenWidth(e.nativeEvent.layout.width);
          }}
          style={{height: '100%', width: '100%'}}
          device={device}
          isActive={true}
          format={format}
          frameProcessorFps={2}
          frameProcessor={frameProcessor}
        />

        <Canvas
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            height: '100%',
            width: '100%',
            right: 0,
            bottom: 0,
            zIndex: 3, // works on ios
            elevation: 3, // works on android
          }}
          onLayout={event => {
            setLayout(event.nativeEvent.layout);
          }}>
          {layout != null &&
            results.map((result, idx) => (
              <Rect
                x={result.x}
                y={result.y}
                width={result.w}
                height={result.h}
                color="red"
                style="stroke"
                key={idx}
                strokeWidth={1}
              />
            ))}
        </Canvas>
        {layout != null &&
          results.map((result, idx) => (
            <Text key={idx + 'text'} style={getStyle(result)}>
              {result.className}
            </Text>
          ))}
      </View>
    );
  }

  return <View style={{flex: 1, backgroundColor: 'white'}}></View>;
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
});
