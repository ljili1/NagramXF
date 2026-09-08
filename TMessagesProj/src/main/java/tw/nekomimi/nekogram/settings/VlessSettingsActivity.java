package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tw.nekomimi.nekogram.helpers.VlessProxyManager;

/**
 * NekoX-style manager for the built-in VLESS nodes.
 *
 * Entry points: TG proxy page ("VLESS Proxy" manage row / node rows) and the
 * app settings page. Nodes are stored as vless:// links in VlessProxyManager;
 * tapping a node enables the sing-box engine with that node and points Telegram
 * at the local 127.0.0.1 inbound.
 */
public class VlessSettingsActivity extends BaseNekoSettingsActivity {

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

    private final List<String> nodes = new ArrayList<>();
    private final Map<String, Long> pings = new HashMap<>(); // link -> ping ms or -1

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
        if (nodeStartRow < 0 || nodeStartRow + nodes.size() > rowCount) {
            // keep safe; rows are rebuilt by rebuild()
        }
    }

    private void rebuild() {
        reloadNodes();
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
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
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.VlessDeleteNodeTitle));
        builder.setMessage(getString(R.string.VlessDeleteNodeConfirm));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Delete), (dialogInterface, i) -> {
            VlessProxyManager.removeNode(link);
            pings.remove(link);
            rebuild();
        });
        showDialog(builder.create());
    }

    private void showAddDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.VlessAddNode));

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintText(getString(R.string.VlessLinkHint));
        editText.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        editText.setSingleLine(false);
        editText.setMinLines(2);
        editText.setMaxLines(6);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setFocusable(true);
        editText.setTransformHintToHeader(true);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
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
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.VlessImportSubscription));

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintText(getString(R.string.VlessSubscriptionUrl));
        editText.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        editText.setFocusable(true);
        editText.setTransformHintToHeader(true);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
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
        Utilities.globalQueue.postRunnable(() -> {
            for (int i = 0; i < nodes.size(); i++) {
                final String link = nodes.get(i);
                long ping = pingNode(link);
                AndroidUtilities.runOnUIThread(() -> {
                    pings.put(link, ping);
                    if (listAdapter != null && nodeStartRow >= 0) {
                        listAdapter.notifyItemChanged(nodeStartRow + nodes.indexOf(link));
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

    /** Simple TCP connect latency to the node's server:port. -1 when unreachable. */
    private long pingNode(String link) {
        String serverPort = VlessProxyManager.nodeServerPort(link);
        int colon = serverPort.lastIndexOf(':');
        if (colon < 0) {
            return -1;
        }
        String host = serverPort.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(serverPort.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            return System.currentTimeMillis() - start;
        } catch (Throwable e) {
            return -1;
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

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
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
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (isNodeRow(position)) {
            deleteNode(nodes.get(position - nodeStartRow));
            return true;
        }
        return false;
    }

    @Override
    protected String getKey() {
        return null;
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.VlessSettings);
    }

    @Override
    protected void updateRows() {
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

    @Override
    protected boolean hasWhiteActionBar() {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            rebuild();
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_INFO_PRIVACY: {
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
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == enableRow) {
                        cell.setTextAndCheck(getString(R.string.VlessEnable), VlessProxyManager.isEnabled(), nodesHeaderRow != -1);
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == nodesHeaderRow) {
                        headerCell.setText(getString(R.string.VlessNodesHeader));
                    }
                    break;
                }
                case TYPE_DETAIL_SETTINGS: {
                    TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                    String link = nodes.get(position - nodeStartRow);
                    boolean active = VlessProxyManager.isActiveNode(link);
                    String name = VlessProxyManager.nodeName(link);
                    String title = (active ? "✓ " : "") + (TextUtils.isEmpty(name) ? getString(R.string.VlessSettings) : name);
                    cell.setTextAndValue(title, nodeStatusText(link), position != nodeStartRow + nodes.size() - 1);
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
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

        @Override
        public int getItemViewType(int position) {
            if (position == descriptionRow || position == infoRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == enableRow) {
                return TYPE_CHECK;
            } else if (position == nodesHeaderRow) {
                return TYPE_HEADER;
            } else if (isNodeRow(position)) {
                return TYPE_DETAIL_SETTINGS;
            }
            return TYPE_SETTINGS;
        }
    }
}
