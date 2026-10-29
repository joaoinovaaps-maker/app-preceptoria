package br.residencia.preceptoria;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * App Android da Plataforma da Preceptoria.
 * Abre a plataforma web (BuildConfig.APP_URL) e acrescenta o que o navegador interno não faz sozinho:
 * GPS, câmera/galeria para anexos, salvar arquivos (PDF/planilhas), imprimir e abrir links externos.
 */
public class MainActivity extends Activity {

    private static final int REQ_ARQUIVO = 10;
    private static final int REQ_LOCAL = 20;

    private WebView web;
    private ProgressBar barra;
    private String hostApp;
    private String ultimaUrl;

    private ValueCallback<Uri[]> retornoArquivo;
    private Uri fotoCamera;

    private String origemGeo;
    private GeolocationPermissions.Callback retornoGeo;

    /** Script injetado em cada página: redireciona downloads, window.open e impressão para o app. */
    private static final String PONTE_JS =
        "(function(){if(window.__apkPonte)return;window.__apkPonte=1;" +
        "function enviar(u,n){fetch(u).then(function(r){return r.blob();}).then(function(b){" +
        "var f=new FileReader();f.onload=function(){AndroidApp.salvar(String(f.result),n||'',b.type||'');};f.readAsDataURL(b);" +
        "}).catch(function(){AndroidApp.aviso('Não foi possível gerar o arquivo.');});}" +
        "function ehArq(u){return /^(blob:|data:)/.test(String(u||''));}" +
        "var oc=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){if(this.hasAttribute('download')&&ehArq(this.href)){enviar(this.href,this.getAttribute('download'));return;}return oc.apply(this,arguments);};" +
        "var od=HTMLAnchorElement.prototype.dispatchEvent;" +
        "HTMLAnchorElement.prototype.dispatchEvent=function(e){if(e&&e.type==='click'&&this.hasAttribute('download')&&ehArq(this.href)){enviar(this.href,this.getAttribute('download'));return true;}return od.apply(this,arguments);};" +
        "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;if(a&&ehArq(a.href)){e.preventDefault();enviar(a.href,a.getAttribute('download'));}},true);" +
        "window.open=function(u){if(!u)return null;if(ehArq(u)){enviar(u,'');}else{AndroidApp.abrirExterno(String(new URL(u,location.href)));}" +
        "return {close:function(){},focus:function(){},document:{write:function(){},close:function(){}}};};" +
        "window.print=function(){AndroidApp.imprimir(document.title||'Documento');};" +
        "})();";

    @Override
    protected void onCreate(Bundle salvo) {
        super.onCreate(salvo);
        hostApp = Uri.parse(BuildConfig.APP_URL).getHost();

        FrameLayout raiz = new FrameLayout(this);
        raiz.setFitsSystemWindows(true);
        raiz.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        barra = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barra.setMax(100);
        barra.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#F2B705")));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        lp.gravity = Gravity.TOP;
        raiz.addView(barra, lp);
        setContentView(raiz);

        configurarWebView();

        if (salvo != null) {
            web.restoreState(salvo);
        } else {
            web.loadUrl(BuildConfig.APP_URL);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void configurarWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " PreceptoriaApp/" + BuildConfig.VERSION_NAME);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Ponte(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return tratarNavegacao(req.getUrl());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (url != null && url.startsWith("http")) ultimaUrl = url;
                barra.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                barra.setVisibility(View.GONE);
                v.evaluateJavascript(PONTE_JS, null);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError erro) {
                if (req.isForMainFrame()) {
                    v.loadUrl("file:///android_asset/erro.html");
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                barra.setProgress(p);
                if (p >= 100) barra.setVisibility(View.GONE);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origem, GeolocationPermissions.Callback cb) {
                if (temPermissaoLocal()) {
                    cb.invoke(origem, true, false);
                } else {
                    origemGeo = origem;
                    retornoGeo = cb;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCAL);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (retornoArquivo != null) retornoArquivo.onReceiveValue(null);
                retornoArquivo = cb;
                return abrirSeletor(params);
            }
        });

