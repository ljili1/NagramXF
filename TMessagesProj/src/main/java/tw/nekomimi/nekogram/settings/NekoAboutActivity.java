package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.ProfileActivity.sendLogs;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.voip.VoIPHelper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import kotlin.Unit;
import tw.nekomimi.nekogram.DatacenterActivity;
import tw.nekomimi.nekogram.helpers.remote.UpdateHelper;
import tw.nekomimi.nekogram.ui.BottomBuilder;
import tw.nekomimi.nekogram.utils.AlertUtil;
import tw.nekomimi.nekogram.utils.FileUtil;
import tw.nekomimi.nekogram.utils.ShareUtil;
import xyz.nextalone.nagram.NaConfig;

public class NekoAboutActivity extends BaseNekoSettingsActivity {

    // Row positions
    private int infoHeaderRow;
    private int updatesRow;
    private int toggleLogsRow;
    private int sendLogsRow;
    private int clearLogsRow;
    private int linksHeaderRow;
    private int forkRow;
    private int xChannelRow;
    private int channelRow;
    private int channelTipsRow;
    private int sourceCodeRow;
    private int translationRow;
    private int datacenterStatusRow;
    private int infoLinksDividerRow;
    private int bottomDividerRow;

    @Override
    protected void updateRows() {
        super.updateRows();
        rowCount = 0;

        // Info Section
        infoHeaderRow = rowCount++;
        updatesRow = rowCount++;
        toggleLogsRow = rowCount++;

        // Conditionally add log options
        if (BuildVars.LOGS_ENABLED) { //
            sendLogsRow = rowCount++;
            clearLogsRow = rowCount++;
        } else {
            sendLogsRow = -1;
            clearLogsRow = -1;
        }

        infoLinksDividerRow = rowCount++;

        // Links Section
        linksHeaderRow = rowCount++;
        forkRow = rowCount++;
        xChannelRow = rowCount++;
        channelRow = rowCount++;
        channelTipsRow = rowCount++;
        sourceCodeRow = rowCount++;
        translationRow = rowCount++;
        datacenterStatusRow = rowCount++;
        bottomDividerRow = rowCount++;
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == updatesRow) {
            onUpdatesClick();
        } else if (position == toggleLogsRow) {
            // Switch log status
            BuildVars.LOGS_ENABLED = BuildVars.DEBUG_VERSION = !BuildVars.LOGS_ENABLED; //
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE); //
            sharedPreferences.edit().putBoolean("logsEnabled", BuildVars.LOGS_ENABLED).apply(); //
            
