/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * NagramXF: proxy page ported to the Nekogram X 9.3.3 blueprint.
 *
 * Layout & interactions follow Nekogram X 9.3.3 ProxyListActivity:
 *   - "Use proxy" master switch on top;
 *   - a single unified server list under "Connections": official native proxies
 *     (SharedConfig.proxyList, SOCKS5/MTProto) and built-in sing-box nodes
 *     (vless/vmess/trojan/ss kept in VlessProxyManager) are rendered in the same
 *     row style "[ Type ] name-or-address" with a status subtitle;
 *   - tapping a row selects and enables it (native → native proxy path,
 *     built-in node → selectNode/engine);
 *   - the edit icon on the right dispatches to the protocol-specific editor;
 *   - long-press shows the Nekogram X-style action sheet (edit/share/share QR/
 *     copy link/delete);
 *   - "＋" add menu (clipboard / QR / SOCKS5 / MTProto / VMess / Trojan / SS /
 *     VLESS / subscription), "⋮" tools menu (retest / delete all / delete
 *     unavailable).
 *
 * The sing-box engine, the 127.0.0.1:LOCAL_PORT shadow entry and its filtering,
 * the VLESS-aware master switch and the cold-start recovery are untouched.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.QRCodeBottomSheet;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tw.nekomimi.nekogram.helpers.ProxyTypes;
import tw.nekomimi.nekogram.helpers.VlessProxyManager;
import tw.nekomimi.nekogram.helpers.WebSocketHelper;
import tw.nekomimi.nekogram.ui.BottomBuilder;
import tw.nekomimi.nekogram.utils.AlertUtil;
import tw.nekomimi.nekogram.utils.ProxyUtil;
import tw.nekomimi.nekogram.utils.VlessImportHelper;