        web.setDownloadListener((url, ua, disposicao, mime, tamanho) -> {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                web.evaluateJavascript("(function(){var u=" + jsString(url) + ";fetch(u).then(function(r){return r.blob();}).then(function(b){var f=new FileReader();f.onload=function(){AndroidApp.salvar(String(f.result),'',b.type||'');};f.readAsDataURL(b);});})();", null);
            } else {
                abrirFora(Uri.parse(url));
            }
        });
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** Páginas da plataforma e do Apps Script ficam no app; o resto abre no navegador ou no app correspondente. */
    private boolean tratarNavegacao(Uri uri) {
        String esquema = uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (esquema.equals("https") && (host.equals(hostApp) || host.equals("script.google.com") || host.endsWith(".googleusercontent.com"))) {
            return false;
        }
        abrirFora(uri);
        return true;
    }

    private void abrirFora(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nenhum aplicativo para abrir este link.", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Localização ----------
    private boolean temPermissaoLocal() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_LOCAL && retornoGeo != null) {
            boolean ok = temPermissaoLocal();
            retornoGeo.invoke(origemGeo, ok, false);
            if (!ok) Toast.makeText(this, "Sem permissão de localização, o registro de ponto não funciona.", Toast.LENGTH_LONG).show();
            retornoGeo = null;
            origemGeo = null;
        }
    }

    // ---------- Anexos: câmera ou arquivos ----------
    private boolean abrirSeletor(WebChromeClient.FileChooserParams params) {
        Intent escolher;
        try {
            escolher = params.createIntent();
        } catch (Exception e) {
            escolher = new Intent(Intent.ACTION_GET_CONTENT);
            escolher.setType("*/*");
        }
        escolher.addCategory(Intent.CATEGORY_OPENABLE);
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            escolher.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        List<Intent> extras = new ArrayList<>();
        String[] tipos = params.getAcceptTypes();
        boolean aceitaImagem = true;
        if (tipos != null && tipos.length > 0) {
            String t = Arrays.toString(tipos);
            aceitaImagem = t.contains("image") || t.contains("*/*") || t.replaceAll("[\\[\\],\\s]", "").isEmpty();
        }
        if (aceitaImagem) {
            try {
                File pasta = new File(getCacheDir(), "camera");
                if (!pasta.exists()) pasta.mkdirs();
                File foto = new File(pasta, "foto_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date()) + ".jpg");
                fotoCamera = FileProvider.getUriForFile(this, getPackageName() + ".arquivos", foto);
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cam.putExtra(MediaStore.EXTRA_OUTPUT, fotoCamera);
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (cam.resolveActivity(getPackageManager()) != null) extras.add(cam);
            } catch (Exception e) {
                fotoCamera = null;
            }
        } else {
            fotoCamera = null;
        }

        Intent seletor = Intent.createChooser(escolher, "Anexar");
        if (!extras.isEmpty()) seletor.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toArray(new Intent[0]));
        try {
            startActivityForResult(seletor, REQ_ARQUIVO);
            return true;
        } catch (ActivityNotFoundException e) {
            retornoArquivo = null;
            Toast.makeText(this, "Não foi possível abrir os arquivos.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent dados) {
        super.onActivityResult(req, res, dados);
        if (req != REQ_ARQUIVO || retornoArquivo == null) return;
        Uri[] resultado = null;
        if (res == RESULT_OK) {
            if (dados != null && dados.getClipData() != null) {
                int n = dados.getClipData().getItemCount();
                resultado = new Uri[n];
                for (int i = 0; i < n; i++) resultado[i] = dados.getClipData().getItemAt(i).getUri();
            } else if (dados != null && dados.getData() != null) {
                resultado = new Uri[]{dados.getData()};
            } else if (fotoCamera != null) {
                resultado = new Uri[]{fotoCamera};
            }
        }
        retornoArquivo.onReceiveValue(resultado);
        retornoArquivo = null;
        fotoCamera = null;
    }

    // ---------- Salvar arquivos gerados pela plataforma ----------
    private void salvarArquivo(String dataUrl, String nome, String mime) {
        try {
            int virgula = dataUrl.indexOf(',');
            if (virgula < 0) throw new IllegalArgumentException("formato");
            String cab = dataUrl.substring(0, virgula);
            if (mime == null || mime.isEmpty()) {
                mime = cab.startsWith("data:") ? cab.substring(5).split(";")[0] : "application/octet-stream";
            }
            if (mime.isEmpty()) mime = "application/octet-stream";
            byte[] bytes = Base64.decode(dataUrl.substring(virgula + 1), Base64.DEFAULT);

            if (nome == null || nome.trim().isEmpty()) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                nome = "preceptoria_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date()) + (ext != null ? "." + ext : "");
            }
            nome = nome.replaceAll("[\\\\/:*?\"<>|]", "_");

            Uri uri;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Preceptoria");
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("sem destino");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("sem destino");
                    os.write(bytes);
                }
                Toast.makeText(this, "Salvo em Downloads/Preceptoria: " + nome, Toast.LENGTH_LONG).show();
            } else {
                File pasta = new File(getExternalFilesDir(null), "Download");
                if (!pasta.exists()) pasta.mkdirs();
                File f = new File(pasta, nome);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                uri = FileProvider.getUriForFile(this, getPackageName() + ".arquivos", f);
                Toast.makeText(this, "Arquivo gerado: " + nome, Toast.LENGTH_LONG).show();
            }

            Intent ver = new Intent(Intent.ACTION_VIEW);
            ver.setDataAndType(uri, mime);
            ver.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(Intent.createChooser(ver, "Abrir com"));
            } catch (ActivityNotFoundException ignorar) {
                // arquivo já está salvo; sem app para abrir
            }
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível salvar o arquivo.", Toast.LENGTH_LONG).show();
        }
    }

    private void imprimir(String titulo) {
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        if (pm == null) return;
        String nome = (titulo == null || titulo.isEmpty()) ? "Plataforma da Preceptoria" : titulo;
        PrintDocumentAdapter ad = web.createPrintDocumentAdapter(nome);
        pm.print(nome, ad, new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
    }

    /** Funções chamadas pelo JavaScript da página (window.AndroidApp). */
    private class Ponte {
        @JavascriptInterface
        public void salvar(String dataUrl, String nome, String mime) {
            runOnUiThread(() -> salvarArquivo(dataUrl, nome, mime));
        }

        @JavascriptInterface
        public void imprimir(String titulo) {
            runOnUiThread(() -> MainActivity.this.imprimir(titulo));
        }

        @JavascriptInterface
        public void abrirExterno(String url) {
            runOnUiThread(() -> {
                Uri u = Uri.parse(url);
                if (!tratarNavegacao(u)) web.loadUrl(url);
            });
        }

        @JavascriptInterface
        public void aviso(String texto) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, texto, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void recarregar() {
            runOnUiThread(() -> web.loadUrl(ultimaUrl != null ? ultimaUrl : BuildConfig.APP_URL));
        }
    }

    // ---------- Ciclo de vida ----------
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) {
        super.onSaveInstanceState(estado);
        web.saveState(estado);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
