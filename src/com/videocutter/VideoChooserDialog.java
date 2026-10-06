package com.videocutter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Environment;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * In-app popup for choosing video files: shows the current folder path, a
 * ".. (up)" row, sub-folders and video files. Cancel / Select buttons at the bottom.
 * Needs READ_EXTERNAL_STORAGE (the caller requests it before showing this).
 */
public final class VideoChooserDialog {

    public interface Callback {
        void onSelected(List<File> files);

        /** The "System" button was tapped: caller may open the system file browser. */
        void onUseSystemPicker();
    }

    private static final String PREFS = "video_chooser";
    private static final String KEY_DIR = "last_dir";

    private static final String[] VIDEO_EXT = {
            "mp4", "m4v", "mkv", "webm", "3gp", "3g2", "mov", "avi", "ts", "mts",
            "m2ts", "mpg", "mpeg", "flv", "wmv", "ogv", "mp3", "m4a", "aac", "wav", "ogg", "flac"
    };

    private static final class Entry {
        final File file;
        final boolean isUp;
        final boolean isDir;

        Entry(File file, boolean isUp, boolean isDir) {
            this.file = file;
            this.isUp = isUp;
            this.isDir = isDir;
        }
    }

    private final Activity activity;
    private final boolean multiple;
    private final Callback callback;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final Set<String> selected = new LinkedHashSet<String>();

    private File currentDir;
    private AlertDialog dialog;
    private TextView pathView;
    private TextView emptyView;
    private TextView selectAllView;
    private final List<File> folderVideos = new ArrayList<File>();
    private ListView listView;
    private EntryAdapter adapter;

    private VideoChooserDialog(Activity activity, boolean multiple, Callback callback) {
        this.activity = activity;
        this.multiple = multiple;
        this.callback = callback;
    }

    public static void show(Activity activity, boolean multiple, Callback callback) {
        new VideoChooserDialog(activity, multiple, callback).open();
    }

    // ------------------------------------------------------------------

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void open() {
        currentDir = initialDir();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(4), dp(20), 0);

