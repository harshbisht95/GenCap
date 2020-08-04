package com.harsh.gencap;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;

import com.google.gson.reflect.TypeToken;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

import org.tensorflow.lite.Interpreter;
import com.google.gson.Gson;

public class CaptionGenerator {
    // PATHS
    private static final String MAIN_MODEL_PATH = "tf_lite_caption_model.tflite";
    private static final String INCEPTION_MODEL_PATH = "tf_lite_inception_model2.tflite";
    private static final String WORD_TO_TOKEN_JSON = "wordtoix.json";
    private static final String TOKEN_TO_WORD_JSON = "ixtoword.json";

    private static final int DIM_BATCH_SIZE = 1;
    private static final int DIM_PIXEL_SIZE = 3;
    static final int DIM_IMG_SIZE_X = 299;
    static final int DIM_IMG_SIZE_Y = 299;

    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128.0f;

    private int[] intValues = new int[DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y];

    private Interpreter tflite_main_model;
    private Interpreter tflite_inception;

    private Map<String,Integer> wordtoint;
    private Map<Integer,String> inttoword;
    private static final float FILTER_FACTOR = 0.3f;
    private static final int FILTER_STAGES = 3;
    float[][] input = new float[1][34];

    float[][] inception_output = new float[1][2048];
    int counter=0;
    int RESULTS_TO_SHOW=1;
    float[][] labelProbArray = null;
    float[][] filterLabelProbArray;
    ArrayList<String> final_opt = new ArrayList<String>();
    Random r = new Random();
    private PriorityQueue<Map.Entry<String, Float>> sortedLabels =
            new PriorityQueue<>(
                    RESULTS_TO_SHOW,
                    new Comparator<Map.Entry<String, Float>>() {
                        @Override
                        public int compare(Map.Entry<String, Float> o1, Map.Entry<String, Float> o2) {
                            return (o1.getValue()).compareTo(o2.getValue());
                        }
                    });
    //Constructor
    CaptionGenerator(Context context) throws IOException {
        tflite_main_model = new Interpreter(loadModelFile(context, MAIN_MODEL_PATH));
        tflite_inception = new Interpreter(loadModelFile(context, INCEPTION_MODEL_PATH));
        loadJSONFromAsset(context);
        labelProbArray = new float [1][wordtoint.size()];
    }

    private void loadJSONFromAsset(Context context) throws IOException {
        wordtoint  = new Gson().fromJson(get_json_object_string(context, WORD_TO_TOKEN_JSON), new TypeToken<HashMap<String, Integer>>() {}.getType());
        inttoword  = new Gson().fromJson(get_json_object_string(context, TOKEN_TO_WORD_JSON), new TypeToken<HashMap<Integer, String>>() {}.getType());
    }

    private String get_json_object_string(Context context, String JSON_PATH) {
        String json = null;
        try {
            InputStream is = context.getAssets().open(JSON_PATH);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
        return json;
    }

    private MappedByteBuffer loadModelFile(Context context, String MODEL_PATH) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_PATH);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }
    String get_desc(Bitmap bitmap){
        int bytes = bitmap.getByteCount();
        float[][][][] imgData = getPixels(bitmap);

        run_inception(imgData);
        String output = run_model();
        return output;
    }
    public float[][][][] getPixels(Bitmap bitmap) {
        int pixel = 0;
        float[][][][] imgData = new float[DIM_BATCH_SIZE][DIM_IMG_SIZE_X][DIM_IMG_SIZE_Y][DIM_PIXEL_SIZE];
        if (bitmap.getWidth() != DIM_IMG_SIZE_X || bitmap.getHeight() != DIM_IMG_SIZE_Y) {
            // rescale the bitmap if needed
            //bitmap = ThumbnailUtils.extractThumbnail(bitmap, DIM_IMG_SIZE_X, DIM_IMG_SIZE_Y);
        }
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        for (int i = 0; i < DIM_IMG_SIZE_X; ++i) {
            for (int j = 0; j < DIM_IMG_SIZE_Y; ++j) {
                final int val = intValues[pixel++];
                imgData[0][i][j][0] = (float)((((val >> 16) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
                imgData[0][i][j][1] = (float)((((val >> 8) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
                imgData[0][i][j][2] = (float)((((val) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
            }
        }
        return imgData;
    }

    public String run_model() {
        int endseq = 0;
        int start=14;

        while(true) {
            if(endseq == 14)
                break;
            Object[] inp = {inception_output, buildInput(start)};

            Map<Integer, Object> outputMap = new HashMap<>();
            outputMap.put(0, labelProbArray);
            tflite_main_model.runForMultipleInputsOutputs(inp, outputMap);
            filterLabelProbArray = (float[][]) (outputMap.get(0));
            String tmp = printTopKLabels();
            start = wordtoint.get(tmp);
            endseq=start;
            if(start != 14)
            final_opt.add(tmp);
        }
        String listString = "";

        for (String s : final_opt)
        {
            listString += s + " ";
        }
        return listString;
    }


    public void run_inception(float[][][][] imgData) {
        tflite_inception.run(imgData, inception_output);

    }
    private float[][] buildInput(int start){

        input[0][counter]=start;
            for(int i=counter+1;i<34;i++) {
                input[0][i] = 0;

            }
        counter++;
        return input;

    }
    /** Prints top-K labels, to be shown in UI as the results. */
    private String printTopKLabels() {
        for (int i = 0; i < wordtoint.size(); ++i) {
            sortedLabels.add(
                    new AbstractMap.SimpleEntry<>(inttoword.get(i), labelProbArray[0][i]));
            if (sortedLabels.size() > RESULTS_TO_SHOW) {
                sortedLabels.poll();
            }
        }
        String textToShow = "";
        final int size = sortedLabels.size();
        for (int i = 0; i < size; ++i) {
            Map.Entry<String, Float> label = sortedLabels.poll();
            textToShow = label.getKey();
        }
        System.out.println("op  :  " + textToShow);
        return textToShow;
    }
    void applyFilter(){
        int num_labels =  wordtoint.size();

        // Low pass filter `labelProbArray` into the first stage of the filter.
        for(int j=0; j<num_labels; ++j){
            filterLabelProbArray[0][j] += FILTER_FACTOR*(labelProbArray[0][j] -
                    filterLabelProbArray[0][j]);
        }
        // Low pass filter each stage into the next.
        for (int i=1; i<FILTER_STAGES; ++i){
            for(int j=0; j<num_labels; ++j){
                filterLabelProbArray[i][j] += FILTER_FACTOR*(
                        filterLabelProbArray[i-1][j] -
                                filterLabelProbArray[i][j]);

            }
        }

        // Copy the last stage filter output back to `labelProbArray`.
        for(int j=0; j<num_labels; ++j){
            labelProbArray[0][j] = filterLabelProbArray[FILTER_STAGES-1][j];
        }
    }
}