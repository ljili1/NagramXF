package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.BasePermissionsActivity;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DocumentSelectActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import kotlin.text.StringsKt;
import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.helpers.CloudSettingsHelper;
import tw.nekomimi.nekogram.helpers.PasscodeHelper;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.utils.AlertUtil;
import tw.nekomimi.nekogram.utils.FileUtil;
import tw.nekomimi.nekogram.utils.GsonUtil;
import tw.nekomimi.nekogram.utils.ShareUtil;

public class NekoSettingsActivity extends BaseFragment {
    private Page typePage;

    @Override
    public View createView(Context context) {
        typePage = new Page(context);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getTitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    CloudSettingsHelper.getInstance().showDialog(NekoSettingsActivity.this);
                }
            }
        });
        
        actionBar.createMenu().addItem(1, R.drawable.cloud_sync);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(typePage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private class Page extends FrameLayout {

        private static final int VIEW_TYPE_HEADER = 1;
        private static final int VIEW_TYPE_BOTTOM = 2;
        private static final int VIEW_TYPE_TEXT = 3;

        public final RecyclerListView listView;
        private final RecyclerView.Adapter listAdapter;

        private int nSettingsHeaderRow = -1;
        private int rowCount;
        private int generalRow = -1;
        private int translatorRow = -1;
        private int chatRow = -1;
        private int passcodeRow = -1;
        private int experimentRow = -1;
        private int categories2Row = -1;

        private int otherRow = -1;
        private int importSettingsRow = -1;
        private int exportSettingsRow = -1;
        private int resetSettingsRow = -1;
        private int appRestartRow = -1;
        private int aboutDividerRow = -1;
        private int aboutHeaderRow = -1;
        private int aboutRow = -1;

        @SuppressLint("ApplySharedPref")
        public Page(Context context) {
            super(context);

            listView = new RecyclerListView(context);
            listView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
            listView.setVerticalScrollBarEnabled(false);
            listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
            listView.setAdapter(listAdapter = new RecyclerListView.SelectionAdapter() {
                @Override
                public int getItemCount() {
                    return rowCount;
                }

                @NonNull
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    View view = null;
                    switch (viewType) {
                        case VIEW_TYPE_HEADER:
                            view = new HeaderCell(getContext());
                            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                            break;
                        case VIEW_TYPE_BOTTOM:
                            view = new ShadowSectionCell(getContext());
                            break;
                        case VIEW_TYPE_TEXT:
                            view = new TextCell(getContext());
                            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                            break;
                    }
                    view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new RecyclerListView.Holder(view);
                }

                @Override
                public boolean isEnabled(RecyclerView.ViewHolder holder) {
                    return holder.getItemViewType() == VIEW_TYPE_TEXT;
                }

                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                    switch (holder.getItemViewType()) {
                        case VIEW_TYPE_HEADER: {
                            HeaderCell headerCell = (HeaderCell) holder.itemView;
                            if (position == nSettingsHeaderRow) {
                                headerCell.setText(getString(R.string.NekoSettings));
                            } else if (position == otherRow) {
                                headerCell.setText(getString(R.string.Other));
                            } else if (position == aboutHeaderRow) {
                                headerCell.setText(getString(R.string.NagranX_About));
                            }
                            break;
                        }
                        case VIEW_TYPE_BOTTOM: {
                            int
                                    key = (position == aboutDividerRow) ?
                                    Theme.key_windowBackgroundGrayShadow :
                                    Theme.key_windowBackgroundGray;
                            holder.itemView.setBackground(Theme.getThemedDrawable(getContext(),
                                    R.drawable.greydivider, key));
                            break;
                        }
                        case VIEW_TYPE_TEXT: {
                            TextCell textCell = (TextCell) holder.itemView;
                            if (position == chatRow) {
                                textCell.setTextAndIcon(getString(R.string.Chat), R.drawable.msg_discussion, true);
                            } else if (position == generalRow) {
                                textCell.setTextAndIcon(getString(R.string.General), R.drawable.msg_theme, true);
                            } else if (position == translatorRow) {
                                textCell.setTextAndIcon(getString(R.string.TranslatorSettings), R.drawable.ic_translate, true);
                            } else if (position == passcodeRow) {
                                textCell.setTextAndIcon(getString(R.string.PasscodeNeko), R.drawable.msg_permissions, true);
                            } else if (position == experimentRow) {
                                textCell.setTextAndIcon(getString(R.string.Experimental), R.drawable.msg_fave, true);
                            } else if (position == importSettingsRow) {
                                textCell.setTextAndIcon(getString(R.string.ImportSettings), R.drawable.msg_photo_settings_solar, true);
                            } else if (position == exportSettingsRow) {
                                textCell.setTextAndIcon(getString(R.string.BackupSettings), R.drawable.msg_instant_link_solar, true);
                            } else if (position == resetSettingsRow) {
                                textCell.setTextAndIcon(getString(R.string.ResetSettings), R.drawable.msg_reset_solar, true);
                            } else if (position == appRestartRow) {
                                textCell.setTextAndIcon(getString(R.string.RestartApp), R.drawable.msg_retry_solar, true);
                            } else if (position == aboutRow) {
                                textCell.setTextAndIcon(getString(R.string.NagranX_About_Desc), R.drawable.msg_info_solar, false);
                            }
                            break;
                        }
                    }
                }

                @Override
                public int getItemViewType(int position) {
                    if (position == categories2Row || position == aboutDividerRow) {
                        return VIEW_TYPE_BOTTOM;
                    } else if (position == nSettingsHeaderRow || position == otherRow || position == aboutHeaderRow) {
                        return VIEW_TYPE_HEADER;
                    } else {
                        return VIEW_TYPE_TEXT;
                    }
                }
            });
            listView.setOnItemClickListener((view, position, x, y) -> {
                if (position == chatRow) {
                    presentFragment(new NekoChatSettingsActivity());
                } else if (position == generalRow) {
                    presentFragment(new NekoGeneralSettingsActivity());
                } else if (position == passcodeRow) {
                    presentFragment(new NekoPasscodeSettingsActivity());
                } else if (position == experimentRow) {
                    presentFragment(new NekoExperimentalSettingsActivity());
                } else if (position == translatorRow) {
                    presentFragment(new NekoTranslatorSettingsActivity());
                } else if (position == importSettingsRow) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        openFilePicker();
                    } else {
                        DocumentSelectActivity activity = getDocumentSelectActivity(getParentActivity());
                        if (activity != null) {
                            presentFragment(activity);
                        }
                    }
                } else if (position == resetSettingsRow) {
                    AlertUtil.showConfirm(getParentActivity(),
                            getString(R.string.ResetSettingsAlert),
                            R.drawable.msg_reset,
                            getString(R.string.Reset),
                            true,
                            () -> {
                                ApplicationLoader.applicationContext.getSharedPreferences("nekocloud", Activity.MODE_PRIVATE).edit().clear().commit();
                                ApplicationLoader.applicationContext.getSharedPreferences("nekox_config", Activity.MODE_PRIVATE).edit().clear().commit();
                                ApplicationLoader.applicationContext.getSharedPreferences("nkmrcfg", Activity.MODE_PRIVATE).edit().clear().commit();
                                AppRestartHelper.triggerRebirth(context, new Intent(context, LaunchActivity.class));
                            });
                } else if (position == exportSettingsRow) {
                    backupSettings();
                } else if (position == appRestartRow) {
                    AppRestartHelper.triggerRebirth(context, new Intent(context, LaunchActivity.class));
                } else if (position == aboutRow) {
                    presentFragment(new NekoAboutActivity());
                }
            });

            updateRows();

            setWillNotDraw(false);
        }

        private void updateRows() {
            rowCount = 0;
            nSettingsHeaderRow = rowCount++;
            generalRow = rowCount++;
            translatorRow = rowCount++;
            chatRow = rowCount++;
            if (!PasscodeHelper.isSettingsHidden()) {
                passcodeRow = rowCount++;
            } else {
                passcodeRow = -1;
            }
            experimentRow = rowCount++;
            categories2Row = rowCount++;
            otherRow = rowCount++;
            importSettingsRow = rowCount++;
            exportSettingsRow = rowCount++;
            resetSettingsRow = rowCount++;
            appRestartRow = rowCount++;
            aboutDividerRow = rowCount++;
            aboutHeaderRow = rowCount++;
            aboutRow = rowCount++;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private void backupSettings() {

        try {
            File cacheFile = new File(AndroidUtilities.getCacheDir(), new Date().toLocaleString() + ".nekox-settings.json");
            FileUtil.writeUtf8String(backupSettingsJson(false, 4), cacheFile);
            ShareUtil.shareFile(getParentActivity(), cacheFile);
        } catch (JSONException e) {
            AlertUtil.showSimpleAlert(getParentActivity(), e);
        }

    }

    public static String backupSettingsJson(boolean isCloud, int indentSpaces) throws JSONException {

        JSONObject configJson = new JSONObject();

        ArrayList<String> userconfig = new ArrayList<>();
        userconfig.add("saveIncomingPhotos");
        userconfig.add("passcodeHash");
        userconfig.add("passcodeType");
        userconfig.add("passcodeHash");
        userconfig.add("autoLockIn");
        userconfig.add("useFingerprint");
        spToJSON("userconfing", configJson, userconfig::contains, isCloud);

        ArrayList<String> mainconfig = new ArrayList<>();
        mainconfig.add("saveToGallery");
        mainconfig.add("autoplayGifs");
        mainconfig.add("autoplayVideo");
        mainconfig.add("mapPreviewType");
        mainconfig.add("raiseToSpeak");
        mainconfig.add("customTabs");
        mainconfig.add("directShare");
        mainconfig.add("shuffleMusic");
        mainconfig.add("playOrderReversed");
        mainconfig.add("inappCamera");
        mainconfig.add("repeatMode");
        mainconfig.add("fontSize");
        mainconfig.add("bubbleRadius");
        mainconfig.add("ivFontSize");
        mainconfig.add("allowBigEmoji");
        mainconfig.add("streamMedia");
        mainconfig.add("saveStreamMedia");
        mainconfig.add("smoothKeyboard");
        mainconfig.add("pauseMusicOnRecord");
        mainconfig.add("streamAllVideo");
        mainconfig.add("streamMkv");
        mainconfig.add("suggestStickers");
        mainconfig.add("sortContactsByName");
        mainconfig.add("sortFilesByName");
        mainconfig.add("noSoundHintShowed");
        mainconfig.add("directShareHash");
        mainconfig.add("useThreeLinesLayout");
        mainconfig.add("archiveHidden");
        mainconfig.add("distanceSystemType");
        mainconfig.add("loopStickers");
        mainconfig.add("keepMedia");
        mainconfig.add("noStatusBar");
        mainconfig.add("lastKeepMediaCheckTime");
        mainconfig.add("searchMessagesAsListHintShows");
        mainconfig.add("searchMessagesAsListUsed");
        mainconfig.add("stickersReorderingHintUsed");
        mainconfig.add("textSelectionHintShows");
        mainconfig.add("scheduledOrNoSoundHintShows");
        mainconfig.add("lockRecordAudioVideoHint");
        mainconfig.add("disableVoiceAudioEffects");
        mainconfig.add("chatSwipeAction");

        mainconfig.add("theme");
        mainconfig.add("selectedAutoNightType");
        mainconfig.add("autoNightScheduleByLocation");
        mainconfig.add("autoNightBrighnessThreshold");
        mainconfig.add("autoNightDayStartTime");
        mainconfig.add("autoNightDayEndTime");
        mainconfig.add("autoNightSunriseTime");
        mainconfig.add("autoNightCityName");
        mainconfig.add("autoNightSunsetTime");
        mainconfig.add("autoNightLocationLatitude3");
        mainconfig.add("autoNightLocationLongitude3");
        mainconfig.add("autoNightLastSunCheckDay");

        mainconfig.add("lang_code");

        spToJSON("mainconfig", configJson, mainconfig::contains, isCloud);
        spToJSON("themeconfig", configJson, null, isCloud);

        spToJSON("nkmrcfg", configJson, null, isCloud);

        return configJson.toString(indentSpaces);
    }

    private static void spToJSON(String sp, JSONObject object, Function<String, Boolean> filter, boolean isCloud) throws JSONException {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(sp, Activity.MODE_PRIVATE);
        JSONObject jsonConfig = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
//            if (isCloud && key.endsWith("Key")) {
//                continue;
//            }
            if (isCloud && key.endsWith("Prompt")) {
                continue;
            }
            if (filter != null && !filter.apply(key)) continue;
            if (entry.getValue() instanceof Long) {
                key = key + "_long";
            } else if (entry.getValue() instanceof Float) {
                key = key + "_float";
            }
            jsonConfig.put(key, entry.getValue());
        }
        object.put(sp, jsonConfig);
    }

    private DocumentSelectActivity getDocumentSelectActivity(Activity parent) {
        try {
            if (Build.VERSION.SDK_INT >= 23 && parent.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                parent.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);
                return null;
            }
        } catch (Throwable ignore) {
        }
        DocumentSelectActivity fragment = new DocumentSelectActivity(false);
        fragment.setMaxSelectedFiles(1);
        fragment.setAllowPhoto(false);
        fragment.setDelegate(new DocumentSelectActivity.DocumentSelectActivityDelegate() {
            @Override
            public void didSelectFiles(DocumentSelectActivity activity, ArrayList<String> files, String caption, boolean notify, int scheduleDate) {
                activity.finishFragment();
                importSettings(parent, new File(files.get(0)));
            }

            @Override
            public void didSelectPhotos(ArrayList<SendMessagesHelper.SendingMediaInfo> photos, boolean notify, int scheduleDate) {
            }

            @Override
            public void startDocumentSelectActivity() {
            }
        });
        return fragment;
    }

    public static void importSettings(Context context, File settingsFile) {

        AlertUtil.showConfirm(context,
                getString(R.string.ImportSettingsAlert),
                R.drawable.msg_photo_settings_solar,
                getString(R.string.Import),
                true,
                () -> importSettingsConfirmed(context, settingsFile));

    }

    public static void importSettingsConfirmed(Context context, File settingsFile) {

        try {
            JsonObject configJson = GsonUtil.toJsonObject(FileUtil.readUtf8String(settingsFile));
            importSettings(configJson);

            AlertDialog restart = new AlertDialog(context, 0);
            restart.setTitle(getString(R.string.NagramX));
            restart.setMessage(getString(R.string.RestartAppToTakeEffect));
            restart.setPositiveButton(getString(R.string.OK), (__, ___) -> AppRestartHelper.triggerRebirth(context, new Intent(context, LaunchActivity.class)));
            restart.show();
        } catch (Exception e) {
            AlertUtil.showSimpleAlert(context, e);
        }

    }

    @SuppressLint("ApplySharedPref")
    public static void importSettings(JsonObject configJson) throws JSONException {

        for (Map.Entry<String, JsonElement> element : configJson.entrySet()) {
            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(element.getKey(), Activity.MODE_PRIVATE);
            SharedPreferences.Editor editor = preferences.edit();
            for (Map.Entry<String, JsonElement> config : ((JsonObject) element.getValue()).entrySet()) {
                String key = config.getKey();
                JsonPrimitive value = (JsonPrimitive) config.getValue();
                if (value.isBoolean()) {
                    editor.putBoolean(key, value.getAsBoolean());
                } else if (value.isNumber()) {
                    boolean isLong = false;
                    boolean isFloat = false;
                    if (key.endsWith("_long")) {
                        key = StringsKt.substringBeforeLast(key, "_long", key);
                        isLong = true;
                    } else if (key.endsWith("_float")) {
                        key = StringsKt.substringBeforeLast(key, "_float", key);
                        isFloat = true;
                    }
                    if (isLong) {
                        editor.putLong(key, value.getAsLong());
                    } else if (isFloat) {
                        editor.putFloat(key, value.getAsFloat());
                    } else {
                        editor.putInt(key, value.getAsInt());
                    }
                } else {
                    editor.putString(key, value.getAsString());
                }
            }
            editor.commit();
        }

    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, 21);
        } catch (android.content.ActivityNotFoundException ex) {
            AlertUtil.showSimpleAlert(getParentActivity(), ex);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == 21 && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                File cacheDir = AndroidUtilities.getCacheDir();
                String tempFile = UUID.randomUUID().toString().replace("-", "") + ".nekox-settings.json";
                File file = new File(cacheDir.getPath(), tempFile);
                try {
                    final InputStream inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                    if (inputStream != null) {
                        OutputStream outputStream = new FileOutputStream(file);
                        final byte[] buffer = new byte[4 * 1024];
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, read);
                        }
                        inputStream.close();
                        outputStream.flush();
                        outputStream.close();
                        importSettings(getParentActivity(), file);
                    }
                } catch (Exception ignore) {
                }
            }
            super.onActivityResultFragment(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean isActionBarCrossfadeEnabled() {
        return false;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        // Fragment background
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));

        // ActionBar colors
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_avatar_backgroundActionBarBlue));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_avatar_actionBarIconBlue));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_avatar_actionBarSelectorBlue));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBMENUBACKGROUND, null, null, null, null, Theme.key_actionBarDefaultSubmenuBackground));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBMENUITEM, null, null, null, null, Theme.key_actionBarDefaultSubmenuItem));

        // If listView exists, bind its children
        RecyclerListView lv = typePage != null ? typePage.listView : null;
        if (lv != null) {
            // List background (gray)
            themeDescriptions.add(new ThemeDescription(lv, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));

            // Cell backgrounds (white)
            themeDescriptions.add(new ThemeDescription(lv, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextCell.class, HeaderCell.class, TextSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));

            // List glow and selector
            themeDescriptions.add(new ThemeDescription(lv, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_avatar_backgroundActionBarBlue));
            themeDescriptions.add(new ThemeDescription(lv, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));

            // Dividers and gray shadow section
            themeDescriptions.add(new ThemeDescription(lv, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
            themeDescriptions.add(new ThemeDescription(lv, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));

            // Text colors inside cells
            themeDescriptions.add(new ThemeDescription(lv, 0, new Class[]{TextCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            themeDescriptions.add(new ThemeDescription(lv, 0, new Class[]{TextCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
            // TextSettingsCell text colors
            themeDescriptions.add(new ThemeDescription(lv, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
            themeDescriptions.add(new ThemeDescription(lv, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));

            // Header text color
            themeDescriptions.add(new ThemeDescription(lv, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        }

        return themeDescriptions;
    }

    public String getTitle() {
        return getString(R.string.NekoSettings);
    }
}
