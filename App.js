import React, {useEffect, useState, useMemo, useRef} from 'react';
import {StyleSheet, View, Text} from 'react-native';
import {
  Camera,
  useCameraDevices,
  useFrameProcessor,
} from 'react-native-vision-camera';
import scanQR from './plugin';
import {runOnJS, runOnUI} from 'react-native-reanimated';
import {Canvas, Rect} from '@shopify/react-native-skia';

export default function App() {
  const devices = useCameraDevices();
  const device = devices.back;

  const [cameraPermission, setCameraPermission] = useState(true);
  const [microphonePermission, setMicrophonePermission] = useState(true);
  const [screenHeight, setScreenHeight] = useState(0);
  const [screenWidth, setScreenWidth] = useState(0);
  const [results, setResults] = useState([]);
  const [finalBoxes, setFinalBoxes] = useState([]);
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

  const frameProcessor = useFrameProcessor(
    frame => {
      'worklet';

      let result = scanQR(frame);
      const object = JSON.parse(result);
      runOnJS(setResults)(object.mResults);
    },
    [screenHeight, screenWidth],
  );

  useEffect(() => {
    if (layout) {
      const arr = [];
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

      results.forEach(element => {
        arr.push({
          x: Math.floor(offsetX + element.x * scale),
          y: Math.floor(offsetY + element.y * scale),
          w: Math.floor(element.w * scale),
          h: Math.floor(element.h * scale),
          className: element.className,
        });
      });
      setFinalBoxes(arr);
    }
  }, [results, layout]);

  const getStyle = result => {
    return {
      position: 'absolute',
      top: result.y + 5,
      left: result.x + 5,
      color: 'red',
      fontWeight: 'bold',
    };
  };

  const updateLayout = event => {
    setLayout(event.nativeEvent.layout);
    runOnUI(setLayout)(event.nativeEvent.layout);
  };

  String.prototype.hashCode = function () {
    var hash = 0,
      i,
      chr;
    if (this.length === 0) return hash;
    for (i = 0; i < this.length; i++) {
      chr = this.charCodeAt(i);
      hash = (hash << 5) - hash + chr;
      hash |= 0; // Convert to 32bit integer
    }
    return hash;
  };

  const objectColors = [
    '#FF3B30',
    '#5856D6',
    '#34C759',
    '#007AFF',
    '#FF9500',
    '#AF52DE',
    '#5AC8FA',
    '#FFCC00',
    '#FF2D55',
  ];

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
          style={styles.canvasStyle}
          onLayout={event => {
            updateLayout(event);
          }}>
          {layout != null &&
            finalBoxes.map((box, idx) => (
              <Rect
                x={box.x}
                y={box.y}
                width={box.w}
                height={box.h}
                color={
                  objectColors[
                    Math.abs(box.className.hashCode()) % objectColors.length
                  ]
                }
                style="stroke"
                key={idx}
                strokeWidth={2}
                mode="continuous"
              />
            ))}
        </Canvas>
        {layout != null &&
          finalBoxes.map((box, idx) => (
            <Text key={idx + 'text'} style={getStyle(box)}>
              {box.className}
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
  canvasStyle: {
    position: 'absolute',
    top: 0,
    left: 0,
    height: '100%',
    width: '100%',
    right: 0,
    bottom: 0,
    zIndex: 3, // works on ios
    elevation: 3, // works on android
  },
});
