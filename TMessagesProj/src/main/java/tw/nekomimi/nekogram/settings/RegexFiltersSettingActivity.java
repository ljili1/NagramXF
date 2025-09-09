/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.AyuFilter;
import tw.nekomimi.nekogram.ui.RegexFilterEditActivity;
import tw.nekomimi.nekogram.ui.RegexFilterPopup;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.NaConfig;

public class RegexFiltersSettingActivity extends BaseNekoSettingsActivity {

    private static final int MENU_IMPORT = 1;
    private static final int MENU_EXPORT = 2;
    private static final int MENU_CLEAR = 3;

    private final long dialogId;
    private int filtersOptionHeaderRow;
    private int regexFiltersEnabledRow;
    private int regexFiltersEnableInChatsRow;
    private int ignoreBlockedRow;
    private int ignoreBlockedNoticeRow;
    private int filtersOptionDividerRow;
    private int filtersHeaderRow;
    private int filtersDividerRow;
    private int addFilterBtnRow;

    public RegexFiltersSettingActivity() {
        dialogId = 0L;
    }

    public RegexFiltersSettingActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        filtersOptionHeaderRow = rowCount++;
        regexFiltersEnabledRow = rowCount++;
        regexFiltersEnableInChatsRow = rowCount++;
        ignoreBlockedRow = rowCount++;
        ignoreBlockedNoticeRow = rowCount++;
        filtersOptionDividerRow = rowCount++;

        filtersHeaderRow = rowCount++;
        var filters = AyuFilter.getRegexFilters();
        rowCount += filters.size();
        if (!filters.isEmpty()) {
            filtersDividerRow = rowCount++;
        } else {
            filtersDividerRow = -1;
        }

