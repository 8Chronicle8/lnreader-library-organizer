package io.github.chronicle8.lnorganizer.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import io.github.chronicle8.lnorganizer.R;
import io.github.chronicle8.lnorganizer.core.BackupReader;
import io.github.chronicle8.lnorganizer.core.Json;
import io.github.chronicle8.lnorganizer.core.Library;
import io.github.chronicle8.lnorganizer.core.ListExport;
import io.github.chronicle8.lnorganizer.core.ListParse;
import io.github.chronicle8.lnorganizer.core.Organize;
import io.github.chronicle8.lnorganizer.core.OutputCheck;
import io.github.chronicle8.lnorganizer.core.Report;
import io.github.chronicle8.lnorganizer.core.ZipFormatException;
import io.github.chronicle8.lnorganizer.core.ZipIndex;
import io.github.chronicle8.lnorganizer.core.ZipRewrite;
import io.github.chronicle8.lnorganizer.io.ChannelSource;
import io.github.chronicle8.lnorganizer.io.PlanWriter;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_OPEN_BACKUP = 1;
    private static final int REQ_SAVE_TXT = 2;
    private static final int REQ_OPEN_TXT = 3;
    private static final int REQ_SAVE_BACKUP = 4;
    private static final int MAX_CATEGORY_JSON = 64 << 20;
    private static final int MAX_TEXT_BYTES = 16 << 20;

    static final String RESTORE_STEPS =
            "LNReader's restore adds categories but never removes them, so restoring on top of your "
            + "current library keeps the old categories too.\n\n"
            + "To get exactly the organized library:\n"
            + "1. Keep your original backup file. It is your way back.\n"
            + "2. Phone Settings → Apps → LNReader → Storage (or Storage & cache) → Clear storage. This empties LNReader; "
            + "the backup brings back what it contains (novels, reading progress, settings, installed "
            + "plugins and, if it has them, downloaded chapters).\n"
            + "3. Open LNReader → More → Settings → Backup → Restore backup, and pick the organized file.\n"
            + "4. Re-add your plugin repositories (More → Settings → Repositories): LNReader's backup does "
            + "not include the repository list.";

    /** What was loaded; kept for the life of the process so a re-created screen still has it. */
    private static final class Session {
        static Uri uri;
        static String name;
        static long size;
        static byte[] centralDirectory;
        static Library library;
        static String exportText;
        static Organize.Result pending;
        /** The editor's text, kept here rather than in the saved-state bundle, which has a size limit. */
        static String editorText;
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private RunLog log;
    private Button btnOpen, btnCopy, btnSaveTxt, btnCopyAi, btnShareAi, btnPaste, btnOpenTxt, btnReset, btnCheck, btnSaveBackup;
    private TextView backupStatus, progressText, report, result;
    private ProgressBar progress;
    private EditText wanted, editor;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        log = RunLog.get(this);

        btnOpen = findViewById(R.id.btn_open);
        btnCopy = findViewById(R.id.btn_copy);
        btnSaveTxt = findViewById(R.id.btn_save_txt);
        btnCopyAi = findViewById(R.id.btn_copy_ai);
        btnShareAi = findViewById(R.id.btn_share_ai);
        btnPaste = findViewById(R.id.btn_paste);
        btnOpenTxt = findViewById(R.id.btn_open_txt);
        btnReset = findViewById(R.id.btn_reset);
        btnCheck = findViewById(R.id.btn_check);
        btnSaveBackup = findViewById(R.id.btn_save_backup);
        backupStatus = findViewById(R.id.backup_status);
        progress = findViewById(R.id.progress);
        progressText = findViewById(R.id.progress_text);
        report = findViewById(R.id.report);
        result = findViewById(R.id.result);
        wanted = findViewById(R.id.wanted);
        editor = findViewById(R.id.editor);

        btnOpen.setOnClickListener(v -> pick(Intent.ACTION_OPEN_DOCUMENT, "*/*", null, REQ_OPEN_BACKUP));
        btnCopy.setOnClickListener(v -> copy(ListExport.Mode.PLAIN));
        btnCopyAi.setOnClickListener(v -> copy(ListExport.Mode.AI));
        btnShareAi.setOnClickListener(v -> shareForAi());
        btnSaveTxt.setOnClickListener(v -> pick(Intent.ACTION_CREATE_DOCUMENT, "text/plain",
                baseName() + "-list.txt", REQ_SAVE_TXT));
        btnPaste.setOnClickListener(v -> paste());
        btnOpenTxt.setOnClickListener(v -> pick(Intent.ACTION_OPEN_DOCUMENT, "text/*", null, REQ_OPEN_TXT));
        btnReset.setOnClickListener(v -> replaceEditor(Session.exportText, "Reset the editor to the backup's list?"));
        btnCheck.setOnClickListener(v -> check(false));
        btnSaveBackup.setOnClickListener(v -> check(true));

        // The editor sits inside the page's scroll view; let it scroll its own text first.
        editor.setOnTouchListener((v, ev) -> {
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                v.getParent().requestDisallowInterceptTouchEvent(ev.getAction() != MotionEvent.ACTION_UP
                        && ev.getAction() != MotionEvent.ACTION_CANCEL);
            }
            return false;
        });

        editor.setSaveEnabled(false);
        if (Session.editorText != null) editor.setText(Session.editorText);

        showBackupStatus();
        updateButtons();
        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        Session.editorText = editor.getText().toString();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            log.line("intent", "shared text, " + (text == null ? "none" : text.length() + " chars"));
            if (text == null || text.length() == 0) {
                showError("Nothing received", "The shared item had no text.");
                return;
            }
            replaceEditor(text.toString(), "Replace the list in the editor with the shared text?");
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = streamExtra(intent);
            log.line("intent", "shared file " + uri + " (" + type + ")");
            if (uri == null) showError("Nothing received", "The shared item had no file.");
            else loadBackup(uri);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            log.line("intent", "open file " + intent.getData() + " (" + type + ")");
            if (intent.getData() == null) showError("Nothing received", "No file was given.");
            else loadBackup(intent.getData());
        }
    }

    @SuppressWarnings("deprecation")
    private static Uri streamExtra(Intent intent) {
        // The typed getParcelableExtra(String, Class) exists only from API 33; minSdk is 26.
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    // ---- pickers ----

    @SuppressWarnings("deprecation")
    private void pick(String action, String type, String title, int request) {
        Intent i = new Intent(action);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        if (title != null) i.putExtra(Intent.EXTRA_TITLE, title);
        startActivityForResult(i, request);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log.line("picker", "request " + request + " cancelled");
            return;
        }
        Uri uri = data.getData();
        switch (request) {
            case REQ_OPEN_BACKUP: loadBackup(uri); break;
            case REQ_SAVE_TXT: saveText(uri); break;
            case REQ_OPEN_TXT: openText(uri); break;
            case REQ_SAVE_BACKUP: writeBackup(uri); break;
            default: throw new IllegalArgumentException("unknown request " + request);
        }
    }

    // ---- 1. open a backup ----

    private void loadBackup(Uri uri) {
        if (busy) {
            toast("Still working on the previous step");
            return;
        }
        setBusy(true, "Reading the backup…");
        WORKER.execute(() -> {
            long t0 = System.nanoTime();
            try {
                String name = displayName(uri);
                log.line("open", "backup " + name + " " + uri);
                try (ParcelFileDescriptor pfd = openRead(uri);
                     FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                    FileChannel ch = fis.getChannel();
                    ZipIndex zip = ZipIndex.open(new ChannelSource(ch));
                    Library lib = BackupReader.read(zip, (done, total) -> post(() -> showProgress(
                            "Reading novels… " + done + " of " + total, done, total)));
                    String text = ListExport.export(lib, name, ListExport.Mode.PLAIN, "");
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    log.line("open", "ok in " + ms + " ms: " + zip.fileSize() + " bytes, " + zip.entries().size()
                            + " entries, " + lib.novels.size() + " novel files, " + lib.listedNovels().size()
                            + " listed, " + lib.categories.size() + " categories, LNReader " + lib.appVersion
                            + " format " + lib.formatVersion + ", notes " + lib.notes);
                    byte[] cd = zip.centralDirectoryBytes();
                    long size = zip.fileSize();
                    post(() -> {
                        String previous = Session.exportText;
                        Session.uri = uri;
                        Session.name = name;
                        Session.size = size;
                        Session.centralDirectory = cd;
                        Session.library = lib;
                        Session.exportText = text;
                        Session.pending = null;
                        setBusy(false, null);
                        showBackupStatus();
                        report.setVisibility(View.GONE);
                        result.setVisibility(View.GONE);
                        String current = editor.getText().toString();
                        if (current.trim().isEmpty() || current.equals(previous)) {
                            editor.setText(text);
                        } else {
                            replaceEditor(text, "The editor already has a list. Replace it with this backup's list?");
                        }
                    });
                }
            } catch (Exception e) {
                // Every failure ends here so it is shown and logged; nothing is half-loaded.
                fail("Could not open the backup", e);
            }
        });
    }

    private ParcelFileDescriptor openRead(Uri uri) throws IOException {
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("the file could not be opened");
        if (pfd.getStatSize() < 0) {
            pfd.close();
            throw new IOException("this file is streamed by the app that provides it and cannot be read "
                    + "directly. Save it to the phone's storage (for example Downloads) and open it from there.");
        }
        return pfd;
    }

    private void showBackupStatus() {
        Library lib = Session.library;
        if (lib == null) {
            backupStatus.setText(R.string.no_backup);
            return;
        }
        int novels = lib.listedNovels().size();
        int cats = lib.categories.size();
        StringBuilder sb = new StringBuilder();
        sb.append(Session.name).append("\n")
                .append(novels).append(novels == 1 ? " novel in " : " novels in ")
                .append(cats).append(cats == 1 ? " category" : " categories")
                .append(lib.appVersion == null ? "" : " · LNReader " + lib.appVersion)
                .append(" · ").append(formatSize(Session.size));
        if (!lib.notes.isEmpty()) {
            sb.append("\n").append(lib.notes.size()).append(lib.notes.size() == 1 ? " note" : " notes")
                    .append(" about the backup (shown under Check list).");
        }
        backupStatus.setText(sb);
    }

    // ---- 2. list out ----

    private String exportFor(ListExport.Mode mode) {
        return ListExport.export(Session.library, Session.name, mode, wanted.getText().toString());
    }

    private void copy(ListExport.Mode mode) {
        String text = exportFor(mode);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("LNReader library", text));
        log.line("copy", mode + ", " + text.length() + " chars");
        toast(mode == ListExport.Mode.AI ? "Copied with AI instructions. Paste it into your AI chat."
                : "List copied (" + text.length() + " characters)");
    }

    private void shareForAi() {
        String text = exportFor(ListExport.Mode.AI);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        log.line("share", "AI list, " + text.length() + " chars");
        startActivity(Intent.createChooser(send, "Send the list to"));
    }

    private void saveText(Uri uri) {
        String text = exportFor(ListExport.Mode.PLAIN);
        WORKER.execute(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
                if (os == null) throw new IOException("the file could not be opened for writing");
                os.write(text.getBytes(StandardCharsets.UTF_8));
                log.line("save-txt", uri + ", " + text.length() + " chars");
                post(() -> toast("List saved"));
            } catch (Exception e) {
                // Shown and logged; a half-written list file is deleted.
                deleteQuietly(uri);
                fail("Could not save the list", e);
            }
        });
    }

    // ---- 3. list in ----

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
        log.line("paste", text == null ? "clipboard empty" : text.length() + " chars");
        if (text == null || text.length() == 0) {
            toast("The clipboard is empty");
            return;
        }
        replaceEditor(text.toString(), "Replace the list in the editor with the clipboard?");
    }

    private void openText(Uri uri) {
        WORKER.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IOException("the file could not be opened");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_TEXT_BYTES) throw new IOException("the file is larger than 16 MB; is it a list?");
                }
                String text;
                try {
                    text = Json.decodeUtf8(bos.toByteArray());
                } catch (CharacterCodingException e) {
                    throw new IOException("the file is not UTF-8 text", e);
                }
                if (text.startsWith("﻿")) text = text.substring(1);
                log.line("open-txt", displayName(uri) + ", " + text.length() + " chars");
                String finalText = text;
                post(() -> replaceEditor(finalText, "Replace the list in the editor with this file?"));
            } catch (Exception e) {
                // Shown and logged; the editor is left as it was.
                fail("Could not open the text file", e);
            }
        });
    }

    private void replaceEditor(String text, String question) {
        if (text == null) return;
        String current = editor.getText().toString();
        if (current.trim().isEmpty() || current.equals(text) || current.equals(Session.exportText)) {
            setEditor(text);
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(question)
                .setPositiveButton("Replace", (d, w) -> setEditor(text))
                .setNegativeButton("Keep mine", null)
                .show();
    }

    private void setEditor(String text) {
        editor.setText(text);
        Session.pending = null;
        report.setVisibility(View.GONE);
        result.setVisibility(View.GONE);
        updateButtons();
    }

    // ---- 4. check and save ----

    private void check(boolean thenSave) {
        Library lib = Session.library;
        String text = editor.getText().toString();
        Organize.Result r;
        try {
            r = Organize.build(lib, ListParse.parse(text, lib));
        } catch (Organize.NothingToImport e) {
            log.line("check", "refused: " + e.getMessage());
            Session.pending = null;
            report.setText(e.getMessage());
            report.setVisibility(View.VISIBLE);
            result.setVisibility(View.GONE);
            if (thenSave) showError("Nothing to save", e.getMessage());
            return;
        }
        String rendered = Report.render(lib, r);
        log.line("check", r.categories.size() + " categories, unchanged " + r.unchanged + ", moved " + r.movedCount
                + ", unplaced " + r.unplaced.size() + ", left out " + r.leftOutCount() + ", issues " + r.issues.size());
        Session.pending = r;
        report.setText(rendered);
        report.setVisibility(View.VISIBLE);
        result.setVisibility(View.GONE);
        if (!thenSave) return;

        StringBuilder warn = new StringBuilder();
        if (r.unchanged) warn.append("The list has no changes; the new backup would be identical to the original.\n\n");
        if (r.leftOutCount() > 0) warn.append(r.leftOutCount()).append(" line(s) were left out or ignored.\n\n");
        if (!r.unplaced.isEmpty()) {
            warn.append(r.unplaced.size()).append(" novel(s) are missing from the list and will go to ")
                    .append(lib.defaultCategory().trimmedName).append(".\n\n");
        }
        if (warn.length() == 0) {
            askWhereToSave();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Save anyway?")
                    .setMessage(warn + "Details are in the report below the buttons.")
                    .setPositiveButton("Save", (d, w) -> askWhereToSave())
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void askWhereToSave() {
        pick(Intent.ACTION_CREATE_DOCUMENT, "application/zip", baseName() + "_organized.zip", REQ_SAVE_BACKUP);
    }

    private void writeBackup(Uri out) {
        Organize.Result r = Session.pending;
        if (r == null) {
            showError("Nothing to save", "Check the list again, then save.");
            return;
        }
        Uri in = Session.uri;
        byte[] expectedCd = Session.centralDirectory;
        long expectedSize = Session.size;
        String outName = displayNameOrUri(out);
        setBusy(true, "Writing the new backup…");
        WORKER.execute(() -> {
            long t0 = System.nanoTime();
            boolean created = true;
            try (ParcelFileDescriptor pfd = openRead(in);
                 FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                ChannelSource source = new ChannelSource(fis.getChannel());
                ZipIndex zip = ZipIndex.open(source);
                if (zip.fileSize() != expectedSize || !Arrays.equals(zip.centralDirectoryBytes(), expectedCd)) {
                    throw new ZipFormatException("the backup changed since it was opened; open it again");
                }
                ZipRewrite.Plan plan = ZipRewrite.replaceEntry(zip, BackupReader.CATEGORY_JSON, r.categoryJson, MAX_CATEGORY_JSON);
                log.line("write", "to " + out + ": " + plan.totalLength + " bytes, identical " + plan.identical
                        + ", Category.json " + r.categoryJson.length + " bytes");
                try (OutputStream os = getContentResolver().openOutputStream(out, "w")) {
                    if (os == null) throw new IOException("the new file could not be opened for writing");
                    PlanWriter.write(plan, source, os, (done, total) -> post(() ->
                            showProgress("Writing… " + percent(done, total), done, total)));
                }
                long t1 = System.nanoTime();
                try (ParcelFileDescriptor opfd = openRead(out);
                     FileInputStream ofis = new FileInputStream(opfd.getFileDescriptor())) {
                    ZipIndex written = ZipIndex.open(new ChannelSource(ofis.getChannel()));
                    if (written.fileSize() != plan.totalLength) {
                        throw new ZipFormatException("the new file has " + written.fileSize() + " bytes, "
                                + plan.totalLength + " were written");
                    }
                    OutputCheck.verify(zip, written, BackupReader.CATEGORY_JSON, r.categoryJson, (done, total) ->
                            post(() -> showProgress("Checking the new backup… " + percent(done, total), done, total)));
                }
                long t2 = System.nanoTime();
                log.line("write", "ok: wrote in " + (t1 - t0) / 1_000_000 + " ms, verified in " + (t2 - t1) / 1_000_000 + " ms");
                created = false;
                long size = plan.totalLength;
                post(() -> {
                    setBusy(false, null);
                    result.setText("Saved and checked: " + outName + " (" + formatSize(size) + ").\n\n" + RESTORE_STEPS);
                    result.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                // The output is only kept once it has been read back and checked.
                String cleanup = created ? deleteQuietly(out) : "";
                fail("Could not write the new backup" + cleanup, e);
            }
        });
    }

    /** Deletes a document this app created; returns a note for the error message when that fails. */
    private String deleteQuietly(Uri uri) {
        try {
            boolean ok = DocumentsContract.deleteDocument(getContentResolver(), uri);
            log.line("cleanup", "deleted " + uri + ": " + ok);
            return ok ? " (the unfinished file was deleted)" : " (delete the unfinished file " + displayNameOrUri(uri) + " yourself)";
        } catch (Exception e) {
            // Reported to the user in the error message; the original failure matters more.
            log.error("cleanup failed for " + uri, e);
            return " (delete the unfinished file " + displayNameOrUri(uri) + " yourself)";
        }
    }

    // ---- menu ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_how) {
            new AlertDialog.Builder(this).setTitle(R.string.menu_how).setMessage(RESTORE_STEPS)
                    .setPositiveButton("OK", null).show();
            return true;
        }
        if (item.getItemId() == R.id.menu_share_log) {
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, log.tail(400_000));
                startActivity(Intent.createChooser(send, "Share the log"));
            } catch (IOException e) {
                showError("Could not read the log", e.getMessage());
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---- helpers ----

    private void updateButtons() {
        boolean loaded = Session.library != null;
        for (Button b : new Button[] {btnCopy, btnSaveTxt, btnCopyAi, btnShareAi, btnReset, btnCheck, btnSaveBackup}) {
            b.setEnabled(loaded && !busy);
        }
        btnOpen.setEnabled(!busy);
        btnPaste.setEnabled(!busy);
        btnOpenTxt.setEnabled(!busy);
        wanted.setEnabled(loaded);
    }

    private void setBusy(boolean on, String text) {
        busy = on;
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        progressText.setVisibility(on ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(true);
        if (text != null) progressText.setText(text);
        updateButtons();
    }

    private void showProgress(String text, long done, long total) {
        if (!busy) return;
        progressText.setText(text);
        if (total > 0) {
            progress.setIndeterminate(false);
            progress.setMax(1000);
            progress.setProgress((int) (done * 1000 / total));
        }
    }

    private void fail(String title, Exception e) {
        log.error(title, e);
        String message = e instanceof BackupReader.BackupException
                ? String.join("\n\n", ((BackupReader.BackupException) e).problems)
                : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        post(() -> {
            setBusy(false, null);
            showError(title, message);
        });
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message + "\n\nThe log (menu → Share log) has the details.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("error", title + ": " + message));
                })
                .show();
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (!isDestroyed()) r.run();
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String baseName() {
        String n = Session.name == null ? "lnreader_backup" : Session.name;
        return n.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
    }

    private String displayName(Uri uri) throws IOException {
        ContentResolver cr = getContentResolver();
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        }
        // Some providers (a file opened with "Open with") have no display name; the path's last part is the name then.
        String last = uri.getLastPathSegment();
        if (last == null) throw new IOException("the file has no name: " + uri);
        return last;
    }

    private String displayNameOrUri(Uri uri) {
        try {
            return displayName(uri);
        } catch (Exception e) {
            // Only used inside messages; the address itself still identifies the file.
            return uri.toString();
        }
    }

    static String percent(long done, long total) {
        return total <= 0 ? "" : (done * 100 / total) + "%";
    }

    static String formatSize(long bytes) {
        if (bytes >= 1L << 20) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1L << 10) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return bytes + " bytes";
    }
}