        pathView = new TextView(activity);
        pathView.setTextSize(12f);
        pathView.setTextColor(Color.BLACK);
        pathView.setPadding(0, 0, 0, dp(2));
        root.addView(pathView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        selectAllView = new TextView(activity);
        selectAllView.setTextSize(12f);
        selectAllView.setTypeface(Typeface.DEFAULT_BOLD);
        selectAllView.setTextColor(0xFF0DB09A);
        selectAllView.setGravity(Gravity.END);
        selectAllView.setPadding(dp(12), dp(6), dp(4), dp(8));
        selectAllView.setVisibility(View.GONE);
        selectAllView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSelectAll();
            }
        });
        root.addView(selectAllView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int listHeight = (int) (dm.heightPixels * 0.68f);

        android.widget.FrameLayout holder = new android.widget.FrameLayout(activity);
        holder.setBackgroundColor(0xFFEFEFEF);

        listView = new ListView(activity);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        adapter = new EntryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onEntryClicked(entries.get(position));
            }
        });
        holder.addView(listView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(activity);
        emptyView.setTextColor(0xFF666666);
        emptyView.setTextSize(13f);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(16), dp(16), dp(16), dp(16));
        holder.addView(emptyView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        root.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity,
                android.R.style.Theme_Material_Light_Dialog_Alert);
        builder.setTitle(multiple ? "Select videos" : "Select video");
        builder.setView(root);
        builder.setNegativeButton("Cancel", null);
        builder.setPositiveButton("Select", null); // set below so it can stay open on error
        dialog = builder.create();
        dialog.show();
        DialogStyler.shrink(dialog);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected.isEmpty()) {
                    Toast.makeText(activity, "Tap a video to select it", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<File> result = new ArrayList<File>();
                for (String path : selected) result.add(new File(path));
                saveLastDir();
                dialog.dismiss();
                callback.onSelected(result);
            }
        });

        loadDir(currentDir);
    }

    private File initialDir() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_DIR, null);
        if (saved != null) {
            File f = new File(saved);
            if (f.isDirectory() && f.canRead()) return f;
        }
        return Environment.getExternalStorageDirectory();
    }

    private void saveLastDir() {
        if (currentDir == null) return;
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DIR, currentDir.getAbsolutePath()).apply();
    }

    private static boolean isVideoName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        for (String e : VIDEO_EXT) {
            if (e.equals(ext)) return true;
        }
        return false;
    }

    private void loadDir(File dir) {
        currentDir = dir;
        pathView.setText(dir.getAbsolutePath());
        entries.clear();
        folderVideos.clear();

        File parent = dir.getParentFile();
        File root = Environment.getExternalStorageDirectory();
        if (root != null && root.getAbsolutePath().equals(dir.getAbsolutePath())) {
            parent = null; // top level: no ".. (up)" row
        }
        if (parent != null) entries.add(new Entry(parent, true, true));

        File[] children = dir.listFiles();
        if (children == null) {
            emptyView.setText("Cannot read this folder");
        } else {
            List<File> dirs = new ArrayList<File>();
            List<File> videos = new ArrayList<File>();
            for (File f : children) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    dirs.add(f);
                } else if (isVideoName(f.getName())) {
                    videos.add(f);
                }
            }
            Comparator<File> byName = new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
                }
            };
            Collections.sort(dirs, byName);
            Collections.sort(videos, byName);
            for (File d : dirs) entries.add(new Entry(d, false, true));
            for (File v : videos) {
                entries.add(new Entry(v, false, false));
                folderVideos.add(v);
            }
            emptyView.setText("No folders or videos here");
        }
        // Only the up row means the folder is effectively empty
        boolean empty = entries.size() <= (parent != null ? 1 : 0);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        // Keep the empty message below the ".. (up)" row
        emptyView.setTranslationY(empty && parent != null ? dp(36) : 0);

        adapter.notifyDataSetChanged();
        listView.setSelection(0);
        updateSelectButton();
    }

    private void onEntryClicked(Entry e) {
        if (e.isDir) {
            saveLastDirFor(e.file);
            loadDir(e.file);
            return;
        }
        String path = e.file.getAbsolutePath();
        if (selected.contains(path)) {
            selected.remove(path);
        } else {
            if (!multiple) selected.clear();
            selected.add(path);
        }
        adapter.notifyDataSetChanged();
        updateSelectButton();
    }

    private boolean allFolderSelected() {
        if (folderVideos.isEmpty()) return false;
        for (File f : folderVideos) {
            if (!selected.contains(f.getAbsolutePath())) return false;
        }
        return true;
    }

    private void toggleSelectAll() {
        boolean deselect = allFolderSelected();
        for (File f : folderVideos) {
            if (deselect) {
                selected.remove(f.getAbsolutePath());
            } else {
                selected.add(f.getAbsolutePath());
            }
        }
        adapter.notifyDataSetChanged();
        updateSelectButton();
    }

    private void saveLastDirFor(File dir) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DIR, dir.getAbsolutePath()).apply();
    }

    private void updateSelectButton() {
        if (selectAllView != null) {
            if (multiple && !folderVideos.isEmpty()) {
                selectAllView.setVisibility(View.VISIBLE);
                selectAllView.setText(allFolderSelected() ? "DESELECT ALL" : "SELECT ALL");
            } else {
                selectAllView.setVisibility(View.GONE);
            }
        }
        if (dialog == null) return;
        android.widget.Button b = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (b == null) return;
        b.setText(selected.isEmpty() ? "Select" : "Select (" + selected.size() + ")");
    }

    // ------------------------------------------------------------------

    private final class EntryAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView name;
            TextView info;
            CheckBox check;
            ImageView icon;
            if (convertView == null) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(6), dp(12), dp(6));
                row.setMinimumHeight(dp(36));

                icon = new ImageView(activity);
                icon.setImageResource(R.drawable.arrow_upward_24px);
                icon.setColorFilter(0xFF555555);
                icon.setVisibility(View.GONE);
                row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

                LinearLayout texts = new LinearLayout(activity);
                texts.setOrientation(LinearLayout.VERTICAL);

                name = new TextView(activity);
                name.setTextSize(14f);
                name.setTextColor(Color.BLACK);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                texts.addView(name);

                info = new TextView(activity);
                info.setTextSize(11f);
                info.setTextColor(0xFF666666);
                texts.addView(info);

                row.addView(texts, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                check = new CheckBox(activity);
                check.setClickable(false);
                check.setFocusable(false);
                row.addView(check, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                row.setTag(new View[]{name, info, check, icon});
            } else {
                row = (LinearLayout) convertView;
            }
            View[] views = (View[]) row.getTag();
            name = (TextView) views[0];
            info = (TextView) views[1];
            check = (CheckBox) views[2];
            icon = (ImageView) views[3];
            icon.setVisibility(entries.get(position).isUp ? View.VISIBLE : View.GONE);

            Entry e = entries.get(position);
            if (e.isUp) {
                name.setText("");
                name.setVisibility(View.GONE);
                name.setTypeface(Typeface.DEFAULT);
                info.setVisibility(View.GONE);
                check.setVisibility(View.GONE);
            } else if (e.isDir) {
                name.setVisibility(View.VISIBLE);
                name.setText("\uD83D\uDCC1  " + e.file.getName());
                name.setTypeface(Typeface.DEFAULT);
                info.setVisibility(View.GONE);
                check.setVisibility(View.GONE);
            } else {
                name.setVisibility(View.VISIBLE);
                name.setText(e.file.getName());
                name.setTypeface(Typeface.DEFAULT);
                info.setVisibility(View.VISIBLE);
                info.setText(Formatter.formatFileSize(activity, e.file.length()));
                check.setVisibility(View.VISIBLE);
                check.setChecked(selected.contains(e.file.getAbsolutePath()));
            }
            return row;
        }
    }
}
