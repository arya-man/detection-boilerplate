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
import com.facebook.react.bridge.WritableNativeMap;
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
import java.util.Collections;
import java.util.Arrays;
import java.util.Comparator;
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

class AnalysisResult {
  private final ArrayList<Result> mResults;

  public AnalysisResult(ArrayList<Result> results) {
      this.mResults = results;
  }
}

class Result {
  Float score;
  String className;
  int left;
  int right;
  int top;
  int bottom;

  public Result(Float output, int left, int right, int top, int bottom, String className) {
      this.score = output;
      this.className = className;
      this.left = left;
      this.right = right;
      this.top = top;
      this.bottom = bottom;
  }
};

class IntermediateResult {
    int classIndex;
    Float score;
    Rect rect;

    public IntermediateResult(int cls, Float output, Rect rect) {
        this.classIndex = cls;
        this.score = output;
        this.rect = rect;
    }
};

public class QRPlugin extends FrameProcessorPlugin {
  // model input image size
  static int mInputWidth = 640;
  static int mInputHeight = 640;

  // for yolov5 model, no need to apply MEAN and STD
  static float[] NO_MEAN_RGB = new float[] {0.0f, 0.0f, 0.0f};
  static float[] NO_STD_RGB = new float[] {1.0f, 1.0f, 1.0f};

  // model output is of size 25200*(num_of_class+5)
  private static int mOutputRow = 25200; // as decided by the YOLOv5 model for input image of size 640*640
  private static int mOutputColumn = 85; // left, top, right, bottom, score and 80 class probability
  private static float mThreshold = 0.30f; // score above which a detection is generated
  private static int mNmsLimit = 15;

  private Context mContext;
  private Module mModule;

  static String[] mClasses;

  static float IOU(Rect a, Rect b) {
    float areaA = (a.right - a.left) * (a.bottom - a.top);
    if (areaA <= 0.0) return 0.0f;

    float areaB = (b.right - b.left) * (b.bottom - b.top);
    if (areaB <= 0.0) return 0.0f;

    float intersectionMinX = Math.max(a.left, b.left);
    float intersectionMinY = Math.max(a.top, b.top);
    float intersectionMaxX = Math.min(a.right, b.right);
    float intersectionMaxY = Math.min(a.bottom, b.bottom);
    float intersectionArea = Math.max(intersectionMaxY - intersectionMinY, 0) *
            Math.max(intersectionMaxX - intersectionMinX, 0);
    return intersectionArea / (areaA + areaB - intersectionArea);
  }

  static ArrayList<Result> nonMaxSuppression(ArrayList<IntermediateResult> boxes, int limit, float threshold) {
    Collections.sort(boxes,
    new Comparator<IntermediateResult>() {
        @Override
        public int compare(IntermediateResult o1, IntermediateResult o2) {
            return o1.score.compareTo(o2.score);
        }
    });

    ArrayList<IntermediateResult> selected = new ArrayList<>();
    boolean[] active = new boolean[boxes.size()];
    Arrays.fill(active, true);
    int numActive = active.length;

    boolean done = false;
    for (int i=0; i<boxes.size() && !done; i++) {
      if (active[i]) {
        IntermediateResult boxA = boxes.get(i);
        selected.add(boxA);
        if (selected.size() >= limit) break;

        for (int j=i+1; j<boxes.size(); j++) {
          if (active[j]) {
            IntermediateResult boxB = boxes.get(j);
            if (IOU(boxA.rect, boxB.rect) > threshold) {
              active[j] = false;
              numActive -= 1;
              if (numActive <= 0) {
                done = true;
                break;
              }
            }
          }
        }
      }
    }

    ArrayList<Result> finalResult = new ArrayList<>();

    for(int i=0; i<selected.size(); i++)
    {
      IntermediateResult intermediateResult = selected.get(i);
      Result result = new Result(intermediateResult.score, intermediateResult.rect.left, 
        intermediateResult.rect.right, intermediateResult.rect.top, intermediateResult.rect.bottom, 
        mClasses[intermediateResult.classIndex]);
        finalResult.add(result);
    }

    return finalResult;
  }

