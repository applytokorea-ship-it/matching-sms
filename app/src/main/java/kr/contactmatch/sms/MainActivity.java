package kr.contactmatch.sms;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLDecoder;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final int REQ_CONTACTS = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // 발송완료 기록(localStorage)
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        webView.addJavascriptInterface(new Bridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "파일 선택"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl("file:///android_asset/index.html");
    }

    /** sms: 링크를 기본 문자앱으로 넘긴다 */
    private boolean handleUrl(String url) {
        if (url.startsWith("sms:") || url.startsWith("smsto:")) {
            try {
                String rest = url.substring(url.indexOf(':') + 1);
                String number = rest;
                String body = null;
                int q = rest.indexOf('?');
                if (q < 0) q = rest.indexOf('&');
                if (q >= 0) {
                    number = rest.substring(0, q);
                    String query = rest.substring(q + 1);
                    for (String part : query.split("&")) {
                        if (part.startsWith("body=")) {
                            body = URLDecoder.decode(part.substring(5).replace("+", "%2B"), "UTF-8");
                        }
                    }
                }
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
                if (body != null) i.putExtra("sms_body", body);
                startActivity(i);
            } catch (Exception e) {
                toast("문자앱을 열 수 없습니다");
            }
            return true;
        }
        if (url.startsWith("tel:")) {
            try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(url))); } catch (Exception ignored) {}
            return true;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
            return true;
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int k = 0; k < n; k++) result[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    // ---------------- 연락처 ----------------

    private void loadContactsAndSend() {
        new Thread(() -> {
            JSONArray arr = new JSONArray();
            try {
                ContentResolver cr = getContentResolver();
                Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER},
                        null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        JSONObject o = new JSONObject();
                        o.put("name", c.getString(0) == null ? "" : c.getString(0));
                        o.put("tel", c.getString(1) == null ? "" : c.getString(1));
                        arr.put(o);
                    }
                    c.close();
                }
            } catch (Exception e) {
                runOnUiThread(() -> toast("연락처를 읽지 못했습니다"));
            }
            final String js = "window.onAndroidContacts && window.onAndroidContacts(" + JSONObject.quote(arr.toString()) + ")";
            runOnUiThread(() -> webView.evaluateJavascript(js, null));
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadContactsAndSend();
            } else {
                toast("연락처 권한이 필요합니다 (설정 > 앱 > 매칭문자 > 권한)");
            }
        }
    }

    // ---------------- 파일 저장 ----------------

    private String saveToDownloads(String base64, String fileName) throws Exception {
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        String mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("저장 위치를 만들 수 없음");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(bytes);
            }
            return "다운로드 폴더";
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File f = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(bytes);
            }
            return f.getAbsolutePath();
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    /** 웹화면(JS)에서 window.Android.xxx() 로 호출 */
    private class Bridge {
        @JavascriptInterface
        public void requestContacts() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    loadContactsAndSend();
                } else {
                    requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
                }
            });
        }

        @JavascriptInterface
        public void saveFile(String base64, String fileName) {
            runOnUiThread(() -> {
                try {
                    String where = saveToDownloads(base64, fileName);
                    toast(fileName + " 저장됨 (" + where + ")");
                } catch (Exception e) {
                    toast("저장 실패: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public boolean isApp() { return true; }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
