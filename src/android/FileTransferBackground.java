package com.spoon.backgroundfileupload;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import com.github.pwittchen.reactivenetwork.library.rx2.ReactiveNetwork;
import com.sromku.simple.storage.SimpleStorage;
import com.sromku.simple.storage.Storage;
import com.sromku.simple.storage.helpers.OrderType;
import android.app.NotificationChannel;
import net.gotev.uploadservice.UploadService;
import net.gotev.uploadservice.UploadServiceConfig;
import net.gotev.uploadservice.data.UploadInfo;
import net.gotev.uploadservice.data.UploadNotificationAction;
import net.gotev.uploadservice.data.UploadNotificationConfig;
import net.gotev.uploadservice.data.UploadNotificationStatusConfig;
import net.gotev.uploadservice.network.ServerResponse;
import net.gotev.uploadservice.observer.request.RequestObserver;
import net.gotev.uploadservice.observer.request.RequestObserverDelegate;
import net.gotev.uploadservice.okhttp.OkHttpStack;
import net.gotev.uploadservice.protocols.multipart.MultipartUploadRequest;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class FileTransferBackground extends CordovaPlugin {
    private CallbackContext uploadCallback;
    private boolean isNetworkAvailable = false;
    private Long lastProgressTimestamp = 0L;
    private boolean ready = false;
    private Disposable networkObservable;
    private RequestObserverDelegate broadcastReceiver = new RequestObserverDelegate() {
        @Override
        public void onProgress(Context context, UploadInfo uploadInfo) {
            Long currentTimestamp = System.currentTimeMillis() / 1000;
            if (currentTimestamp - lastProgressTimestamp >= 1) {
                lastProgressTimestamp = currentTimestamp;
                JSONObject objResult = new JSONObject(new HashMap() {{
                    put("id", uploadInfo.getUploadId());
                    put("progress", uploadInfo.getProgressPercent());
                    put("state", "UPLOADING");
                }});
                sendCallback(objResult);
            }
        }

        @Override
        public void onError(final Context context, final UploadInfo uploadInfo, final Throwable exception) {
            String errorMsg = exception != null ? exception.getMessage() : "";
            logMessage("eventLabel = 'upload failed' uploadId = '" + uploadInfo.getUploadId() + "' error = '" + errorMsg + "'");
//            deletePendingUploadAndSendEvent(new JSONObject(new HashMap() {{
//                put("id", uploadInfo.getUploadId());
//                put("state", "FAILED");
//                put("error", "upload failed: " + errorMsg);
//                put("errorCode", serverResponse != null ? serverResponse.getHttpCode() : 0);
//            }}));
        }

        @Override
        public void onSuccess(Context context, UploadInfo uploadInfo, ServerResponse serverResponse) {

        }

        @Override
        public void onCompleted(Context context, UploadInfo uploadInfo) {
//            logMessage("eventLabel = 'upload completed' uploadId = '" + uploadInfo.getUploadId() + "' response = '" + serverResponse.getBodyAsString() + "'");
//            deletePendingUploadAndSendEvent(new JSONObject(new HashMap() {{
//                put("id", uploadInfo.getUploadId());
//                put("state", "UPLOADED");
//                put("serverResponse", serverResponse.getBodyAsString());
//                put("statusCode", serverResponse.getHttpCode());
//            }}));
        }

        @Override
        public void onCompletedWhileNotObserving(){

        }
    };

    public void createAndSendEvent(JSONObject obj) {
        UploadEvent event = UploadEvent.create(obj);
        sendCallback(event.dataRepresentation());
    }

    public void sendCallback(JSONObject obj) {
        /* we check the webview has not been destroyed*/
        if (ready) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, obj);
            result.setKeepCallback(true);
            uploadCallback.sendPluginResult(result);
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        if (action.equalsIgnoreCase("initManager")) {
            uploadCallback = callbackContext;
            this.initManager(args.get(0).toString());
        } else if (action.equalsIgnoreCase("removeUpload")) {
            this.removeUpload(args.get(0).toString(), callbackContext);
        } else if (action.equalsIgnoreCase("acknowledgeEvent")) {
            this.acknowledgeEvent(args.getString(0), callbackContext);
        } else if (action.equalsIgnoreCase("startUpload")) {
            this.upload((JSONObject) args.get(0));
        } else if (action.equalsIgnoreCase("destroy")) {
            this.destroy();
        }
        return true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel =new  NotificationChannel(
                    "com.spoon.backgroundfileupload.channel",
                    "TestApp Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) cordova.getActivity()
                    .getApplication()
                    .getApplicationContext()
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private void initManager(String options) throws IllegalStateException {
        if (this.ready) {
            throw new IllegalStateException("initManager was called twice");
        }
        this.ready = true;

//        new RequestObserver(this, broadcastReceiver).register();
        String notificationChannelID = "TestChannel";
        UploadServiceConfig.initialize(
                this.cordova.getActivity().getApplication(),
                notificationChannelID,
                BuildConfig.DEBUG
        );
        int parallelUploadsLimit = 1;
        try {
            JSONObject settings = new JSONObject(options);
            parallelUploadsLimit = settings.getInt("parallelUploadsLimit");
        } catch (JSONException error) {
            logMessage("eventLabel = 'could not read parallelUploadsLimit from config' error = '" + error.getMessage() + "'");
        }
        UploadServiceConfig.setHttpStack(new OkHttpStack());
        this.createNotificationChannel();
//        cordova.getActivity().getApplicationContext().registerReceiver(broadcastReceiver, new IntentFilter(UploadService.NAMESPACE + ".uploadservice.broadcast.status"));

        networkObservable = ReactiveNetwork
                .observeNetworkConnectivity(cordova.getContext())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(connectivity -> {
                    this.isNetworkAvailable = connectivity.state() == NetworkInfo.State.CONNECTED;
                    if (this.isNetworkAvailable) {
                        logMessage("eventLabel='Network now available, restarting pending uploads'");
                        uploadPendingList();
                    }
                });

        //mark v1 uploads as failed
        migrateOldUploads();

        //broadcast all completed upload events
        for (UploadEvent event : UploadEvent.all()) {
            sendCallback(event.dataRepresentation());
        }
    }

    private void migrateOldUploads() {
        Storage storage = SimpleStorage.getInternalStorage(this.cordova.getActivity().getApplicationContext());
        String uploadDirectoryName = "FileTransferBackground";
        if (storage.isDirectoryExists(uploadDirectoryName)) {
            for (String uploadId : getOldUploadIds()) {
                createAndSendEvent(new JSONObject(new HashMap() {{
                    put("id", uploadId);
                    put("state", "FAILED");
                    put("errorCode", 0);
                    put("error", "upload failed");
                }}));
            }
            // remove all old uploads
            storage.deleteDirectory(uploadDirectoryName);
        }
    }

    private ArrayList<String> getOldUploadIds() {
        Storage storage = SimpleStorage.getInternalStorage(this.cordova.getActivity().getApplicationContext());
        String uploadDirectoryName = "FileTransferBackground";
        ArrayList<String> previousUploads = new ArrayList();
        List<File> files = storage.getFiles(uploadDirectoryName, OrderType.DATE);
        for (File file : files) {
            if (file.getName().endsWith(".json")) {
                String content = storage.readTextFile(uploadDirectoryName, file.getName());
                if (content != null) {
                    try {
                        previousUploads.add(new JSONObject(content).getString("id"));
                    } catch (JSONException exception) {
                        logMessage("eventLabel='could not read old uploads' error='" + exception.getMessage() + "'");
                    }
                }
            }
        }
        return previousUploads;
    }

    private void uploadPendingList() {
        List<PendingUpload> previousUploads = PendingUpload.all();
        for (PendingUpload upload : previousUploads) {
            JSONObject obj = null;
            try {
                obj = new JSONObject(upload.data);
            } catch (JSONException exception) {
                logMessage("eventLabel='could not parse pending upload' uploadId='" + upload.uploadId + "' error='" + exception.getMessage() + "'");
            }
            if (obj != null) {
                this.upload(obj);
            }
        }
    }


    private void upload(JSONObject jsonPayload) {
        HashMap payload = null;
        try {
            payload = convertToHashMap(jsonPayload);
        } catch (JSONException error) {
            logMessage("eventLabel='could not read id from payload' error:'" + error.getMessage() + "'");
        }
        if (payload == null) return;
        String id = payload.get("id").toString();
        if (UploadService.getTaskList().contains(id)) {
            logMessage("eventLabel='upload is already being uploaded. ignoring re-upload request' uploadId='" + id + "'");
            return;
        }
        logMessage("eventLabel='adding upload' uploadId='" + id + "'");
        PendingUpload.create(jsonPayload);
        if (isNetworkAvailable) {
            MultipartUploadRequest request = null;
            try {

                request = new MultipartUploadRequest(this.cordova.getActivity().getApplicationContext(), payload.get("serverUrl").toString())
                        .setUploadID(id)
                        .setMethod("POST")
                        .addFileToUpload(payload.get("filePath").toString(), payload.get("fileKey").toString())
                        .setMaxRetries(0);
            } catch (IllegalArgumentException error){
                sendAddingUploadError(id, error);
                return;
            } catch (FileNotFoundException error) {
                sendAddingUploadError(id, error);
                return;
            }

            request.setNotificationConfig((context, uploadId) -> getNotificationConfiguration(uploadId));

            try {
                HashMap<String, Object> headers = convertToHashMap((JSONObject) payload.get("headers"));
                for (String key : headers.keySet()) {
                    request.addHeader(key, headers.get(key).toString());
                }
            } catch (JSONException exception) {
                logMessage("eventLabel='could not parse request headers' uploadId='" + id + "' error='" + exception.getMessage() + "'");
                sendAddingUploadError(id, exception);
                return;
            }
            try {
                HashMap<String, Object> parameters = convertToHashMap((JSONObject) payload.get("parameters"));
                for (String key : parameters.keySet()) {
                    request.addParameter(key, parameters.get(key).toString());
                }
            } catch (JSONException exception) {
                logMessage("eventLabel='could not parse request parameters' uploadId='" + id + "' error='" + exception.getMessage() + "'");
                sendAddingUploadError(id, exception);
                return;
            }
            request.startUpload();
        } else {
            logMessage("eventLabel='No network available, upload has been queued' uploadId='" + id + "'");
        }
    }

    private void sendAddingUploadError(String uploadId, Exception error) {
        deletePendingUploadAndSendEvent(new JSONObject(new HashMap() {{
            put("id", uploadId);
            put("state", "FAILED");
            put("errorCode", 0);
            put("error", error.getMessage());
        }}));
    }

    public void deletePendingUploadAndSendEvent(JSONObject obj) {
        try {
            PendingUpload.remove(obj.getString("id"));
        } catch (JSONException error) {
            logMessage("eventLabel='could not delete pending upload' error='" + error.getMessage() + "'");
        }
        createAndSendEvent(obj);
    }

    private UploadNotificationConfig getNotificationConfiguration(String title) {
        Intent intent = new Intent(cordova.getContext(), cordova.getActivity().getClass());
        PendingIntent clickIntent = PendingIntent.getActivity(cordova.getContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        final boolean autoClear = false;
        final Bitmap largeIcon = null;
        final boolean clearOnAction = true;
        final ArrayList<UploadNotificationAction> noActions = new ArrayList<>(1);
        final ArrayList<UploadNotificationAction> progressActions = new ArrayList<>(0);

        UploadNotificationStatusConfig progress = new UploadNotificationStatusConfig(
                "Upload in progress",
                "",
                R.drawable.ic_upload,
                Color.BLUE,
                largeIcon,
                clickIntent,
                progressActions,
                clearOnAction,
                autoClear
        );

        UploadNotificationStatusConfig success = new UploadNotificationStatusConfig(
                "Upload completed",
                "",
                R.drawable.ic_upload,
                Color.GREEN,
                largeIcon,
                clickIntent,
                noActions,
                clearOnAction,
                autoClear
        );

        UploadNotificationStatusConfig error = new UploadNotificationStatusConfig(
                "Upload error",
                "",
                R.drawable.ic_upload,
                Color.RED,
                largeIcon,
                clickIntent,
                noActions,
                clearOnAction,
                autoClear
        );

        UploadNotificationConfig config = new UploadNotificationConfig("com.spoon.backgroundfileupload.channel", false, progress, success, error, null);
        return config;
    }

    private HashMap<String, Object> convertToHashMap(JSONObject jsonObject) throws JSONException {
        HashMap<String, Object> hashMap = new HashMap<>();
        if (jsonObject != null) {
            Iterator<?> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = (String) keys.next();
                Object value = jsonObject.get(key);
                hashMap.put(key, value);
            }
        }
        return hashMap;
    }

    private void logMessage(String message) {
        Log.d("CordovaBackgroundUpload", message);
    }

    private void removeUpload(String fileId, CallbackContext context) {
        PendingUpload.remove(fileId);
        UploadService.stopUpload(fileId);
        PluginResult result = new PluginResult(PluginResult.Status.OK);
        result.setKeepCallback(true);
        context.sendPluginResult(result);
    }

    private void acknowledgeEvent(String eventId, CallbackContext context) {
        String onlyNumbers = eventId.replaceAll("\\D+", "");
        UploadEvent.destroy(Long.valueOf(onlyNumbers).longValue());
        PluginResult result = new PluginResult(PluginResult.Status.OK);
        result.setKeepCallback(true);
        context.sendPluginResult(result);
    }

    public void onDestroy() {
       logMessage("eventLabel='plugin onDestroy'");
       destroy();
    }

     public void destroy() {
        ready = false;
        if (networkObservable != null)
            networkObservable.dispose();
//        broadcastReceiver.unregister(cordova.getActivity().getApplicationContext());
    }
}