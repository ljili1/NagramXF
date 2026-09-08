package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.RecyclerListView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tw.nekomimi.nekogram.helpers.VlessProxyManager;

/**
 * Built-in VLESS node manager, rewritten to follow the Telegram/NekoX native
 * proxy-list interaction paradigm (ProxyListActivity):
 *
 *  - Long-press a node enters action-mode (multi-select) with Share / Delete.
 *  - Nodes report TCP latency through VlessProxyManager.pingNode() on the
 *    global queue (the native page's ConnectionsManager.checkProxy path only
 *    understands SOCKS5/MTProto proxies, so it is not used here).
 *  - Active node shows a check mark; the list is ordered with the active node first.
 *
 * Entry points: TG proxy page ("VLESS Proxy" manage row / node rows) and the
 * app settings page. Nodes are stored as vless:// links in VlessProxyManager;
 * tapping a node enables the sing-box engine with that node and points Telegram
 * at the local 127.0.0.1 inbound.
 */
public class VlessSettingsActivity extends BaseFragment {

    private static final int MENU_SHARE = 1;
    private static final int MENU_DELETE = 2;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private ActionBarMenuItem shareMenuItem;
    private ActionBarMenuItem deleteMenuItem;
    private NumberTextView selectedCountView;

    private int descriptionRow;
    private int enableRow;
    private int nodesHeaderRow;
    private int nodeStartRow;
    private int addRow;
    private int scanRow;
    private int importRow;
    private int subscribeRow;
    private int testRow;
    private int infoRow;
    private int rowCount;

    private final List<String> nodes = new ArrayList<>();
    /** link -> ping ms, or -1 when unreachable, or absent when not yet checked. */
    private final Map<String, Long> pings = new HashMap<>();
    private final List<String> selectedItems = new ArrayList<>();
    private boolean actionModeVisible;
    private boolean testing;

    public VlessSettingsActivity() {
        super();
    }

    private int nodeRowCount() {
        return nodeStartRow < 0 ? 0 : nodes.size();
    }

    private boolean isNodeRow(int position) {
        return nodeStartRow >= 0 && position >= nodeStartRow && position < nodeStartRow + nodes.size();
    }

    private void reloadNodes() {
        nodes.clear();
        nodes.addAll(VlessProxyManager.getNodes());
        // Active node floats to the top so it is always easy to find.
        for (int i = 0; i < nodes.size(); i++) {
            if (VlessProxyManager.isActiveNode(nodes.get(i))) {
                nodes.remove(i);
                nodes.add(0, VlessProxyManager.getVlessLink());
                break;
            }
        }
    }

    private void updateRows() {
        rowCount = 0;
        descriptionRow = rowCount++;
        enableRow = rowCount++;
        if (nodes.isEmpty()) {
            nodesHeaderRow = -1;
            nodeStartRow = -1;
        } else {
            nodesHeaderRow = rowCount++;
            nodeStartRow = rowCount;
            rowCount += nodes.size();
        }
        addRow = rowCount++;
        scanRow = rowCount++;
        importRow = rowCount++;
        subscribeRow = rowCount++;
        testRow = rowCount++;
        infoRow = rowCount++;
    }

    private void rebuild() {
        reloadNodes();
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        checkActionMode();
    }

    private void showToast(String text) {
        Context ctx = getParentActivity();
        if (ctx != null) {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        }
    }

