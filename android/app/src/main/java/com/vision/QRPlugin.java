package com.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;
import android.view.TextureView;
import android.view.ViewStub;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.camera.core.ImageProxy;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.ReadableNativeArray;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.bridge.Arguments;
import com.mrousavy.camera.frameprocessor.FrameProcessorPlugin;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;

import com.google.gson.Gson;

import android.graphics.ColorMatrix;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Color;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.TensorOperator;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.TransformToGrayscaleOp;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;
import org.tensorflow.lite.support.tensorbuffer.TensorBufferFloat;

import android.os.Environment;
import android.media.MediaScannerConnection;
import android.net.Uri;

public class QRPlugin extends FrameProcessorPlugin {


  //Interpreter.Options options = new Interpreter.Options();
  private Context mContext;

  private Module mModule = null;
  private ResultView mResultView;

  static class AnalysisResult {
    private final ArrayList<Result> mResults;

    public AnalysisResult(ArrayList<Result> results) {
      mResults = results;

    }
  }
  private Bitmap imgToBitmap(Image image) {
    Image.Plane[] planes = image.getPlanes();
    ByteBuffer yBuffer = planes[0].getBuffer();
    ByteBuffer uBuffer = planes[1].getBuffer();
    ByteBuffer vBuffer = planes[2].getBuffer();

    int ySize = yBuffer.remaining();
    int uSize = uBuffer.remaining();
    int vSize = vBuffer.remaining();

    byte[] nv21 = new byte[ySize + uSize + vSize];
    yBuffer.get(nv21, 0, ySize);
    vBuffer.get(nv21, ySize, vSize);
    uBuffer.get(nv21, ySize + vSize, uSize);

    YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 75, out);

    byte[] imageBytes = out.toByteArray();
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
  }


  @Override
  public Object callback(ImageProxy image, Object[] params) {
    try {
      mModule = LiteModuleLoader.load(MainActivity.assetFilePath(mContext, "yolov5s.torchscript.ptl"));
    } catch (IOException e) {
      Log.e("Object Detection", "Error reading assets", e);
      return null;
    }
    Bitmap bitmap = imgToBitmap(image.getImage());
    Matrix matrix = new Matrix();
    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, PrePostProcessor.mInputWidth, PrePostProcessor.mInputHeight, true);

    final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, PrePostProcessor.NO_MEAN_RGB, PrePostProcessor.NO_STD_RGB);
    IValue[] outputTuple = mModule.forward(IValue.from(inputTensor)).toTuple();
    final Tensor outputTensor = outputTuple[0].toTensor();
    final float[] outputs = outputTensor.getDataAsFloatArray();

    float imgScaleX = (float)bitmap.getWidth() / PrePostProcessor.mInputWidth;
    float imgScaleY = (float)bitmap.getHeight() / PrePostProcessor.mInputHeight;
    float ivScaleX = (float)mResultView.getWidth() / bitmap.getWidth();
    float ivScaleY = (float)mResultView.getHeight() / bitmap.getHeight();

    final ArrayList<Result> results = PrePostProcessor.outputsToNMSPredictions(outputs, imgScaleX, imgScaleY, ivScaleX, ivScaleY, 0, 0);
    //return new AnalysisResult(results);
    Rect r = results.get(0).rect;
    WritableNativeArray nativeArray = new WritableNativeArray();
    nativeArray.pushDouble(r.left);nativeArray.pushDouble(r.bottom);nativeArray.pushDouble(r.right - r.left);nativeArray.pushDouble(r.top - r.bottom);
// Convert the WritableNativeArray to a ReadableNativeArray if needed
    WritableNativeMap map = new WritableNativeMap();

    map.putArray("values", nativeArray);
    return map;
  }

  QRPlugin(ReactApplicationContext context) {
    super("scanQR");
    mContext = context.getApplicationContext();
  }
}