  static ArrayList<Result> outputsToNMSPredictions(float[] outputs, float imgScaleX, float imgScaleY, float ivScaleX, float ivScaleY, float startX, float startY) {
    ArrayList<IntermediateResult> results = new ArrayList<>();
    for (int i = 0; i< mOutputRow; i++) {
      if (outputs[i* mOutputColumn +4] > mThreshold) {
        float x = outputs[i* mOutputColumn];
        float y = outputs[i* mOutputColumn +1];
        float w = outputs[i* mOutputColumn +2];
        float h = outputs[i* mOutputColumn +3];

        float left = imgScaleX * (x - w/2);
        float top = imgScaleY * (y - h/2);
        float right = imgScaleX * (x + w/2);
        float bottom = imgScaleY * (y + h/2);

        float max = outputs[i* mOutputColumn +5];
        int cls = 0;
        for (int j = 0; j < mOutputColumn -5; j++) {
          if (outputs[i* mOutputColumn +5+j] > max) {
            max = outputs[i* mOutputColumn +5+j];
            cls = j;
          }
        }

        Rect rect = new Rect((int)(startX+ivScaleX*left), (int)(startY+top*ivScaleY), (int)(startX+ivScaleX*right), (int)(startY+ivScaleY*bottom));
        IntermediateResult result = new IntermediateResult(cls, outputs[i*mOutputColumn+4], rect);
        results.add(result);
      }
    }
    return nonMaxSuppression(results, mNmsLimit, mThreshold);
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

  private AnalysisResult analyzeImage(ImageProxy image) {
    Bitmap bitmap = imgToBitmap(image.getImage());    
    Matrix matrix = new Matrix();
    matrix.postRotate(90.0f);
    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, mInputWidth, mInputHeight, true);
    final Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, NO_MEAN_RGB, NO_STD_RGB);
    IValue[] outputTuple = mModule.forward(IValue.from(inputTensor)).toTuple();
    final Tensor outputTensor = outputTuple[0].toTensor();
    final float[] outputs = outputTensor.getDataAsFloatArray();

    float imgScaleX = (float)bitmap.getWidth() / mInputWidth;
    float imgScaleY = (float)bitmap.getHeight() / mInputHeight;
    float ivScaleX = 1;
    float ivScaleY = 1;

    final ArrayList<Result> results = outputsToNMSPredictions(outputs, imgScaleX, imgScaleY, ivScaleX, ivScaleY, 0, 0);
    return new AnalysisResult(results);
  }

  public static String assetFilePath(Context context, String assetName) throws IOException {
    File file = new File(context.getFilesDir(), assetName);
    if (file.exists() && file.length() > 0) {
      return file.getAbsolutePath();
    }

    try (InputStream is = context.getAssets().open(assetName)) {
      try (OutputStream os = new FileOutputStream(file)) {
        byte[] buffer = new byte[4 * 1024];
        int read;
        while ((read = is.read(buffer)) != -1) {
          os.write(buffer, 0, read);
        }
        os.flush();
      }
      return file.getAbsolutePath();
    }
  }
  
  Interpreter.Options options = new Interpreter.Options();
  @Override
  public Object callback(ImageProxy image, Object[] params) {
    try {
      if (mModule == null) {
          mModule = LiteModuleLoader.load(assetFilePath(mContext, "yolov5s.torchscript.ptl"));
      }
      BufferedReader br = new BufferedReader(new InputStreamReader(mContext.getAssets().open("classes.txt")));
      String line;
      List<String> classes = new ArrayList<>();
      while ((line = br.readLine()) != null) {
          classes.add(line);
      }
      mClasses = new String[classes.size()];
      classes.toArray(mClasses);
    } catch (IOException e) {
      Log.e("Object Detection", "Error reading assets", e);
    }

    AnalysisResult result = analyzeImage(image);
    Gson gson = new Gson();
    String jsonString = gson.toJson(result);
    return jsonString;
  } 

  QRPlugin(ReactApplicationContext context) {
    super("scanQR");
    mContext = context.getApplicationContext();
  }
}