    /** Imports pasted text / subscription body / QR payload. */
    private void doImport(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            showToast(getString(R.string.VlessNoLinkFound));
            return;
        }
        int added = VlessProxyManager.importFromText(raw);
        if (added > 0) {
            showToast(LocaleController.formatString("VlessNodesAdded", R.string.VlessNodesAdded, added));
        } else {
            showToast(getString(R.string.VlessNoLinkFound));
        }
        rebuild();
    }

    private void selectNode(String link) {
        VlessProxyManager.selectNode(link);
        rebuild();
    }

    private void deleteNode(String link) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(getString(R.string.VlessDeleteNodeTitle));
        builder.setMessage(getString(R.string.VlessDeleteNodeConfirm));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Delete), (dialogInterface, i) -> {
            VlessProxyManager.removeNode(link);
            pings.remove(link);
            selectedItems.remove(link);
            rebuild();
        });
        showDialog(builder.create());
    }

    private void showAddDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(getString(R.string.VlessAddNode));

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintText(getString(R.string.VlessLinkHint));
        editText.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
        editText.setSingleLine(false);
        editText.setMinLines(2);
        editText.setMaxLines(6);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setFocusable(true);
        editText.setTransformHintToHeader(true);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, getResourceProvider()),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), 0);
        container.addView(editText);
        builder.setView(container);

        builder.setPositiveButton(getString(R.string.Add), (dialogInterface, i2) -> doImport(editText.getText().toString()));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        showDialog(dialog);
    }

    private void importFromClipboard() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
            if (cs != null && cs.toString().contains("vless://")) {
                doImport(cs.toString());
                return;
            }
        }
        showToast(getString(R.string.VlessNoLinkFound));
    }

    private void showSubscriptionDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(getString(R.string.VlessImportSubscription));

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintText(getString(R.string.VlessSubscriptionUrl));
        editText.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        editText.setFocusable(true);
        editText.setTransformHintToHeader(true);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, getResourceProvider()),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        editText.setBackground(null);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), 0);
        container.addView(editText);
        builder.setView(container);

        builder.setPositiveButton(getString(R.string.OK), (dialogInterface, i2) -> fetchSubscription(editText.getText().toString().trim()));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        showDialog(dialog);
    }

    private void fetchSubscription(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String body = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "NagramXF");
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    InputStream is = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    reader.close();
                    body = sb.toString();
                }
                connection.disconnect();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final String text = body;
            AndroidUtilities.runOnUIThread(() -> {
                if (text == null || text.isEmpty()) {
                    showToast(getString(R.string.VlessFetchFailed));
                } else {
                    doImport(text);
                }
            });
        });
    }

    private void testAllNodes() {
        if (testing) {
            return;
        }
        testing = true;
        pings.clear();
        if (listAdapter != null) {
            listAdapter.notifyItemRangeChanged(nodeStartRow, nodes.size());
            listAdapter.notifyItemChanged(testRow);
        }
        Utilities.globalQueue.postRunnable(() -> {
            for (int i = 0; i < nodes.size(); i++) {
                final String link = nodes.get(i);
                final long ping = VlessProxyManager.pingNode(link);
                AndroidUtilities.runOnUIThread(() -> {
                    pings.put(link, ping);
                    if (listAdapter != null && nodeStartRow >= 0) {
                        int idx = nodes.indexOf(link);
                        if (idx >= 0) {
                            listAdapter.notifyItemChanged(nodeStartRow + idx);
                        }
                    }
                });
            }
            AndroidUtilities.runOnUIThread(() -> {
                testing = false;
                if (listAdapter != null) {
                    listAdapter.notifyItemChanged(testRow);
                }
            });
        });
    }

    // --- Action mode (multi-select) ---

    private void toggleSelected(int position) {
        if (!isNodeRow(position)) {
            return;
        }
        String link = nodes.get(position - nodeStartRow);
        int idx = selectedItems.indexOf(link);
        if (idx >= 0) {
            selectedItems.remove(idx);
        } else {
            selectedItems.add(link);
        }
        listAdapter.notifyItemChanged(position);
        checkActionMode();
    }

    private void clearSelected() {
        ArrayList<String> copy = new ArrayList<>(selectedItems);
        selectedItems.clear();
        for (String link : copy) {
            int pos = nodes.indexOf(link);
            if (pos >= 0 && nodeStartRow >= 0) {
                listAdapter.notifyItemChanged(nodeStartRow + pos);
            }
        }
        checkActionMode();
    }

    private void checkActionMode() {
        boolean shouldShow = !selectedItems.isEmpty();
        if (shouldShow != actionModeVisible) {
            if (shouldShow) {
                actionBar.showActionMode();
            } else {
                actionBar.hideActionMode();
            }
            actionModeVisible = shouldShow;
        }
        if (actionModeVisible) {
            selectedCountView.setNumber(selectedItems.size(), true);
            int deleteVisible = selectedItems.isEmpty() ? View.GONE : View.VISIBLE;
            if (deleteMenuItem != null) {
                deleteMenuItem.setVisibility(deleteVisible);
            }
            if (shareMenuItem != null) {
                shareMenuItem.setVisibility(deleteVisible);
            }
        }
    }

    private void shareSelected() {
        if (selectedItems.isEmpty()) {
            return;
        }
        StringBuilder links = new StringBuilder();
        for (String link : selectedItems) {
            if (links.length() > 0) {
                links.append("\n\n");
            }
            links.append(link);
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, links.toString());
        Intent chooser = Intent.createChooser(shareIntent,
                getString(selectedItems.size() > 1 ? R.string.ShareLinks : R.string.ShareLink));
        chooser.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getParentActivity() != null) {
            getParentActivity().startActivity(chooser);
        }
        clearSelected();
    }

    private void deleteSelected() {
        if (selectedItems.isEmpty()) {
            return;
        }
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        boolean single = selectedItems.size() == 1;
        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setMessage(getString(single ? R.string.DeleteProxyConfirm : R.string.DeleteProxyMultiConfirm));
        builder.setTitle(getString(R.string.DeleteProxyTitle));
        builder.setPositiveButton(getString(R.string.Delete), (dialogInterface, i) -> {
            ArrayList<String> copy = new ArrayList<>(selectedItems);
            for (String link : copy) {
                VlessProxyManager.removeNode(link);
                pings.remove(link);
            }
            selectedItems.clear();
            rebuild();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.VlessSettings));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (actionModeVisible) {
                        clearSelected();
                    } else {
                        finishFragment();
                    }
                } else if (id == MENU_SHARE) {
                    shareSelected();
                } else if (id == MENU_DELETE) {
                    deleteSelected();
                }
            }
        });

        ActionBarMenu menu = actionBar.createActionMode();
        selectedCountView = new NumberTextView(context);
        selectedCountView.setTextSize(18);
        selectedCountView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        selectedCountView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon, getResourceProvider()));
        selectedCountView.setNumber(0, false);
        menu.addView(selectedCountView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 16, 0, 0, 0));
        shareMenuItem = menu.addItemWithWidth(MENU_SHARE, R.drawable.msg_share, AndroidUtilities.dp(54));
        deleteMenuItem = menu.addItemWithWidth(MENU_DELETE, R.drawable.msg_delete, AndroidUtilities.dp(54));
        shareMenuItem.setVisibility(View.GONE);
        deleteMenuItem.setVisibility(View.GONE);

        reloadNodes();
        updateRows();

        fragmentView = new FrameLayoutFix(context);
        fragmentView.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.MATCH_PARENT));
        FrameLayoutFix parent = (FrameLayoutFix) fragmentView;

        listAdapter = new ListAdapter(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context, androidx.recyclerview.widget.LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (actionModeVisible) {
                toggleSelected(position);
                return;
            }
            onItemClick(position);
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (isNodeRow(position)) {
                if (!actionModeVisible) {
                    actionBar.showActionMode();
                }
                toggleSelected(position);
                return true;
            }
            return false;
        });
        listView.setSectionsType(RecyclerListView.SECTIONS_TYPE_SIMPLE);
        parent.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void onItemClick(int position) {
        if (position == enableRow) {
            boolean enabled = !VlessProxyManager.isEnabled();
            if (enabled && !VlessProxyManager.hasConfig()) {
                showAddDialog();
            } else {
                VlessProxyManager.setEnabled(enabled);
            }
            rebuild();
        } else if (isNodeRow(position)) {
            selectNode(nodes.get(position - nodeStartRow));
        } else if (position == addRow) {
            showAddDialog();
        } else if (position == scanRow) {
            CameraScanActivity.showAsSheet(this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
                @Override
                public void didFindQr(String text) {
                    doImport(text);
                }
            });
        } else if (position == importRow) {
            importFromClipboard();
        } else if (position == subscribeRow) {
            showSubscriptionDialog();
        } else if (position == testRow) {
            testAllNodes();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    private static class FrameLayoutFix extends android.widget.FrameLayout {
        public FrameLayoutFix(@NonNull Context context) {
            super(context);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        public ListAdapter(Context context) {
            this.mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position < 0) {
                return false;
            }
            return position == enableRow || isNodeRow(position) || position == addRow
                    || position == scanRow || position == importRow || position == subscribeRow || position == testRow;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == descriptionRow || position == infoRow) {
                return 0;
            } else if (position == enableRow) {
                return 1;
            } else if (position == nodesHeaderRow) {
                return 2;
            } else if (isNodeRow(position)) {
                return 3;
            }
            return 4;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case 1:
                    view = new TextCheckCell(mContext);
                    break;
                case 2:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
                    break;
                case 3:
                    view = new TextDetailSettingsCell(mContext);
                    break;
                default:
                    view = new TextSettingsCell(mContext);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            onBindViewHolder(holder, position, false);
        }

        /**
         * Custom 3-arg binder (not a RecyclerView override): the standard
         * 2-arg [onBindViewHolder] delegates here with partial = false.
         */
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case 0: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == descriptionRow) {
                        if (VlessProxyManager.isEnabled() && VlessProxyManager.hasConfig()) {
                            cell.setText(LocaleController.formatString("VlessRunningDescription", R.string.VlessRunningDescription, VlessProxyManager.nodeServerPort(VlessProxyManager.getVlessLink())));
                        } else if (!nodes.isEmpty()) {
                            cell.setText(getString(R.string.VlessOffDescription));
                        } else {
                            cell.setText(getString(R.string.VlessNoNodes));
                        }
                        cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == infoRow) {
                        cell.setText(getString(R.string.VlessDescription));
                        cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
                case 1: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == enableRow) {
                        cell.setTextAndCheck(getString(R.string.VlessEnable), VlessProxyManager.isEnabled(), nodesHeaderRow != -1);
                    }
                    break;
                }
                case 2: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == nodesHeaderRow) {
                        headerCell.setText(getString(R.string.VlessNodesHeader));
                    }
                    break;
                }
                case 3: {
                    TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                    String link = nodes.get(position - nodeStartRow);
                    boolean active = VlessProxyManager.isActiveNode(link);
                    boolean selected = selectedItems.contains(link);
                    String name = VlessProxyManager.nodeName(link);
                    String title = (active ? "✓ " : "") + (TextUtils.isEmpty(name) ? getString(R.string.VlessSettings) : name);
                    cell.setTextAndValue(title, nodeStatusText(link), position != nodeStartRow + nodes.size() - 1);
                    if (selected) {
                        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
                    } else {
                        cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
                case 4: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
                    if (position == addRow) {
                        cell.setText(getString(R.string.VlessAddNode), true);
                    } else if (position == scanRow) {
                        cell.setText(getString(R.string.VlessScanQr), true);
                    } else if (position == importRow) {
                        cell.setText(getString(R.string.VlessImportClipboard), true);
                    } else if (position == subscribeRow) {
                        cell.setText(getString(R.string.VlessImportSubscription), true);
                    } else if (position == testRow) {
                        cell.setText(testing ? getString(R.string.Checking) : getString(R.string.VlessTestNodes), false);
                    }
                    break;
                }
            }
        }

        private String nodeStatusText(String link) {
            String serverPort = VlessProxyManager.nodeServerPort(link);
            StringBuilder sb = new StringBuilder(serverPort);
            Long ping = pings.get(link);
            if (ping != null) {
                sb.append(" · ");
                if (ping >= 0) {
                    sb.append(ping).append(" ms");
                } else {
                    sb.append(getString(R.string.Unavailable));
                }
            }
            if (VlessProxyManager.isActiveNode(link)) {
                sb.append(" · ").append(getString(R.string.VlessNodeActive));
            }
            return sb.toString();
        }
    }
}