        addFilterBtnRow = rowCount++;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onResume() {
        super.onResume();

        updateRows();

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == regexFiltersEnabledRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            NaConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(enabled);
        } else if (position == regexFiltersEnableInChatsRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            NaConfig.INSTANCE.getRegexFiltersEnableInChats().setConfigBool(enabled);
        } else if (position == ignoreBlockedRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            NekoConfig.ignoreBlocked.setConfigBool(enabled);
        } else if (position > filtersHeaderRow && position < filtersDividerRow) {
            ArrayList<AyuFilter.FilterModel> filterModels = AyuFilter.getRegexFilters();
            int filterIndex = position - filtersHeaderRow - 1;
            if (filterIndex < 0 || filterIndex >= filterModels.size()) {
                return;
            }

            if (dialogId == 0 && LocaleController.isRTL && x > AndroidUtilities.dp(76) || !LocaleController.isRTL && x < (view.getMeasuredWidth() - AndroidUtilities.dp(76))) {
                RegexFilterPopup.show(this, view, x, y, filterIndex);
            } else {
                TextCheckCell textCheckCell = (TextCheckCell) view;
                AyuFilter.FilterModel filterModel = filterModels.get(filterIndex);

                boolean enabled = !textCheckCell.isChecked();
                textCheckCell.setChecked(enabled);
                filterModel.setEnabled(enabled, dialogId);
                AyuFilter.saveFilter(filterModels);
            }
        } else if (position == addFilterBtnRow) {
            presentFragment(new RegexFilterEditActivity());
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (dialogId == 0 && position > filtersHeaderRow && position < filtersDividerRow) {
            int filterIndex = position - filtersHeaderRow - 1;
            ArrayList<AyuFilter.FilterModel> filterModels = AyuFilter.getRegexFilters();
            if (filterIndex >= 0 && filterIndex < filterModels.size()) {
                RegexFilterPopup.show(this, view, x, y, filterIndex);
                return true;
            }
        }
        return super.onItemLongClick(view, position, x, y);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.RegexFilters);
    }

    @Override
    public ActionBar createActionBar(Context context) {
        ActionBar actionBar = super.createActionBar(context);
        
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem menuItem = menu.addItem(0, R.drawable.ic_ab_other);
        menuItem.addSubItem(MENU_IMPORT, R.drawable.msg_download, LocaleController.getString("ImportRegexFilters", R.string.ImportRegexFilters));
        
        if (!AyuFilter.getRegexFilters().isEmpty()) {
            menuItem.addSubItem(MENU_EXPORT, R.drawable.msg_shareout, LocaleController.getString("ExportRegexFilters", R.string.ExportRegexFilters));
        }
        
        menuItem.addSubItem(MENU_CLEAR, R.drawable.msg_delete, LocaleController.getString("ClearAllRegexFilters", R.string.ClearAllRegexFilters));
        
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_IMPORT) {
                    showImportOptions();
                } else if (id == MENU_EXPORT) {
                    showExportOptions();
                } else if (id == MENU_CLEAR) {
                    clearAllFilters();
                }
            }
        });
        
        return actionBar;
    }

    private void showImportOptions() {
        BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
        
        LinearLayout linearLayout = new LinearLayout(getParentActivity());
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        
        TextInfoPrivacyCell titleCell = new TextInfoPrivacyCell(getParentActivity());
        titleCell.setText(LocaleController.getString("SelectImportMethod", R.string.SelectImportMethod));
        titleCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        linearLayout.addView(titleCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        
        TextCell pasteCell = new TextCell(getParentActivity());
        pasteCell.setText(LocaleController.getString("PasteFromClipboard", R.string.PasteFromClipboard), false);
        pasteCell.setBackground(Theme.getSelectorDrawable(false));
        pasteCell.setOnClickListener(v -> {
            builder.getDismissRunnable().run();
            importFromClipboard();
        });
        linearLayout.addView(pasteCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        
        TextCell urlCell = new TextCell(getParentActivity());
        urlCell.setText(LocaleController.getString("ImportFromURL", R.string.ImportFromURL), false);
        urlCell.setBackground(Theme.getSelectorDrawable(false));
        urlCell.setOnClickListener(v -> {
            builder.getDismissRunnable().run();
            showUrlImportDialog();
        });
        linearLayout.addView(urlCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        
        
        builder.setCustomView(linearLayout);
        builder.show();
    }

    private void importFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            String text = item.getText().toString();
            processImportedData(text);
        } else {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("ClipboardEmpty", R.string.ClipboardEmpty)).show();
        }
    }

    private void showUrlImportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("ImportFromURL", R.string.ImportFromURL));

        LinearLayout linearLayout = new LinearLayout(getParentActivity());
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = new EditTextBoldCursor(getParentActivity());
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(10), 0));

        builder.setView(linearLayout);
        builder.setPositiveButton(getString(R.string.OK), (dialog, which) -> {
            String url = editText.getText().toString().trim();
            if (!url.isEmpty()) {
                importFromUrl(url);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);

        AlertDialog dialog = builder.create();
        showDialog(dialog);
        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }

    private void importFromUrl(String url) {
        if (!isValidUrl(url)) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("InvalidUrl", R.string.InvalidUrl)).show();
            return;
        }
        
        if (url.contains("dpaste.com/") && !url.endsWith(".txt")) {
            url = url + ".txt";
        }
        
        final String finalUrl = url;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                URL urlObj = new URL(finalUrl);
                HttpURLConnection connection = (HttpURLConnection) urlObj.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    AndroidUtilities.runOnUIThread(() -> {
                        processImportedData(response.toString());
                    });
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("ImportFromUrl", R.string.ImportFromUrl)).show();
                    });
                }
                connection.disconnect();
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> {
                    BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("ImportFromUrl", R.string.ImportFromUrl) + ": " + e.getMessage()).show();
                });
            }
        });
    }
    
    private boolean isValidUrl(String url) {
        try {
            new URL(url);
            return url.startsWith("http://") || url.startsWith("https://");
        } catch (Exception e) {
            return false;
        }
    }


    private void processImportedData(String json) {
        try {
            AyuFilter.FilterModel[] importedFilters = new Gson().fromJson(json, AyuFilter.FilterModel[].class);
            
            if (importedFilters != null && importedFilters.length > 0) {
                ArrayList<AyuFilter.FilterModel> currentFilters = AyuFilter.getRegexFilters();
                for (AyuFilter.FilterModel filter : importedFilters) {
                    currentFilters.add(0, filter);
                }
                AyuFilter.saveFilter(currentFilters);
                updateRows();
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString("RegexFiltersImported", R.string.RegexFiltersImported, importedFilters.length)).show();
            } else {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("ImportFileFormatError", R.string.ImportFileFormatError)).show();
            }
        } catch (Exception e) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.formatString("ImportFailed", R.string.ImportFailed, e.getMessage())).show();
        }
    }

    private void showExportOptions() {
        ArrayList<AyuFilter.FilterModel> filters = AyuFilter.getRegexFilters();
        if (filters.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("NoRegexFiltersToExport", R.string.NoRegexFiltersToExport)).show();
            return;
        }
        
        BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
        
        LinearLayout linearLayout = new LinearLayout(getParentActivity());
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        
        TextInfoPrivacyCell titleCell = new TextInfoPrivacyCell(getParentActivity());
        titleCell.setText(LocaleController.getString("SelectExportMethod", R.string.SelectExportMethod));
        titleCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        linearLayout.addView(titleCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        
        TextCell copyCell = new TextCell(getParentActivity());
        copyCell.setBackground(Theme.getSelectorDrawable(false));
        copyCell.setText(LocaleController.getString("CopyToClipboard", R.string.CopyToClipboard), false);
        copyCell.setOnClickListener(v -> {
            builder.getDismissRunnable().run();
            exportToClipboard();
        });
        linearLayout.addView(copyCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        
        TextCell dpasteCell = new TextCell(getParentActivity());
        dpasteCell.setBackground(Theme.getSelectorDrawable(false));
        dpasteCell.setText(LocaleController.getString("PublishToDpaste", R.string.PublishToDpaste), false);
        dpasteCell.setOnClickListener(v -> {
            builder.getDismissRunnable().run();
            publishToDpaste();
        });
        linearLayout.addView(dpasteCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        
        builder.setCustomView(linearLayout);
        builder.show();
    }

    private void exportToClipboard() {
        ArrayList<AyuFilter.FilterModel> filters = AyuFilter.getRegexFilters();
        String json = new Gson().toJson(filters);
        
        ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Regex Filters", json);
        clipboard.setPrimaryClip(clip);
        
        BulletinFactory.of(this).createSimpleBulletin(R.raw.copy, LocaleController.formatString("RegexFiltersCopiedToClipboard", R.string.RegexFiltersCopiedToClipboard, filters.size())).show();
    }

    private void publishToDpaste() {
        ArrayList<AyuFilter.FilterModel> filters = AyuFilter.getRegexFilters();
        String json = new Gson().toJson(filters);
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                URL url = new URL("https://dpaste.com/api/v2/");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                
                String postData = "content=" + java.net.URLEncoder.encode(json, "UTF-8") + 
                                 "&syntax=json" + 
                                 "&expiry_days=30";
                
                OutputStream os = connection.getOutputStream();
                os.write(postData.getBytes("UTF-8"));
                os.flush();
                os.close();
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_CREATED) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    String pasteUrl = reader.readLine();
                    reader.close();
                    
                    AndroidUtilities.runOnUIThread(() -> {
                        ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("Dpaste URL", pasteUrl);
                        clipboard.setPrimaryClip(clip);
                        
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.saved_messages, LocaleController.formatString("PublishedToDpaste", R.string.PublishedToDpaste, pasteUrl)).show();
                    });
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("PublishToDpasteError", R.string.PublishToDpasteError)).show();
                    });
                }
                connection.disconnect();
            } catch (Exception e) {
                AndroidUtilities.runOnUIThread(() -> {
                    BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("PublishToDpasteError", R.string.PublishToDpasteError) + ": " + e.getMessage()).show();
                });
            }
        });
    }
    

    private void clearAllFilters() {
        ArrayList<AyuFilter.FilterModel> filters = AyuFilter.getRegexFilters();
        if (filters.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString("NoRegexFiltersToClear", R.string.NoRegexFiltersToClear)).show();
            return;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("ClearAllRegexFilters", R.string.ClearAllRegexFilters));
        builder.setMessage(LocaleController.getString("ClearAllRegexFiltersConfirm", R.string.ClearAllRegexFiltersConfirm));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            NaConfig.INSTANCE.getRegexFiltersData().setConfigString("[]");
            AyuFilter.rebuildCache();
            updateRows();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString("RegexFiltersCleared", R.string.RegexFiltersCleared)).show();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean payload) {
            switch (holder.getItemViewType()) {
                case TYPE_SHADOW:
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                case TYPE_CHECK:
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    if (position == regexFiltersEnabledRow) {
                        textCheckCell.setTextAndCheck(getString(R.string.RegexFiltersEnable),
                                NaConfig.INSTANCE.getRegexFiltersEnabled().Bool(), true);
                    } else if (position == regexFiltersEnableInChatsRow) {
                        textCheckCell.setTextAndCheck(getString(R.string.RegexFiltersEnableInChats),
                                NaConfig.INSTANCE.getRegexFiltersEnableInChats().Bool(), true);
                    } else if (position == ignoreBlockedRow) {
                        textCheckCell.setTextAndCheck(getString(R.string.IgnoreBlocked), NekoConfig.ignoreBlocked.Bool(), true);
                    } else if (position > filtersHeaderRow && position < filtersDividerRow) {
                        ArrayList<AyuFilter.FilterModel> filterModels = AyuFilter.getRegexFilters();
                        int filterIndex = position - filtersHeaderRow - 1;
                        if (filterIndex >= 0 && filterIndex < filterModels.size()) {
                            AyuFilter.FilterModel filterModel = filterModels.get(filterIndex);
                            textCheckCell.setTextAndCheck(filterModel.regex, filterModel.isEnabled(dialogId), true);
                        }
                    }
                    break;
                case TYPE_INFO_PRIVACY:
                    TextInfoPrivacyCell infoPrivacyCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == ignoreBlockedNoticeRow) {
                        infoPrivacyCell.setText(getString(R.string.IgnoreBlockedAbout));
                        infoPrivacyCell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                case TYPE_TEXT:
                    TextCell textCell = (TextCell) holder.itemView;
                    if (position == addFilterBtnRow) {
                        textCell.setTextAndIcon(getString(R.string.RegexFiltersAdd), R.drawable.msg_add, false);
                    }
                    break;
                case TYPE_HEADER:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == filtersOptionHeaderRow) {
                        headerCell.setText(getString(R.string.General));
                    } else if (position == filtersHeaderRow) {
                        headerCell.setText(getString(R.string.RegexFiltersHeader));
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == filtersDividerRow && filtersDividerRow != 0 || position == filtersOptionDividerRow) {
                return TYPE_SHADOW;
            } else if (position == filtersHeaderRow || position == filtersOptionHeaderRow) {
                return TYPE_HEADER;
            } else if (position == addFilterBtnRow) {
                return TYPE_TEXT;
            } else if (position == ignoreBlockedNoticeRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_CHECK;
        }
    }
}
