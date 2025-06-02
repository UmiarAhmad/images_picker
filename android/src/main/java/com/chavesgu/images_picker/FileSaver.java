package com.chavesgu.images_picker;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static java.io.File.separator;

public class FileSaver {

    /**
     * Сохраняет видео в галерею (папка Movies/<albumName>).
     * Возвращает true, если всё записалось без ошибок, false иначе.
     * <p>
     * Важно: до вызова этого метода вы должны проверить у пользователя
     * разрешение WRITE_EXTERNAL_STORAGE (для API < 29).
     */
    public static boolean saveVideo(
            @NonNull Context context,
            @NonNull String filePath,
            @Nullable String albumName
    ) {
        boolean saveRes = false;
        String folderName = context.getApplicationInfo()
                .loadLabel(context.getPackageManager())
                .toString();
        if (albumName != null) folderName = albumName;

        // API >= 29 → используем RELATIVE_PATH и IS_PENDING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
            values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + folderName);
            values.put(MediaStore.Video.Media.IS_PENDING, true);

            // Вставляем запись, получаем Uri
            Uri uri = context.getContentResolver()
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;

            // Записываем сам файл через ParcelFileDescriptor
            try (ParcelFileDescriptor pfd =
                         context.getContentResolver().openFileDescriptor(uri, "w");
                 OutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {

                saveRes = saveToStream(filePath, out);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            // Убираем флаг PENDING
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, false);
            context.getContentResolver().update(uri, values, null, null);
        }
        // API < 29 → записываем вручную на внешнюю директорию + уведомляем MediaScanner
        else {
            String storagePath = Environment.getExternalStorageDirectory().toString()
                    + separator + folderName;
            File directory = new File(storagePath);
            if (!directory.exists() && !directory.mkdirs()) {
                return false;
            }

            String fileName = System.currentTimeMillis() + ".mp4";
            File file = new File(directory, fileName);
            try (OutputStream out = new FileOutputStream(file)) {
                saveRes = saveToStream(filePath, out);
                Uri tmpUri = Uri.fromFile(file);
                context.sendBroadcast(new Intent(
                        Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, tmpUri));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return false;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            // Вставляем «старым» способом в MediaStore
            ContentValues oldValues = new ContentValues();
            oldValues.put(MediaStore.Video.Media.DATA, file.getAbsolutePath());
            context.getContentResolver()
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, oldValues);
        }

        return saveRes;
    }

    /**
     * Сохраняет картинку (Bitmap) в галерею (папка Pictures/<albumName>).
     * Возвращает true, если записалось успешно, false иначе.
     * <p>
     * Важно: до вызова этого метода вы должны проверить разрешение WRITE_EXTERNAL_STORAGE
     * (для API < 29). На Android 10+ разрешение не нужно, если вы пишете в RELATIVE_PATH.
     */
    public static boolean saveImage(
            @NonNull Context context,
            @NonNull Bitmap bitmap,
            @NonNull String suffix,
            @Nullable String albumName
    ) {
        boolean saveRes = false;
        String folderName = context.getApplicationInfo()
                .loadLabel(context.getPackageManager())
                .toString();
        if (albumName != null) folderName = albumName;

        // API >= 29 → RELATIVE_PATH + IS_PENDING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.MIME_TYPE, mimeTypeFromSuffix(suffix));
            values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
            values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + folderName);
            values.put(MediaStore.Images.Media.IS_PENDING, true);

            Uri uri = context.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;

            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                saveRes = saveBitmapToStream(bitmap, out);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, false);
            context.getContentResolver().update(uri, values, null, null);
        }
        // API < 29 → записываем вручную + MediaScanner
        else {
            String storagePath = Environment.getExternalStorageDirectory().toString()
                    + separator + folderName;
            File directory = new File(storagePath);
            if (!directory.exists() && !directory.mkdirs()) {
                return false;
            }

            String fileName = System.currentTimeMillis() + "." + suffix;
            File file = new File(directory, fileName);
            try (OutputStream out = new FileOutputStream(file)) {
                saveRes = saveBitmapToStream(bitmap, out);
                Uri tmpUri = Uri.fromFile(file);
                context.sendBroadcast(new Intent(
                        Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, tmpUri));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return false;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            ContentValues oldValues = new ContentValues();
            oldValues.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());
            context.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, oldValues);
        }

        return saveRes;
    }

    /**
     * Возвращает правильный MIME_TYPE на основании расширения файла.
     */
    @NonNull
    private static String mimeTypeFromSuffix(@NonNull String suffix) {
        String type = "image/" + suffix;
        if ("jpg".equalsIgnoreCase(suffix) || "jpeg".equalsIgnoreCase(suffix)) {
            type = "image/jpeg";
        }
        return type;
    }

    /**
     * Копирует байты из файла по пути filePath в OutputStream.
     */
    private static boolean saveToStream(
            @NonNull String filePath,
            @NonNull OutputStream outputStream
    ) {
        try (InputStream in = new FileInputStream(new File(filePath))) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                outputStream.write(buf, 0, len);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Сжимает Bitmap и записывает его в OutputStream.
     */
    private static boolean saveBitmapToStream(
            @NonNull Bitmap bitmap,
            @Nullable OutputStream out
    ) {
        if (out == null) return false;
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        try {
            out.close();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
