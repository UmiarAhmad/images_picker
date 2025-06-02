package com.chavesgu.images_picker;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

import com.luck.picture.lib.PictureSelectionModel;
import com.luck.picture.lib.PictureSelector;
import com.luck.picture.lib.config.PictureConfig;
import com.luck.picture.lib.config.PictureMimeType;
import com.luck.picture.lib.entity.LocalMedia;
import com.luck.picture.lib.language.LanguageConfig;
import com.luck.picture.lib.listener.OnResultCallbackListener;
import com.luck.picture.lib.tools.PictureFileUtils;

/**
 * ImagesPickerPlugin тепер использует FlutterEmbedding V2 API.
 * Убираем старый регистратор Registrar и используем FlutterPlugin + ActivityAware.
 */
public class ImagesPickerPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener {
    private static final String CHANNEL_NAME = "chavesgu/images_picker";

    private MethodChannel channel;
    private Context context;
    private Activity activity;
    private Result pendingResult;

    private int WRITE_IMAGE_CODE = 33;
    private int WRITE_VIDEO_CODE = 44;
    private String WRITE_IMAGE_PATH;
    private String WRITE_VIDEO_PATH;
    private String ALBUM_NAME;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        // Сохраняем context и регистрируем MethodChannel
        context = flutterPluginBinding.getApplicationContext();
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), CHANNEL_NAME);
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        channel = null;
        context = null;
    }

    // ActivityAware callbacks:
    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        // Регистрируем слушатель результатов запросов на разрешения
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "getPlatformVersion":
                result.success("Android " + android.os.Build.VERSION.RELEASE);
                break;

            case "pick": {
                int count = (int) call.argument("count");
                String pickType = call.argument("pickType");
                double quality = call.argument("quality");
                boolean supportGif = call.argument("gif");
                int maxTime = call.argument("maxTime");
                @SuppressWarnings("unchecked")
                HashMap<String, Object> cropOption = call.argument("cropOption");
                String language = call.argument("language");

                int chooseType;
                switch (pickType) {
                    case "PickType.video":
                        chooseType = PictureMimeType.ofVideo();
                        break;
                    case "PickType.all":
                        chooseType = PictureMimeType.ofAll();
                        break;
                    default:
                        chooseType = PictureMimeType.ofImage();
                        break;
                }

                PictureSelectionModel model = PictureSelector.create(activity)
                        .openGallery(chooseType);
                Utils.setLanguage(model, language);
                Utils.setPhotoSelectOpt(model, count, quality);
                if (cropOption != null) Utils.setCropOpt(model, cropOption);
                model.isGif(supportGif);
                model.videoMaxSecond(maxTime);
                resolveMedias(model, result);
                break;
            }

            case "openCamera": {
                String pickType = call.argument("pickType");
                int maxTime = call.argument("maxTime");
                double quality = call.argument("quality");
                @SuppressWarnings("unchecked")
                HashMap<String, Object> cropOption = call.argument("cropOption");
                String language = call.argument("language");

                int chooseType = PictureMimeType.ofVideo();
                if ("PickType.image".equals(pickType)) {
                    chooseType = PictureMimeType.ofImage();
                }

                PictureSelectionModel model = PictureSelector.create(activity)
                        .openCamera(chooseType);
                model.setOutputCameraPath(context.getExternalCacheDir().getAbsolutePath());
                if ("PickType.image".equals(pickType)) {
                    model.cameraFileName("image_picker_camera_" + UUID.randomUUID() + ".jpg");
                } else {
                    model.cameraFileName("image_picker_camera_" + UUID.randomUUID() + ".mp4");
                }
                model.recordVideoSecond(maxTime);
                Utils.setLanguage(model, language);
                Utils.setPhotoSelectOpt(model, 1, quality);
                if (cropOption != null) Utils.setCropOpt(model, cropOption);
                resolveMedias(model, result);
                break;
            }

            case "saveVideoToAlbum": {
                String path = call.argument("path");
                String albumName = call.argument("albumName");
                WRITE_VIDEO_PATH = path;
                ALBUM_NAME = albumName;
                if (hasPermission()) {
                    saveVideoToGallery(path, albumName, result);
                } else {
                    pendingResult = result;
                    String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
                    ActivityCompat.requestPermissions(activity, permissions, WRITE_VIDEO_CODE);
                }
                break;
            }

            case "saveImageToAlbum": {
                String path = call.argument("path");
                String albumName = call.argument("albumName");
                WRITE_IMAGE_PATH = path;
                ALBUM_NAME = albumName;
                if (hasPermission()) {
                    saveImageToGallery(path, albumName, result);
                } else {
                    pendingResult = result;
                    String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
                    ActivityCompat.requestPermissions(activity, permissions, WRITE_IMAGE_CODE);
                }
                break;
            }

            default:
                result.notImplemented();
                break;
        }
    }

    private void resolveMedias(PictureSelectionModel model, Result result) {
        model.forResult(new OnResultCallbackListener<LocalMedia>() {
            @Override
            public void onResult(final List<LocalMedia> medias) {
                new Thread(() -> {
                    final List<Object> resArr = new ArrayList<>();
                    for (LocalMedia media : medias) {
                        HashMap<String, Object> map = new HashMap<>();
                        String path = media.getPath();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            path = media.getAndroidQToPath();
                        }
                        if (media.getMimeType().contains("image")) {
                            if (media.isCut()) path = media.getCutPath();
                            if (media.isCompressed()) path = media.getCompressPath();
                        }
                        map.put("path", path);

                        String thumbPath;
                        if (media.getMimeType().contains("image")) {
                            thumbPath = path;
                        } else {
                            thumbPath = createVideoThumb(path);
                        }
                        map.put("thumbPath", thumbPath);

                        int size = getFileSize(path);
                        map.put("size", size);

                        Log.i("ImagesPickerPlugin", map.toString());
                        resArr.add(map);
                    }
                    new Handler(context.getMainLooper()).post(() -> result.success(resArr));
                }).start();
            }

            @Override
            public void onCancel() {
                result.success(null);
            }
        });
    }

    private String createVideoThumb(String path) {
        Bitmap bitmap = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.FULL_SCREEN_KIND);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes);
        try {
            File outputDir = context.getCacheDir();
            File outputFile = File.createTempFile("image_picker_thumb_" + UUID.randomUUID(), ".jpg", outputDir);
            try (FileOutputStream fo = new FileOutputStream(outputFile)) {
                fo.write(bytes.toByteArray());
            }
            return outputFile.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private int getFileSize(String path) {
        File file = new File(path);
        return (int) file.length();
    }

    private void saveImageToGallery(final String path, String albumName, Result result) {
        boolean status = false;
        String suffix = path.substring(path.lastIndexOf('.') + 1);
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        status = FileSaver.saveImage(context, bitmap, suffix, albumName);
        result.success(status);
    }

    private void saveVideoToGallery(String path, String albumName, Result result) {
        result.success(FileSaver.saveVideo(context, path, albumName));
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED
                        && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PermissionChecker.PERMISSION_GRANTED);
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (pendingResult == null) {
            return false;
        }
        if (requestCode == WRITE_IMAGE_CODE) {
            if (grantResults.length > 1
                    && grantResults[0] == PermissionChecker.PERMISSION_GRANTED
                    && grantResults[1] == PermissionChecker.PERMISSION_GRANTED) {
                saveImageToGallery(WRITE_IMAGE_PATH, ALBUM_NAME, pendingResult);
            } else {
                pendingResult.success(false);
            }
            pendingResult = null;
            return true;
        }
        if (requestCode == WRITE_VIDEO_CODE) {
            if (grantResults.length > 1
                    && grantResults[0] == PermissionChecker.PERMISSION_GRANTED
                    && grantResults[1] == PermissionChecker.PERMISSION_GRANTED) {
                saveVideoToGallery(WRITE_VIDEO_PATH, ALBUM_NAME, pendingResult);
            } else {
                pendingResult.success(false);
            }
            pendingResult = null;
            return true;
        }
        return false;
    }
}
