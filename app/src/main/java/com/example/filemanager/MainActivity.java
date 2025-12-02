package com.example.filemanager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION = 100;
    private static final int REQUEST_MANAGE_STORAGE = 101;
    private static final long SIZE_LIMIT = 20 * 1024 * 1024 * 1024; // 20GB

    private static final String OFFICIAL_PATH = "/storage/emulated/0/Android/data/com.mihoyou.yuanshen/file";
    private static final String BILIBILI_PATH = "/storage/emulated/0/Android/data/com.mihoyou.ys.bilibili/file";
    private static final String EXCLUDE_FOLDER = "unityvulkanpso";
    private static final String LOG_FILE = "/storage/emulated/0/Android/data/com.example.filemanager/files/move_log.ser";

    private TextView tvStatus;
    private Button btnCheckSize, btnMoveFiles, btnMoveBack;
    private Handler handler = new Handler(Looper.getMainLooper());
    private List<FileMoveRecord> moveRecords = new ArrayList<>();
    private boolean isOfficialServer = true; // 默认官服

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        btnCheckSize = findViewById(R.id.btn_check_size);
        btnMoveFiles = findViewById(R.id.btn_move_files);
        btnMoveBack = findViewById(R.id.btn_move_back);

        btnCheckSize.setOnClickListener(v -> checkFolderSize());
        btnMoveFiles.setOnClickListener(v -> moveFiles());
        btnMoveBack.setOnClickListener(v -> moveFilesBack());

        checkPermissions();
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限获取成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要存储权限才能使用", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "需要所有文件访问权限才能使用", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void checkFolderSize() {
        tvStatus.setText(R.string.size_calculating);
        new Thread(() -> {
            // 检查官服文件夹大小
            long officialSize = calculateFolderSize(new File(OFFICIAL_PATH));
            // 检查B服文件夹大小
            long bilibiliSize = calculateFolderSize(new File(BILIBILI_PATH));
            
            String sizeStr = formatSize(officialSize);
            
            // 判断当前服务器：大于20G的文件夹所在的服务器即为当前服务器
            isOfficialServer = officialSize > SIZE_LIMIT;
            
            handler.post(() -> {
                // 显示当前服务器和大小
                String serverText = isOfficialServer ? getString(R.string.server_official) : getString(R.string.server_bilibili);
                tvStatus.setText(String.format("%s\n%s", serverText, getString(R.string.size_result, sizeStr)));
                
                // 更新移动按钮文字
                updateMoveButtonText();
                
                if (isOfficialServer || bilibiliSize > SIZE_LIMIT) {
                    Toast.makeText(this, R.string.size_exceeded, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.size_normal, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }
    
    /**
     * 更新移动按钮文字
     */
    private void updateMoveButtonText() {
        if (isOfficialServer) {
            btnMoveFiles.setText(R.string.btn_move_to_bilibili);
        } else {
            btnMoveFiles.setText(R.string.btn_move_to_official);
        }
    }

    private long calculateFolderSize(File folder) {
        long size = 0;
        if (folder.exists() && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        size += file.length();
                    } else {
                        size += calculateFolderSize(file);
                    }
                }
            }
        }
        return size;
    }

    private String formatSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return String.format("%.2f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private void moveFiles() {
        tvStatus.setText(R.string.moving_files);
        new Thread(() -> {
            boolean success = false;
            try {
                // 根据当前服务器确定源路径和目标路径
                File sourceDir = isOfficialServer ? new File(OFFICIAL_PATH) : new File(BILIBILI_PATH);
                File targetDir = isOfficialServer ? new File(BILIBILI_PATH) : new File(OFFICIAL_PATH);
                moveRecords.clear();

                if (sourceDir.exists() && sourceDir.isDirectory()) {
                    if (!targetDir.exists()) {
                        targetDir.mkdirs();
                    }

                    File[] files = sourceDir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (!file.getName().equals(EXCLUDE_FOLDER)) {
                                String relativePath = file.getName();
                                File targetFile = new File(targetDir, relativePath);
                                if (moveFile(file, targetFile)) {
                                    moveRecords.add(new FileMoveRecord(file.getAbsolutePath(), targetFile.getAbsolutePath()));
                                }
                            }
                        }
                    }

                    // 保存移动记录
                    saveMoveRecords();
                    success = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            boolean finalSuccess = success;
            handler.post(() -> {
                if (finalSuccess) {
                    tvStatus.setText(R.string.move_completed);
                    Toast.makeText(this, R.string.move_completed, Toast.LENGTH_SHORT).show();
                    // 移动完成后重新检查服务器状态
                    checkFolderSize();
                } else {
                    tvStatus.setText(R.string.move_failed);
                    Toast.makeText(this, R.string.move_failed, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private boolean moveFile(File source, File target) {
        if (source.isDirectory()) {
            if (!target.exists()) {
                if (!target.mkdirs()) {
                    return false;
                }
            }
            File[] files = source.listFiles();
            if (files != null) {
                for (File file : files) {
                    File targetFile = new File(target, file.getName());
                    if (!moveFile(file, targetFile)) {
                        return false;
                    }
                }
            }
            return source.delete();
        } else {
            if (target.exists()) {
                return true; // 不覆盖原有文件
            }
            try (FileInputStream fis = new FileInputStream(source);
                 FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
                int length;
                while ((length = fis.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                }
                return source.delete();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    private void moveFilesBack() {
        tvStatus.setText(R.string.moving_back);
        new Thread(() -> {
            boolean success = false;
            try {
                // 加载移动记录
                if (loadMoveRecords()) {
                    for (FileMoveRecord record : moveRecords) {
                        File source = new File(record.targetPath);
                        File target = new File(record.originPath);
                        if (source.exists()) {
                            // 创建目标目录
                            if (source.isDirectory()) {
                                target.mkdirs();
                            } else {
                                target.getParentFile().mkdirs();
                            }
                            moveFile(source, target);
                        }
                    }
                    // 清空移动记录
                    moveRecords.clear();
                    saveMoveRecords();
                    success = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            boolean finalSuccess = success;
            handler.post(() -> {
                if (finalSuccess) {
                    tvStatus.setText(R.string.move_back_completed);
                    Toast.makeText(this, R.string.move_back_completed, Toast.LENGTH_SHORT).show();
                } else {
                    tvStatus.setText(R.string.move_back_failed);
                    Toast.makeText(this, R.string.no_log_found, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void saveMoveRecords() {
        try {
            File logDir = new File(getExternalFilesDir(null).getAbsolutePath());
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            File logFile = new File(LOG_FILE);
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(logFile))) {
                oos.writeObject(moveRecords);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean loadMoveRecords() {
        try {
            File logFile = new File(LOG_FILE);
            if (logFile.exists()) {
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(logFile))) {
                    moveRecords = (List<FileMoveRecord>) ois.readObject();
                    return !moveRecords.isEmpty();
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }

    static class FileMoveRecord implements Serializable {
        String originPath;
        String targetPath;

        FileMoveRecord(String originPath, String targetPath) {
            this.originPath = originPath;
            this.targetPath = targetPath;
        }
    }
}