            // Re-calculate row positions and refresh the list
            updateRows();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        } else if (position == sendLogsRow) {
            sendLogs();
        } else if (position == clearLogsRow) {
            clearLogs();
        }
        // Link Rows
        else if (position == forkRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramX_Fork", this, 1);
        } else if (position == xChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramX", this, 1);
        } else if (position == channelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("nagram_channel", this, 1);
        } else if (position == channelTipsRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramTips", this, 1);
        } else if (position == translationRow) {
            Browser.openUrl(getParentActivity(), "https://crowdin.com/project/NagramX");
        } else if (position == sourceCodeRow) {
            Browser.openUrl(getParentActivity(), "https://github.com/Keeperorowner/NagramX_Fork");
        } else if (position == datacenterStatusRow) {
            presentFragment(new DatacenterActivity(0));
        }
    }

    private void onUpdatesClick() {
        if (getParentActivity() == null) {
            return;
        }
        BottomBuilder builder = new BottomBuilder(getParentActivity());
        builder.addTitle(getString(R.string.Updates));

        // Beta version switch
        builder.addCheckItem(getString(R.string.EnableBetaVersion), R.drawable.test_tube_solar, NaConfig.INSTANCE.getEnableBetaVersion().Bool(), false, (cell, isChecked) -> {
            NaConfig.INSTANCE.getEnableBetaVersion().setConfigBool(isChecked);
            return Unit.INSTANCE;
        });

        // Check update on startup switch
        builder.addCheckItem(getString(R.string.CheckUpdateOnStartup), R.drawable.msg_timer, NaConfig.INSTANCE.getCheckUpdateOnStartup().Bool(), false, (cell, isChecked) -> {
            NaConfig.INSTANCE.getCheckUpdateOnStartup().setConfigBool(isChecked);
            return Unit.INSTANCE;
        });

        // Startup update check interval - temporarily commented out for testing
        /*
        String currentInterval = " - ";
        int intervalHours = NaConfig.INSTANCE.getStartupUpdateCheckInterval().Int();
        switch (intervalHours) {
            case 1:
                currentInterval += getString(R.string.UpdateCheckInterval1Hour);
                break;
            case 4:
                currentInterval += getString(R.string.UpdateCheckInterval4Hours);
                break;
            case 8:
                currentInterval += getString(R.string.UpdateCheckInterval8Hours);
                break;
            case 24:
                currentInterval += getString(R.string.UpdateCheckInterval24Hours);
                break;
            default:
                currentInterval += intervalHours + " " + getString(R.string.Hours);
                break;
        }
        builder.addItem(getString(R.string.StartupUpdateCheckInterval) + currentInterval, R.drawable.msg_timer, (it) -> {
            BottomBuilder intervalBuilder = new BottomBuilder(getParentActivity());
            intervalBuilder.addTitle(getString(R.string.StartupUpdateCheckInterval), getString(R.string.StartupUpdateCheckIntervalNotice));
            intervalBuilder.addRadioItem(getString(R.string.UpdateCheckInterval1Hour), NaConfig.INSTANCE.getStartupUpdateCheckInterval().Int() == 1, (radioButtonCell) -> {
                NaConfig.INSTANCE.getStartupUpdateCheckInterval().setConfigInt(1);
                intervalBuilder.doRadioCheck(radioButtonCell);
                AndroidUtilities.runOnUIThread(intervalBuilder::dismiss, 500);
                return Unit.INSTANCE;
            });
            intervalBuilder.addRadioItem(getString(R.string.UpdateCheckInterval4Hours), NaConfig.INSTANCE.getStartupUpdateCheckInterval().Int() == 4, (radioButtonCell) -> {
                NaConfig.INSTANCE.getStartupUpdateCheckInterval().setConfigInt(4);
                intervalBuilder.doRadioCheck(radioButtonCell);
                AndroidUtilities.runOnUIThread(intervalBuilder::dismiss, 500);
                return Unit.INSTANCE;
            });
            intervalBuilder.addRadioItem(getString(R.string.UpdateCheckInterval8Hours), NaConfig.INSTANCE.getStartupUpdateCheckInterval().Int() == 8, (radioButtonCell) -> {
                NaConfig.INSTANCE.getStartupUpdateCheckInterval().setConfigInt(8);
                intervalBuilder.doRadioCheck(radioButtonCell);
                AndroidUtilities.runOnUIThread(intervalBuilder::dismiss, 500);
                return Unit.INSTANCE;
            });
            intervalBuilder.addRadioItem(getString(R.string.UpdateCheckInterval24Hours), NaConfig.INSTANCE.getStartupUpdateCheckInterval().Int() == 24, (radioButtonCell) -> {
                NaConfig.INSTANCE.getStartupUpdateCheckInterval().setConfigInt(24);
                intervalBuilder.doRadioCheck(radioButtonCell);
                AndroidUtilities.runOnUIThread(intervalBuilder::dismiss, 500);
                return Unit.INSTANCE;
            });
            showDialog(intervalBuilder.create());
            return Unit.INSTANCE;
        });
        */

        // Clean updates cache with icon
        builder.addItem(getString(R.string.DebugMenuCleanAppUpdate), R.drawable.msg_clear, (it) -> {
            UpdateHelper.cleanAppUpdate(); //
            return Unit.INSTANCE;
        });

        // Check Update - moved to bottom
        builder.addItem(getString(R.string.CheckUpdate), R.drawable.msg_search_solar,
                (it) -> {
                    Browser.openUrl(getParentActivity(), "tg://update"); //
                    return Unit.INSTANCE;
                });

        showDialog(builder.create());
    }

    private void sendLogs() {
        Activity activity = getParentActivity();
        if (activity == null) { //
            return;
        }
        AlertDialog progressDialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER); //
        progressDialog.setCanCancel(false); //
        progressDialog.show(); //
        Utilities.globalQueue.postRunnable(() -> { //
            try {
                File dir = AndroidUtilities.getLogsDir(); //
                if (dir == null) { //
                    AndroidUtilities.runOnUIThread(progressDialog::dismiss); //
                    return;
                }
                File logcatFile = new File(dir, "NagramX-" + System.currentTimeMillis() + ".log"); //
                try {
                    ProcessBuilder pb1 = new ProcessBuilder("logcat", "-df", logcatFile.getPath()); //
                    pb1.inheritIO(); //
                    Process process1 = pb1.start(); //
                    boolean finished1 = process1.waitFor(10, TimeUnit.SECONDS); //
                    if (!finished1) { //
                        process1.destroyForcibly(); //
                    }
                    ProcessBuilder pb2 = new ProcessBuilder("logcat", "-c"); //
                    pb2.inheritIO(); //
                    Process process2 = pb2.start(); //
                    boolean finished2 = process2.waitFor(10, TimeUnit.SECONDS); //
                    if (!finished2) {
                        process2.destroyForcibly(); //
                    }
                } catch (Exception e) {
                    AlertUtil.showToast(e); //
                }

                File zipFile = new File(dir, "logs.zip"); //
                if (zipFile.exists()) { //
                    zipFile.delete(); //
                }

                ArrayList<File> files = new ArrayList<>(Arrays.asList(dir.listFiles())); //

                File filesDir = ApplicationLoader.getFilesDirFixed(); //
                filesDir = new File(filesDir, "malformed_database/"); //
                if (filesDir.exists() && filesDir.isDirectory()) { //
                    File[] malformedDatabaseFiles = filesDir.listFiles(); //
                    if (malformedDatabaseFiles != null) {
                        files.addAll(Arrays.asList(malformedDatabaseFiles)); //
                    }
                }
                final boolean[] finished = {false};
                try (FileOutputStream dest = new FileOutputStream(zipFile); //
                     ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(dest))) { //
                    byte[] data = new byte[1024 * 64]; //
                    for (File file : files) {
                        if (!file.exists()) { //
                            continue;
                        }
                        try (FileInputStream fi = new FileInputStream(file); //
                             BufferedInputStream origin = new BufferedInputStream(fi, data.length)) { //
                            ZipEntry entry = new ZipEntry(file.getName()); //
                            out.putNextEntry(entry); //
                            int count;
                            while ((count = origin.read(data, 0, data.length)) != -1) { //
                                out.write(data, 0, count); //
                            }
                        }
                    }
                    finished[0] = true; //
                } catch (Exception e) {
                    FileLog.e(e);
                }
                AndroidUtilities.runOnUIThread(() -> { //
                    try {
                        progressDialog.dismiss(); //
                    } catch (Exception ignore) {
                    }
                    if (finished[0]) { //
                        ShareUtil.shareFile(activity, zipFile); //
                    } else {
                        Toast.makeText(activity, LocaleController.getString("ErrorOccurred", R.string.ErrorOccurred), Toast.LENGTH_SHORT).show(); //
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private void clearLogs() {
        AlertDialog pro = AlertUtil.showProgress(getParentActivity()); //
        pro.show(); //
        Utilities.globalQueue.postRunnable(() -> {
            FileUtil.delete(AndroidUtilities.getLogsDir()); //
            AndroidUtilities.runOnUIThread(pro::dismiss);
        });
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    public String getTitle() {
        return getString(R.string.NagranX_About);
    }

    @Override
    protected String getActionBarTitle() {
        return getTitle();
    }

    @Override
    protected boolean hasWhiteActionBar() {
        return false;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        // Fragment background (gray list background)
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));

        // ActionBar colors (keep consistent with other Neko* settings screens)
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_avatar_backgroundActionBarBlue));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_avatar_actionBarIconBlue));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_avatar_actionBarSelectorBlue));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBMENUBACKGROUND, null, null, null, null, Theme.key_actionBarDefaultSubmenuBackground));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBMENUITEM, null, null, null, null, Theme.key_actionBarDefaultSubmenuItem));

        // ListView visuals
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_avatar_backgroundActionBarBlue));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));

        // Cell backgrounds (white)
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{HeaderCell.class, TextCell.class, TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundWhite));

        // Text colors inside cells
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));

        return themeDescriptions;
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_TEXT:
                    view = new TextCell(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO_PRIVACY:
                    view = new TextInfoPrivacyCell(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    return super.onCreateViewHolder(parent, viewType);
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();

            switch (viewType) {
                case TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == infoHeaderRow) {
                        headerCell.setText(getString(R.string.NagramX_Info));
                    } else if (position == linksHeaderRow) {
                        headerCell.setText(getString(R.string.NagramX_Links));
                    }
                    break;
                }
                case TYPE_TEXT: {
                    TextCell textCell = (TextCell) holder.itemView;
                    if (position == updatesRow) {
                        textCell.setTextAndIcon(getString(R.string.Updates), R.drawable.round_update_white_28, true);
                    } else if (position == toggleLogsRow) {
                        String text = BuildVars.LOGS_ENABLED ? getString(R.string.DebugMenuDisableLogs) : getString(R.string.DebugMenuEnableLogs); //
                        textCell.setTextAndIcon(text, R.drawable.bug_solar, true);
                    } else if (position == sendLogsRow) {
                        textCell.setTextAndIcon(getString(R.string.DebugSendLogs), R.drawable.ic_upward_solar, true);
                    } else if (position == clearLogsRow) {
                        textCell.setTextAndIcon(getString(R.string.DebugClearLogs), R.drawable.msg_clear_solar, true);
                    } else if (position == forkRow) {
                        textCell.setTextAndValue(getString(R.string.Fork), "@NagramX_Fork", true, true);
                    } else if (position == xChannelRow) {
                        textCell.setTextAndValue(getString(R.string.XChannel), "@NagramX", true, true);
                    } else if (position == channelRow) {
                        textCell.setTextAndValue(getString(R.string.OfficialChannel), "@nagram_channel", true, true);
                    } else if (position == channelTipsRow) {
                        textCell.setTextAndValue(getString(R.string.TipsChannel), "@" + "NagramTips", true, true);
                    } else if (position == sourceCodeRow) {
                        textCell.setTextAndValue(getString(R.string.SourceCode), "Github", true, true);
                    } else if (position == translationRow) {
                        textCell.setTextAndValue(getString(R.string.TransSite), "Crowdin", true, true);
                    } else if (position == datacenterStatusRow) {
                        textCell.setText(getString(R.string.DatacenterStatus), true);
                    }
                    break;
                }
                case TYPE_SHADOW: {
                     holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == infoHeaderRow || position == linksHeaderRow) {
                return TYPE_HEADER;
            } else if (position == bottomDividerRow || position == infoLinksDividerRow) {
                return TYPE_SHADOW;
            }
            return TYPE_TEXT;
        }
    }
}