public class ProxyListActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private ListAdapter listAdapter;
    private RecyclerListView listView;
    @SuppressWarnings("FieldCanBeLocal")
    private LinearLayoutManager layoutManager;

    private int currentConnectionState;

    private boolean useProxySettings;
    private boolean useProxyForCalls;

    private int rowCount;
    private int useProxyRow;
    private int useProxyShadowRow;
    private int connectionsHeaderRow;
    private int proxyStartRow;
    private int proxyEndRow;
    private int proxyShadowRow;
    private int callsRow;
    private int callsDetailRow;

    // Unified display list: every visible row is one ProxyRow wrapper over either
    // a native SharedConfig.ProxyInfo or a built-in node link.
    private final List<ProxyRow> proxyRows = new ArrayList<>();
    private final List<SharedConfig.ProxyInfo> proxyList = new ArrayList<>();

    // Node latency checks that already ran for this page instance.
    private final Set<String> pingedLinks = new HashSet<>();
    private final ExecutorService nodePingExecutor = Executors.newCachedThreadPool();

    // na: action bar menu
    private ActionBarMenuItem otherItem;

    /**
     * One displayed server row. Mirrors the information Nekogram X 9.3.3 exposes
     * through its ProxyInfo rows (type / remarks / server / ping state) but wraps
     * either a native ProxyInfo or a built-in node link kept by the sing-box
     * manager, so native proxies and nodes share a single row model & renderer.
     */
    private class ProxyRow {
        final SharedConfig.ProxyInfo nativeInfo;
        final String nodeLink;
        final String title;
        final String address;
        final boolean isWsRow;

        ProxyRow(SharedConfig.ProxyInfo nativeInfo, String nodeLink, String title, String address, boolean isWsRow) {
            this.nativeInfo = nativeInfo;
            this.nodeLink = nodeLink;
            this.title = title;
            this.address = address;
            this.isWsRow = isWsRow;
        }

        boolean isNode() {
            return nodeLink != null;
        }

        boolean isActiveProxy() {
            if (isNode()) {
                return VlessProxyManager.isActiveNode(nodeLink);
            }
            return useProxySettings && SharedConfig.currentProxy == nativeInfo;
        }

        boolean isChecking() {
            if (isNode()) {
                return false;
            }
            return nativeInfo.checking;
        }

        boolean isAvailable() {
            if (isNode()) {
                return VlessProxyManager.getPing(nodeLink) >= 0;
            }
            return nativeInfo.available;
        }

        /** Ping to show in the row subtitle; 0 means "no measured ping yet". */
        long getPingMs() {
            if (isNode()) {
                long ping = VlessProxyManager.getPing(nodeLink);
                return ping > 0 ? ping : 0;
            }
            return nativeInfo.ping;
        }

        /** Shareable link for this row. */
        String getLink() {
            if (isNode()) {
                return nodeLink;
            }
            return nativeInfo.getLink();
        }

        void openEditor() {
            if (isNode()) {
                presentNodeEditor(nodeLink);
            } else if (isWsRow) {
                presentFragment(new tw.nekomimi.nekogram.settings.WsSettingsActivity(nativeInfo));
            } else {
                presentFragment(new ProxySettingsActivity(nativeInfo));
            }
        }
    }

    public class TextDetailProxyCell extends FrameLayout {

        private TextView textView;
        private TextView valueTextView;
        private ImageView checkImageView;
        private ProxyRow currentRow;
        private Drawable checkDrawable;
        private int color;

        public TextDetailProxyCell(Context context) {
            super(context);

            float textLeftMargin = LocaleController.isRTL ? 104 : 21;
            float textRightMargin = LocaleController.isRTL ? 21 : 104;
            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, textLeftMargin, 10, textRightMargin, 0));

            valueTextView = new TextView(context);
            valueTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            valueTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            valueTextView.setLines(1);
            valueTextView.setMaxLines(1);
            valueTextView.setSingleLine(true);
            valueTextView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
            valueTextView.setEllipsize(TextUtils.TruncateAt.END);
            valueTextView.setPadding(0, 0, 0, 0);
            addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, textLeftMargin, 35, textRightMargin, 0));

            checkImageView = new ImageView(context);
            checkImageView.setImageResource(R.drawable.msg_info);
            checkImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), PorterDuff.Mode.MULTIPLY));
            checkImageView.setScaleType(ImageView.ScaleType.CENTER);
            checkImageView.setContentDescription(getString(R.string.Edit));
            addView(checkImageView, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 8, 8, 8, 0));
            checkImageView.setOnClickListener(v -> {
                ProxyRow row = currentRow;
                if (row != null) {
                    row.openEditor();
                }
            });

            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + 1, MeasureSpec.EXACTLY));
        }

        public void setProxyRow(ProxyRow row) {
            currentRow = row;
            textView.setText(row.title);
        }

        public void updateStatus() {
            ProxyRow row = currentRow;
            if (row == null) {
                return;
            }
            int colorKey;
            if (row.isActiveProxy()) {
                if (currentConnectionState == ConnectionsManager.ConnectionStateConnected || currentConnectionState == ConnectionsManager.ConnectionStateUpdating) {
                    colorKey = Theme.key_windowBackgroundWhiteBlueText6;
                    long ping = row.getPingMs();
                    if (ping != 0) {
                        valueTextView.setText(getString(R.string.Connected) + ", " + LocaleController.formatString("Ping", R.string.Ping, ping));
                    } else {
                        valueTextView.setText(getString(R.string.Connected));
                    }
                    if (!row.isNode() && !row.nativeInfo.checking && !row.nativeInfo.available) {
                        row.nativeInfo.availableCheckTime = 0;
                    }
                } else {
                    colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                    valueTextView.setText(getString(R.string.Connecting));
                }
            } else {
                if (row.isChecking()) {
                    valueTextView.setText(getString(R.string.Checking));
                    colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                } else if (row.isAvailable()) {
                    long ping = row.getPingMs();
                    if (ping != 0) {
                        valueTextView.setText(getString(R.string.Available) + ", " + LocaleController.formatString("Ping", R.string.Ping, ping));
                    } else {
                        valueTextView.setText(getString(R.string.Available));
                    }
                    colorKey = Theme.key_windowBackgroundWhiteGreenText;
                } else {
                    valueTextView.setText(getString(R.string.Unavailable));
                    colorKey = Theme.key_text_RedRegular;
                }
            }
            color = Theme.getColor(colorKey);
            valueTextView.setTag(colorKey);
            valueTextView.setTextColor(color);
            if (checkDrawable != null) {
                checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
            }
        }

        public void setChecked(boolean checked) {
            if (checked) {
                if (checkDrawable == null) {
                    checkDrawable = getResources().getDrawable(R.drawable.proxy_check).mutate();
                }
                if (checkDrawable != null) {
                    checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                }
                if (LocaleController.isRTL) {
                    valueTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, checkDrawable, null);
                } else {
                    valueTextView.setCompoundDrawablesWithIntrinsicBounds(checkDrawable, null, null, null);
                }
            } else {
                valueTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            }
        }

        public void setValue(CharSequence value) {
            valueTextView.setText(value);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateStatus();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    private void showProxyQrCode(Context context, String link) {
        if (context == null || TextUtils.isEmpty(link)) {
            return;
        }
        QRCodeBottomSheet alert = new QRCodeBottomSheet(context, LocaleController.getString(R.string.ShareQrCode), link, LocaleController.getString(R.string.QRCodeLinkHelpProxy), true);
        Bitmap icon = SvgHelper.getBitmap(AndroidUtilities.readRes(R.raw.qr_dog), AndroidUtilities.dp(60), AndroidUtilities.dp(60), false);
        alert.setCenterImage(icon);
        showDialog(alert);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();

        SharedConfig.loadProxyList();
        currentConnectionState = ConnectionsManager.getInstance(currentAccount).getConnectionState();

        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyChangedByRotation);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);

        final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        boolean proxyEnabledPref = preferences.getBoolean("proxy_enabled", false);
        // The built-in node engine keeps the master switch on even when no native
        // proxy rows exist (only the hidden 127.0.0.1 shadow entry is stored).
        useProxySettings = VlessProxyManager.isEnabled() || proxyEnabledPref && !SharedConfig.proxyList.isEmpty();
        useProxyForCalls = proxyEnabledPref && preferences.getBoolean("proxy_enabled_calls", false);

        updateRows(true);

        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        nodePingExecutor.shutdownNow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyChangedByRotation);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
    }

    private final static int menu_add = 1010;
    private final static int menu_add_import_from_clipboard = 1003;
    private final static int menu_add_scan_qr = 1011;
    private final static int menu_add_input_socks = 1012;
    private final static int menu_add_input_telegram = 1002;
    private final static int menu_add_input_vmess = 1013;
    private final static int menu_add_input_trojan = 1014;
    private final static int menu_add_input_ss = 1015;
    private final static int menu_add_input_vless = 1016;
    private final static int menu_add_subscription = 1017;
    private final static int menu_other = 1001;
    private final static int menu_retest_ping = 1004;
    private final static int menu_delete_all = 1005;
    private final static int menu_delete_unavailable = 1006;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ProxySettings));
        if (parentLayout != null && parentLayout.isLayersLayout()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        // "＋" add menu, ordered as in Nekogram X 9.3.3 for the protocols this
        // backend supports, then our VLESS node type and the subscription-import
        // entry — a single add surface.
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem addItem = menu.addItem(menu_add, R.drawable.add);
        addItem.setContentDescription(LocaleController.getString("AddProxy", R.string.AddProxy));
        addItem.addSubItem(menu_add_import_from_clipboard, LocaleController.getString("ImportProxyFromClipboard", R.string.ImportProxyFromClipboard)).setOnClickListener((v) -> importFromClipboardMenu());
        addItem.addSubItem(menu_add_scan_qr, LocaleController.getString("ScanQRCode", R.string.ScanQRCode)).setOnClickListener((v) -> scanQrCodeMenu());
        addItem.addSubItem(menu_add_input_socks, LocaleController.getString("AddProxySocks5", R.string.AddProxySocks5)).setOnClickListener((v) -> presentFragment(new ProxySettingsActivity()));
        addItem.addSubItem(menu_add_input_telegram, LocaleController.getString("AddProxyTelegram", R.string.AddProxyTelegram)).setOnClickListener((v) -> presentFragment(new ProxySettingsActivity()));
        addItem.addSubItem(menu_add_input_vmess, LocaleController.getString("AddProxyVmess", R.string.AddProxyVmess)).setOnClickListener((v) -> presentFragment(new tw.nekomimi.nekogram.settings.VmessNodeEditActivity()));
        addItem.addSubItem(menu_add_input_trojan, LocaleController.getString("AddProxyTrojan", R.string.AddProxyTrojan)).setOnClickListener((v) -> presentFragment(new tw.nekomimi.nekogram.settings.TrojanNodeEditActivity()));
        addItem.addSubItem(menu_add_input_ss, LocaleController.getString("AddProxySS", R.string.AddProxySS)).setOnClickListener((v) -> presentFragment(new tw.nekomimi.nekogram.settings.ShadowsocksNodeEditActivity()));
        addItem.addSubItem(menu_add_input_vless, LocaleController.getString(R.string.VlessAddNode)).setOnClickListener((v) -> presentFragment(new tw.nekomimi.nekogram.settings.VlessNodeEditActivity()));
        addItem.addSubItem(menu_add_subscription, LocaleController.getString(R.string.VlessImportSubscription)).setOnClickListener((v) ->
                VlessImportHelper.showSubscriptionDialog(ProxyListActivity.this, () -> updateRows(true)));

        // "⋮" tools menu (Nekogram X 9.3.3 subset supported by this backend).
        otherItem = menu.addItem(menu_other, R.drawable.ic_ab_other);
        otherItem.setContentDescription(LocaleController.getString("AccDescrMoreOptions", R.string.AccDescrMoreOptions));
        otherItem.addSubItem(menu_retest_ping, LocaleController.getString("RetestPing", R.string.RetestPing)).setOnClickListener((v) -> retestPing());
        otherItem.addSubItem(menu_delete_all, LocaleController.getString("DeleteAllServer", R.string.DeleteAllServer)).setOnClickListener((v) -> confirmDeleteAll());
        otherItem.addSubItem(menu_delete_unavailable, LocaleController.getString("DeleteUnavailableServer", R.string.DeleteUnavailableServer)).setOnClickListener((v) -> confirmDeleteUnavailable());

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        ((DefaultItemAnimator) listView.getItemAnimator()).setDelayAnimations(false);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position == useProxyRow) {
                toggleUseProxy();
            } else if (position == callsRow) {
                useProxyForCalls = !useProxyForCalls;
                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(useProxyForCalls);
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putBoolean("proxy_enabled_calls", useProxyForCalls);
                editor.commit();
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                ProxyRow row = proxyRows.get(position - proxyStartRow);
                if (row != null) {
                    if (row.isNode()) {
                        selectNode(row);
                    } else {
                        selectNativeProxy(row);
                    }
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= proxyStartRow && position < proxyEndRow) {
                ProxyRow row = proxyRows.get(position - proxyStartRow);
                if (row != null) {
                    showProxyActions(row);
                    return true;
                }
            }
            return false;
        });

        return fragmentView;
    }

    /** "Use proxy" master switch, VLESS-aware: it controls the built-in node
     * engine when one is configured, otherwise the native proxy path. */
    private void toggleUseProxy() {
        if (VlessProxyManager.isEnabled()) {
            // Turning the built-in engine off.
            VlessProxyManager.setEnabled(false);
            useProxySettings = false;
            useProxyForCalls = false;
            notifyProxySettingsChanged();
            updateRows(true);
            return;
        }
        if (!useProxySettings && VlessProxyManager.hasConfig()) {
            // Turning on: boot the engine on the last selected node.
            VlessProxyManager.selectNode(VlessProxyManager.getVlessLink());
            useProxySettings = true;
            useProxyForCalls = false;
            notifyProxySettingsChanged();
            updateRows(true);
            return;
        }
        if (SharedConfig.currentProxy == null) {
            SharedConfig.ProxyInfo fallback = null;
            for (ProxyRow row : proxyRows) {
                if (!row.isNode() && SharedConfig.proxyList.contains(row.nativeInfo)) {
                    fallback = row.nativeInfo;
                    break;
                }
            }
            if (fallback != null) {
                SharedConfig.currentProxy = fallback;
            } else {
                showAddProxySheet();
                return;
            }
            if (!useProxySettings) {
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putString("proxy_ip", SharedConfig.currentProxy.address);
                editor.putString("proxy_pass", SharedConfig.currentProxy.password);
                editor.putString("proxy_user", SharedConfig.currentProxy.username);
                editor.putInt("proxy_port", SharedConfig.currentProxy.port);
                editor.putString("proxy_secret", SharedConfig.currentProxy.secret);
                editor.commit();
            }
        }
        useProxySettings = !useProxySettings;
        if (!useProxySettings) {
            useProxyForCalls = false;
            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putBoolean("proxy_enabled", false);
            editor.putBoolean("proxy_enabled_calls", false);
            editor.commit();
            ConnectionsManager.setProxySettings(false, null, 0, null, null, null);
            notifyProxySettingsChanged();
            updateRows(true);
            return;
        }
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", true);
        editor.commit();
        if (SharedConfig.currentProxy != null) {
            SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
            ConnectionsManager.setProxySettings(true, info.address, info.port, info.username, info.password, info.secret);
        }
        notifyProxySettingsChanged();
        updateRows(true);
    }

    /** Row tap: native proxy row. Stops the engine if needed and enables the row. */
    private void selectNativeProxy(ProxyRow row) {
        SharedConfig.ProxyInfo info = row.nativeInfo;
        if (info == null) {
            return;
        }
        // A native proxy takes over: stop the built-in engine if it is running.
        if (VlessProxyManager.isEnabled()) {
            VlessProxyManager.setEnabled(false);
        }
        useProxySettings = true;
        SharedConfig.currentProxy = info;
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putString("proxy_ip", info.address);
        editor.putString("proxy_pass", info.password);
        editor.putString("proxy_user", info.username);
        editor.putInt("proxy_port", info.port);
        editor.putString("proxy_secret", info.secret);
        editor.putBoolean("proxy_enabled", true);
        if (!info.secret.isEmpty()) {
            useProxyForCalls = false;
            editor.putBoolean("proxy_enabled_calls", false);
        }
        editor.commit();
        ConnectionsManager.setProxySettings(true, info.address, info.port, info.username, info.password, info.secret);
        notifyProxySettingsChanged();
        updateRows(true);
    }

    /** Row tap: built-in node row. Selects the node and starts/hot-swaps the engine. */
    private void selectNode(ProxyRow row) {
        String link = row.nodeLink;
        if (link == null) {
            return;
        }
        try {
            VlessProxyManager.selectNode(link);
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        useProxySettings = true;
        notifyProxySettingsChanged();
        updateRows(true);
    }

    private void notifyProxySettingsChanged() {
        NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
    }

    /** "＋" add sheet shown when the user enables the master switch with no rows. */
    private void showAddProxySheet() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        BottomBuilder builder = new BottomBuilder(activity);
        builder.addItems(new String[]{
                LocaleController.getString("AddProxySocks5", R.string.AddProxySocks5),
                LocaleController.getString("AddProxyTelegram", R.string.AddProxyTelegram),
                LocaleController.getString("AddProxyVmess", R.string.AddProxyVmess),
                LocaleController.getString("AddProxyTrojan", R.string.AddProxyTrojan),
                LocaleController.getString("AddProxySS", R.string.AddProxySS),
                LocaleController.getString(R.string.VlessAddNode),
                LocaleController.getString("ImportProxyFromClipboard", R.string.ImportProxyFromClipboard),
                LocaleController.getString("ScanQRCode", R.string.ScanQRCode)
        }, null, (i, t, c) -> {
            dispatchAddAction(i);
            return kotlin.Unit.INSTANCE;
        });
        builder.show();
    }

    private void dispatchAddAction(int index) {
        switch (index) {
            case 0:
            case 1:
                presentFragment(new ProxySettingsActivity());
                break;
            case 2:
                presentFragment(new tw.nekomimi.nekogram.settings.VmessNodeEditActivity());
                break;
            case 3:
                presentFragment(new tw.nekomimi.nekogram.settings.TrojanNodeEditActivity());
                break;
            case 4:
                presentFragment(new tw.nekomimi.nekogram.settings.ShadowsocksNodeEditActivity());
                break;
            case 5:
                presentFragment(new tw.nekomimi.nekogram.settings.VlessNodeEditActivity());
                break;
            case 6:
                importFromClipboardMenu();
                break;
            case 7:
                scanQrCodeMenu();
                break;
            default:
                break;
        }
    }

    /** Long-press row action sheet (Nekogram X 9.3.3 style). */
    private void showProxyActions(ProxyRow row) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        String link;
        try {
            link = row.getLink();
        } catch (Throwable e) {
            FileLog.e(e);
            link = null;
        }
        final String shareText = link;
        BottomBuilder builder = new BottomBuilder(activity);
        builder.addItems(new String[]{
                LocaleController.getString("EditProxy", R.string.EditProxy),
                LocaleController.getString("ShareProxy", R.string.ShareProxy),
                LocaleController.getString("ShareQRCode", R.string.ShareQRCode),
                LocaleController.getString("CopyLink", R.string.CopyLink),
                LocaleController.getString("ProxyDelete", R.string.ProxyDelete),
                LocaleController.getString("Cancel", R.string.Cancel)
        }, new int[]{
                R.drawable.group_edit,
                R.drawable.msg_share,
                R.drawable.msg_qrcode,
                R.drawable.msg_copy,
                R.drawable.msg_delete,
                R.drawable.msg_cancel
        }, (i, text, cell) -> {
            switch (i) {
                case 0:
                    row.openEditor();
                    break;
                case 1:
                    shareProxyLink(shareText);
                    break;
                case 2:
                    showProxyQrCode(activity, shareText);
                    break;
                case 3:
                    if (shareText != null) {
                        AndroidUtilities.addToClipboard(shareText);
                        AlertUtil.showToast(LocaleController.getString(R.string.LinkCopied));
                    }
                    break;
                case 4:
                    confirmDeleteProxy(row);
                    break;
                default:
                    break;
            }
            return kotlin.Unit.INSTANCE;
        });
        builder.show();
    }

    private void shareProxyLink(String link) {
        Activity activity = getParentActivity();
        if (activity == null || TextUtils.isEmpty(link)) {
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, link);
        Intent chooserIntent = Intent.createChooser(shareIntent, getString(R.string.ShareLink));
        chooserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(chooserIntent);
    }

    private void confirmDeleteProxy(ProxyRow row) {
        AlertUtil.showConfirm(getParentActivity(),
                LocaleController.getString("DeleteProxy", R.string.DeleteProxy),
                R.drawable.msg_delete, LocaleController.getString("Delete", R.string.Delete),
                true, () -> {
                    if (row.isNode()) {
                        VlessProxyManager.removeNode(row.nodeLink);
                    } else {
                        SharedConfig.deleteProxy(row.nativeInfo);
                    }
                    if (!VlessProxyManager.isEnabled() && SharedConfig.currentProxy == null) {
                        useProxyForCalls = false;
                        useProxySettings = false;
                    }
                    notifyProxySettingsChanged();
                    updateRows(true);
                });
    }

    private void confirmDeleteAll() {
        AlertUtil.showConfirm(getParentActivity(),
                LocaleController.getString("DeleteAllServer", R.string.DeleteAllServer),
                R.drawable.msg_delete, LocaleController.getString("Delete", R.string.Delete),
                true, () -> {
                    // Deleting every visible server must not leave the engine
                    // running against a deleted shadow entry.
                    if (VlessProxyManager.isEnabled()) {
                        VlessProxyManager.setEnabled(false);
                    }
                    for (String link : new ArrayList<>(VlessProxyManager.getNodes())) {
                        VlessProxyManager.removeNode(link);
                    }
                    // deleteAllProxy() also clears the internal 127.0.0.1:6357
                    // shadow row; it is re-created by applyLocalProxy() the next
                    // time the built-in proxy is enabled.
                    SharedConfig.deleteAllProxy();
                    if (SharedConfig.currentProxy == null) {
                        useProxySettings = false;
                        useProxyForCalls = false;
                    }
                    notifyProxySettingsChanged();
                    updateRows(true);
                });
    }

    private void confirmDeleteUnavailable() {
        AlertUtil.showConfirm(getParentActivity(),
                LocaleController.getString("DeleteUnavailableServer", R.string.DeleteUnavailableServer),
                R.drawable.msg_delete, LocaleController.getString("Delete", R.string.Delete),
                true, () -> {
                    for (SharedConfig.ProxyInfo info : SharedConfig.getProxyList()) {
                        if (info.checking) {
                            continue;
                        }
                        // Never remove the internal 127.0.0.1 shadow entry.
                        if ("127.0.0.1".equals(info.address) && info.port == VlessProxyManager.LOCAL_PORT) {
                            continue;
                        }
                        if (!info.available) {
                            SharedConfig.deleteProxy(info);
                        }
                    }
                    if (SharedConfig.currentProxy == null) {
                        useProxyForCalls = false;
                        useProxySettings = false;
                    }
                    notifyProxySettingsChanged();
                    updateRows(true);
                });
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        return super.onBackPressed(invoked);
    }

    private void updateRows(boolean notify) {
        if (notify) {
            proxyList.clear();
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                if ("127.0.0.1".equals(info.address) && info.port == VlessProxyManager.LOCAL_PORT) {
                    continue;
                }
                proxyList.add(info);
            }

            proxyRows.clear();
            for (SharedConfig.ProxyInfo info : proxyList) {
                proxyRows.add(makeNativeRow(info));
            }
            ArrayList<String> nodeLinks = VlessProxyManager.getNodes();
            String activeLink = VlessProxyManager.getVlessLink();
            if (!TextUtils.isEmpty(activeLink) && nodeLinks.remove(activeLink)) {
                nodeLinks.add(0, activeLink);
            }
            for (String link : nodeLinks) {
                proxyRows.add(makeNodeRow(link));
            }

            // Sort like Nekogram X 9.3.3: active proxy first, then available
            // servers by ping (unmeasured/unavailable at the bottom).
            proxyRows.sort(new Comparator<ProxyRow>() {
                @Override
                public int compare(ProxyRow o1, ProxyRow o2) {
                    boolean a1 = o1.isActiveProxy();
                    boolean a2 = o2.isActiveProxy();
                    if (a1 != a2) {
                        return a1 ? -1 : 1;
                    }
                    boolean av1 = o1.isAvailable();
                    boolean av2 = o2.isAvailable();
                    if (av1 != av2) {
                        return av1 ? -1 : 1;
                    }
                    long p1 = o1.getPingMs();
                    long p2 = o2.getPingMs();
                    if (p1 != p2) {
                        return Long.compare(p1, p2);
                    }
                    return 0;
                }
            });
        }

        rowCount = 0;
        useProxyRow = rowCount++;
        boolean hasServers = !proxyRows.isEmpty();
        if (hasServers) {
            useProxyShadowRow = rowCount++;
            connectionsHeaderRow = rowCount++;
            proxyStartRow = rowCount;
            rowCount += proxyRows.size();
            proxyEndRow = rowCount;
        } else {
            useProxyShadowRow = -1;
            connectionsHeaderRow = -1;
            proxyStartRow = -1;
            proxyEndRow = -1;
        }
        proxyShadowRow = rowCount++;
        if (SharedConfig.currentProxy == null || SharedConfig.currentProxy.secret.isEmpty()) {
            boolean change = callsRow == -1;
            callsRow = rowCount++;
            callsDetailRow = rowCount++;
            if (!notify && change && listAdapter != null) {
                listAdapter.notifyItemChanged(proxyShadowRow);
                listAdapter.notifyItemRangeInserted(proxyShadowRow + 1, 2);
            }
        } else {
            boolean change = callsRow != -1;
            callsRow = -1;
            callsDetailRow = -1;
            if (!notify && change && listAdapter != null) {
                listAdapter.notifyItemChanged(proxyShadowRow);
                listAdapter.notifyItemRangeRemoved(proxyShadowRow + 1, 2);
            }
        }

        checkProxyList();
        if (notify && listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        if (notify && !proxyRows.isEmpty()) {
            pingMissingNodes();
        }
    }

    private ProxyRow makeNativeRow(SharedConfig.ProxyInfo info) {
        boolean ws = WebSocketHelper.proxyServer.equals(info.address);
        String type = (info.secret == null || info.secret.isEmpty()) ? "Socks5" : "MTProto";
        String address = info.address + ":" + info.port;
        String title = ws
                ? getString(R.string.PublicProxy)
                : "[ " + type + " ] " + address;
        return new ProxyRow(info, null, title, address, ws);
    }

    private ProxyRow makeNodeRow(String link) {
        String type;
        String kind = ProxyTypes.kind(link);
        switch (kind) {
            case "vless":
                type = "Vless";
                break;
            case "vmess":
                type = "Vmess";
                break;
            case "trojan":
                type = "Trojan";
                break;
            case "ss":
                type = "Shadowsocks";
                break;
            default:
                type = TextUtils.isEmpty(kind) ? "Proxy" : kind;
                break;
        }
        String remarks = "";
        String address = link;
        try {
            remarks = ProxyTypes.nodeName(link);
        } catch (Throwable ignore) {
        }
        try {
            address = ProxyTypes.nodeServerPort(link);
        } catch (Throwable ignore) {
        }
        String title = "[ " + type + " ] " + (TextUtils.isEmpty(remarks) ? address : remarks);
        return new ProxyRow(null, link, title, address, false);
    }

    /**
     * Opens the protocol-specific editor for a built-in node link, falling back
     * to the generic link editor for protocols without a dedicated form (vless).
     */
    private void presentNodeEditor(String link) {
        String kind = ProxyTypes.typeTag(link);
        if ("vmess".equals(kind)) {
            presentFragment(new tw.nekomimi.nekogram.settings.VmessNodeEditActivity(link));
        } else if ("trojan".equals(kind)) {
            presentFragment(new tw.nekomimi.nekogram.settings.TrojanNodeEditActivity(link));
        } else if ("ss".equals(kind)) {
            presentFragment(new tw.nekomimi.nekogram.settings.ShadowsocksNodeEditActivity(link));
        } else {
            presentFragment(new tw.nekomimi.nekogram.settings.VlessNodeEditActivity(link));
        }
    }

    /** "＋" → import from clipboard. Native tg:// links keep their legacy path. */
    private void importFromClipboardMenu() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        String text = readClipboardText(activity);
        if (text != null && containsTgProxyLink(text)) {
            ProxyUtil.importFromClipboard(activity);
            updateRows(true);
            return;
        }
        VlessImportHelper.importFromClipboard(ProxyListActivity.this, () -> updateRows(true));
    }

    /** "＋" → scan QR. Node links are imported directly; other links open/copy. */
    private void scanQrCodeMenu() {
        CameraScanActivity.showAsSheet(ProxyListActivity.this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                handleScannedText(text);
            }
        });
    }

    private void handleScannedText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String trimmed = text.trim();
        try {
            if (!ProxyUtil.parseProxies(trimmed).isEmpty()) {
                VlessImportHelper.importText(ProxyListActivity.this, trimmed, () -> updateRows(true));
                return;
            }
        } catch (Throwable ignore) {
        }
        if (Browser.isInternalUrl(trimmed, new boolean[]{false})) {
            Browser.openUrl(getParentActivity(), trimmed);
            return;
        }
        AlertUtil.showCopyAlert(getParentActivity(), trimmed);
    }

    private static String readClipboardText(Context context) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
            if (cs != null) {
                return cs.toString();
            }
        }
        return null;
    }

    private static boolean containsTgProxyLink(String text) {
        return text.contains("tg://proxy") || text.contains("tg://socks") ||
                text.contains("https://t.me/proxy") || text.contains("https://t.me/socks");
    }

    /** Re-tests every visible row (native proxies through the native checker,
     * built-in nodes through their TCP ping) and refreshes the rows. */
    private void retestPing() {
        checkProxyList(true);
        pingNodes(false);
        updateRows(true);
    }

    private void pingMissingNodes() {
        pingNodes(true);
    }

    /** Pings every built-in node. When [onlyMissing] is true a node measured for
     * this page instance is skipped (used on list refreshes). */
    private void pingNodes(boolean onlyMissing) {
        ArrayList<String> links = new ArrayList<>();
        for (ProxyRow row : proxyRows) {
            if (row.isNode() && !links.contains(row.nodeLink)) {
                links.add(row.nodeLink);
            }
        }
        for (final String link : links) {
            if (onlyMissing && !pingedLinks.add(link)) {
                continue;
            }
            nodePingExecutor.execute(() -> {
                long ping = VlessProxyManager.pingNode(link);
                AndroidUtilities.runOnUIThread(() -> {
                    if (isFinished) {
                        return;
                    }
                    VlessProxyManager.setPing(link, ping);
                    if (listAdapter == null || proxyStartRow < 0) {
                        return;
                    }
                    int idx = indexOfNodeLink(link);
                    if (idx >= 0) {
                        listAdapter.notifyItemChanged(proxyStartRow + idx);
                    }
                });
            });
        }
    }

    private int indexOfNodeLink(String link) {
        for (int i = 0, count = proxyRows.size(); i < count; i++) {
            ProxyRow row = proxyRows.get(i);
            if (row.isNode() && link.equals(row.nodeLink)) {
                return i;
            }
        }
        return -1;
    }

    private void checkProxyList() {
        checkProxyList(false);
    }

    private void checkProxyList(boolean force) {
        for (int a = 0, count = proxyList.size(); a < count; a++) {
            final SharedConfig.ProxyInfo proxyInfo = proxyList.get(a);
            if (proxyInfo.checking || SystemClock.elapsedRealtime() - proxyInfo.availableCheckTime < (proxyInfo.available ? 20 : 5) * 1000 && !force) {
                continue;
            }
            proxyInfo.checking = true;
            proxyInfo.proxyCheckPingId = ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.address, proxyInfo.port, proxyInfo.username, proxyInfo.password, proxyInfo.secret, time -> AndroidUtilities.runOnUIThread(() -> {
                proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                proxyInfo.checking = false;
                if (time == -1) {
                    proxyInfo.available = false;
                    proxyInfo.ping = 0;
                } else {
                    proxyInfo.ping = time;
                    proxyInfo.available = true;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
            }));
        }
    }

    @Override
    protected void onDialogDismiss(Dialog dialog) {
        DownloadController.getInstance(currentAccount).checkAutodownloadSettings();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            updateRows(true);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyChangedByRotation || id == NotificationCenter.proxySettingsChanged) {
            updateRows(true);
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (currentConnectionState != state) {
                currentConnectionState = state;
                refreshVisibleStatuses();
                if (currentConnectionState == ConnectionsManager.ConnectionStateConnected) {
                    updateRows(true);
                }
            }
        } else if (id == NotificationCenter.proxyCheckDone) {
            if (listView != null && args.length > 0 && args[0] instanceof SharedConfig.ProxyInfo) {
                refreshVisibleStatuses();
            }
        }
    }

    private void refreshVisibleStatuses() {
        if (listView == null) {
            return;
        }
        for (int i = 0, count = listView.getChildCount(); i < count; i++) {
            View child = listView.getChildAt(i);
            if (child == null) {
                continue;
            }
            RecyclerView.ViewHolder holder = listView.getChildViewHolder(child);
            if (holder != null && holder.itemView instanceof TextDetailProxyCell) {
                ((TextDetailProxyCell) holder.itemView).updateStatus();
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final static int VIEW_TYPE_SHADOW = 0;
        private final static int VIEW_TYPE_HEADER = 1;
        private final static int VIEW_TYPE_TEXT_CHECK = 2;
        private final static int VIEW_TYPE_INFO = 3;
        private final static int VIEW_TYPE_PROXY_DETAIL = 4;

        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
            setHasStableIds(true);
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SHADOW:
                    break;
                case VIEW_TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == connectionsHeaderRow) {
                        headerCell.setText(getString(R.string.ProxyConnections));
                    }
                    break;
                }
                case VIEW_TYPE_TEXT_CHECK: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == useProxyRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxySettings), useProxySettings, false);
                    } else if (position == callsRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyForCalls), useProxyForCalls, false);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == callsDetailRow) {
                        cell.setText(getString(R.string.UseProxyForCallsInfo));
                    }
                    break;
                }
                case VIEW_TYPE_PROXY_DETAIL: {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    ProxyRow row = proxyRows.get(position - proxyStartRow);
                    cell.setProxyRow(row);
                    cell.updateStatus();
                    cell.setChecked(row.isActiveProxy());
                    break;
                }
            }
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            if (viewType == VIEW_TYPE_TEXT_CHECK) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                int position = holder.getAdapterPosition();
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == callsRow) {
                    checkCell.setChecked(useProxyForCalls);
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == useProxyRow || position == callsRow || position >= proxyStartRow && position < proxyEndRow;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_TEXT_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_PROXY_DETAIL:
                default:
                    view = new TextDetailProxyCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public long getItemId(int position) {
            if (position == useProxyShadowRow) {
                return -1;
            } else if (position == proxyShadowRow) {
                return -2;
            } else if (position == useProxyRow) {
                return -4;
            } else if (position == callsRow) {
                return -5;
            } else if (position == connectionsHeaderRow) {
                return -6;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                ProxyRow row = proxyRows.get(position - proxyStartRow);
                if (row.isNode()) {
                    return ("node://" + row.nodeLink).hashCode();
                }
                return row.nativeInfo.hashCode();
            } else {
                return -7;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == useProxyShadowRow || position == proxyShadowRow) {
                return VIEW_TYPE_SHADOW;
            } else if (position == useProxyRow || position == callsRow) {
                return VIEW_TYPE_TEXT_CHECK;
            } else if (position == connectionsHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                return VIEW_TYPE_PROXY_DETAIL;
            } else {
                return VIEW_TYPE_INFO;
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextCheckCell.class, HeaderCell.class, TextDetailProxyCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextDetailProxyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText6));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGreenText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_text_RedRegular));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"checkImageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));

        return themeDescriptions;
    }
}
