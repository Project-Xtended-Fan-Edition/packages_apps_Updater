/*
 * Copyright (C) 2017-2022 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.xtended.updater.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.xtended.updater.misc.Constants;
import com.xtended.updater.misc.FileUtils;
import com.xtended.updater.misc.Utils;
import com.xtended.updater.model.UpdateInfo;
import com.xtended.updater.model.UpdateStatus;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

class UpdateInstaller {

    private static final String TAG = "UpdateInstaller";

    private static UpdateInstaller sInstance = null;
    private static String sInstallingUpdate = null;

    private Thread mPrepareUpdateThread;
    private volatile boolean mCanCancel;

    private final Context mContext;
    private final UpdaterController mUpdaterController;

    private UpdateInstaller(Context context, UpdaterController controller) {
        mContext = context.getApplicationContext();
        mUpdaterController = controller;
    }

    static synchronized UpdateInstaller getInstance(Context context,
            UpdaterController updaterController) {
        if (sInstance == null) {
            sInstance = new UpdateInstaller(context, updaterController);
        }
        return sInstance;
    }

    static synchronized boolean isInstalling() {
        return sInstallingUpdate != null;
    }

    static synchronized boolean isInstalling(String downloadId) {
        return sInstallingUpdate != null && sInstallingUpdate.equals(downloadId);
    }

    void install(String downloadId) {
        if (isInstalling()) {
            Log.e(TAG, "Already installing an update");
            return;
        }

        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        long buildTimestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0);
        long lastBuildTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP,
                buildTimestamp);
        boolean isReinstalling = buildTimestamp == lastBuildTimestamp;
        preferences.edit()
                .putLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, buildTimestamp)
                .putLong(Constants.PREF_INSTALL_NEW_TIMESTAMP, update.getTimestamp())
                .putString(Constants.PREF_INSTALL_PACKAGE_PATH, update.getFile().getAbsolutePath())
                .putBoolean(Constants.PREF_INSTALL_AGAIN, isReinstalling)
                .putBoolean(Constants.PREF_INSTALL_NOTIFIED, false)
                .apply();

        if (Utils.isEncrypted(mContext, update.getFile())) {
            // uncrypt rewrites the file so that it can be read without mounting
            // the filesystem, so create a copy of it.
            prepareForUncryptAndInstall(update);
        } else {
            installPackage(update.getFile(), downloadId);
        }
    }

    private void installPackage(File update, String downloadId) {
        try {
            android.os.RecoverySystem.installPackage(mContext, update);
        } catch (IOException e) {
            Log.e(TAG, "Could not install update", e);
            mUpdaterController.getActualUpdate(downloadId)
                    .setStatus(UpdateStatus.INSTALLATION_FAILED);
            mUpdaterController.notifyUpdateChange(downloadId);
        }
    }

    private synchronized void prepareForUncryptAndInstall(UpdateInfo update) {
        String uncryptFilePath = update.getFile().getAbsolutePath() + Constants.UNCRYPT_FILE_EXT;
        File uncryptFile = new File(uncryptFilePath);

        Runnable copyUpdateRunnable = new Runnable() {
            private long mLastUpdate = -1;

            final FileUtils.ProgressCallBack mProgressCallBack = new FileUtils.ProgressCallBack() {
                @Override
                public void update(int progress) {
                    long now = SystemClock.elapsedRealtime();
                    if (mLastUpdate < 0 || now - mLastUpdate > 500) {
                        mUpdaterController.getActualUpdate(update.getDownloadId())
                                .setInstallProgress(progress);
                        mUpdaterController.notifyInstallProgress(update.getDownloadId());
                        mLastUpdate = now;
                    }
                }
            };

            @Override
            public void run() {
                try {
                    mCanCancel = true;
                    FileUtils.copyFile(update.getFile(), uncryptFile, mProgressCallBack);
                    try {
                        Set<PosixFilePermission> perms = new HashSet<>();
                        perms.add(PosixFilePermission.OWNER_READ);
                        perms.add(PosixFilePermission.OWNER_WRITE);
                        perms.add(PosixFilePermission.OTHERS_READ);
                        perms.add(PosixFilePermission.GROUP_READ);
                        Files.setPosixFilePermissions(uncryptFile.toPath(), perms);
                    } catch (IOException exception) {}

                    mCanCancel = false;
                    if (mPrepareUpdateThread.isInterrupted()) {
                        mUpdaterController.getActualUpdate(update.getDownloadId())
                                .setStatus(UpdateStatus.INSTALLATION_CANCELLED);
                        mUpdaterController.getActualUpdate(update.getDownloadId())
                                .setInstallProgress(0);
                        //noinspection ResultOfMethodCallIgnored
                        uncryptFile.delete();
                    } else {
                        installPackage(uncryptFile, update.getDownloadId());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Could not copy update", e);
                    //noinspection ResultOfMethodCallIgnored
                    uncryptFile.delete();
                    mUpdaterController.getActualUpdate(update.getDownloadId())
                            .setStatus(UpdateStatus.INSTALLATION_FAILED);
                } finally {
                    synchronized (UpdateInstaller.this) {
                        mCanCancel = false;
                        mPrepareUpdateThread = null;
                        sInstallingUpdate = null;
                    }
                    mUpdaterController.notifyUpdateChange(update.getDownloadId());
                }
            }
        };

        mPrepareUpdateThread = new Thread(copyUpdateRunnable);
        mPrepareUpdateThread.start();
        sInstallingUpdate = update.getDownloadId();
        mCanCancel = false;

        mUpdaterController.getActualUpdate(update.getDownloadId())
                .setStatus(UpdateStatus.INSTALLING);
        mUpdaterController.notifyUpdateChange(update.getDownloadId());
    }

    public synchronized void cancel() {
        if (!mCanCancel) {
            Log.d(TAG, "Nothing to cancel");
            return;
        }
        mPrepareUpdateThread.interrupt();
    }
}
