/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
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

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProxyRotationController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.QRCodeBottomSheet;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.helpers.ProxyTypes;
import tw.nekomimi.nekogram.helpers.VlessProxyManager;
import tw.nekomimi.nekogram.helpers.WebSocketHelper;
import tw.nekomimi.nekogram.utils.AlertUtil;
import tw.nekomimi.nekogram.utils.ProxyUtil;
import tw.nekomimi.nekogram.utils.VlessImportHelper;

public class ProxyListActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final static boolean IS_PROXY_ROTATION_AVAILABLE = true;
    private static final int MENU_DELETE = 0;
    private static final int MENU_SHARE = 1;

    private ListAdapter listAdapter;
    private RecyclerListView listView;
    @SuppressWarnings("FieldCanBeLocal")
    private LinearLayoutManager layoutManager;

    private int currentConnectionState;

    private boolean useProxySettings;
    private boolean useProxyForCalls;

    private int rowCount;
    @Keep
    private int useProxyRow;
    private int useProxyShadowRow;
    private int connectionsHeaderRow;
    private int proxyStartRow;
    private int proxyEndRow;
    @Keep
    private int proxyAddRow;
    private int proxyShadowRow;
    @Keep
    private int callsRow;
    private int rotationRow;
    private int rotationTimeoutRow;
    private int rotationTimeoutInfoRow;
    private int callsDetailRow;
    private int deleteAllRow;

    // VLESS (built-in sing-box) section — rendered separately from the native
    // proxy list so it never leaks into SharedConfig.proxyList semantics.
    private final List<String> vlessNodes = new ArrayList<>();
    private int vlessHeaderRow = -1;
    private int vlessStartRow = -1;
    private int vlessEndRow = -1;
    private int vlessManageRow = -1;

    private ItemTouchHelper itemTouchHelper;
    private NumberTextView selectedCountTextView;
    private ActionBarMenuItem shareMenuItem;
    private ActionBarMenuItem deleteMenuItem;

    private List<SharedConfig.ProxyInfo> selectedItems = new ArrayList<>();
    // VLESS nodes selected in the same actionMode as native proxies.
    private List<String> selectedVless = new ArrayList<>();
    private List<SharedConfig.ProxyInfo> proxyList = new ArrayList<>();
    private boolean wasCheckedAllList;

    // na: action bar menu
    private ActionBarMenuItem otherItem;

    public class TextDetailProxyCell extends FrameLayout {

        private TextView textView;
        private TextView valueTextView;
        private ImageView shareImageView;
        private ImageView checkImageView;
        private SharedConfig.ProxyInfo currentInfo;
        private Drawable checkDrawable;

        // VLESS node mode: when vlessLink != null this cell renders one saved
        // vless:// node instead of a native SharedConfig.ProxyInfo row.
        private String vlessLink;
        private boolean isVlessRow;

        private CheckBox2 checkBox;
        private boolean isSelected;
        private boolean isSelectionEnabled;

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

            shareImageView = new ImageView(context);
            shareImageView.setImageResource(R.drawable.msg_share);
            shareImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), PorterDuff.Mode.MULTIPLY));
            shareImageView.setScaleType(ImageView.ScaleType.CENTER);
            shareImageView.setContentDescription(LocaleController.getString(R.string.ShareFile));
            addView(shareImageView, LayoutHelper.createFrame(
                48,
                48,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP,
                LocaleController.isRTL ? 56 : 8,
                8,
                LocaleController.isRTL ? 8 : 56,
                0
            ));
            shareImageView.setOnClickListener(v -> {
                if (vlessLink != null) {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, vlessLink);
                    Context ctx = getParentActivity();
                    if (ctx != null) {
                        ctx.startActivity(Intent.createChooser(shareIntent, getString(R.string.ShareFile)));
                    }
                } else {
                    showProxyQrCode(context, currentInfo);
                }
            });

            checkImageView = new ImageView(context);
            checkImageView.setImageResource(R.drawable.msg_info);
            checkImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), PorterDuff.Mode.MULTIPLY));
            checkImageView.setScaleType(ImageView.ScaleType.CENTER);
            checkImageView.setContentDescription(getString(R.string.Edit));
            addView(checkImageView, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 8, 8, 8, 0));
            checkImageView.setOnClickListener(v -> {
                if (vlessLink != null) {
                    presentNodeEditor(vlessLink);
                } else if (WebSocketHelper.proxyServer.equals(currentInfo.address)) {
                    presentFragment(new tw.nekomimi.nekogram.settings.WsSettingsActivity(currentInfo));
                } else {
                    presentFragment(new ProxySettingsActivity(currentInfo));
                }
            });

            checkBox = new CheckBox2(context, 21);
            checkBox.setColor(Theme.key_checkbox, Theme.key_radioBackground, Theme.key_checkboxCheck);
            checkBox.setDrawBackgroundAsArc(14);
            checkBox.setVisibility(GONE);
            addView(checkBox, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 16, 0, 8, 0));

            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + 1, MeasureSpec.EXACTLY));
        }

        public void setProxy(SharedConfig.ProxyInfo proxyInfo) {
            isVlessRow = false;
            vlessLink = null;
            if (WebSocketHelper.proxyServer.equals(proxyInfo.address)) {
                textView.setText(LocaleController.getString(R.string.PublicProxy));
            } else if (TextUtils.isEmpty(proxyInfo.address) || proxyInfo.port <= 0) {
                textView.setText(LocaleController.getString(R.string.ProxyInvalid));
            } else {
                textView.setText(proxyInfo.address + ":" + proxyInfo.port);
            }
            currentInfo = proxyInfo;
        }

        /** Binds this cell to a saved built-in node instead of a native proxy. */
        public void setVlessNode(String link) {
            isVlessRow = true;
            vlessLink = link;
            currentInfo = null;
            // Row title shows the protocol tag like "[vmess] name".
            String tag = ProxyTypes.typeTag(link);
            String name = ProxyTypes.nodeName(link);
            StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(tag)) {
                sb.append('[').append(tag).append("] ");
            }
            if (!TextUtils.isEmpty(name)) {
                sb.append(name);
            }
            if (sb.length() == 0) {
                sb.append(LocaleController.getString(R.string.ProxyNodes));
            }
            textView.setText(sb.toString());
            valueTextView.setText(ProxyTypes.nodeServerPort(link));
            updateStatus();
        }

        public void updateStatus() {
            if (isVlessRow) {
                boolean active = VlessProxyManager.isActiveNode(vlessLink);
                String serverPort = VlessProxyManager.nodeServerPort(vlessLink);
                long ping = VlessProxyManager.getPing(vlessLink);
                if (ping >= 0) {
                    serverPort = serverPort + ", " + LocaleController.formatString("Ping", R.string.Ping, ping);
                }
                String status;
                int colorKey;
                if (active) {
                    if (currentConnectionState == ConnectionsManager.ConnectionStateConnected || currentConnectionState == ConnectionsManager.ConnectionStateUpdating) {
                        colorKey = Theme.key_windowBackgroundWhiteBlueText6;
                        status = serverPort + ", " + getString(R.string.Connected);
                    } else {
                        colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                        status = serverPort + ", " + getString(R.string.Connecting);
                    }
                } else {
                    colorKey = ping >= 0 ? Theme.key_windowBackgroundWhiteGreenText : Theme.key_windowBackgroundWhiteGrayText2;
                    status = serverPort;
                }
                color = Theme.getColor(colorKey);
                valueTextView.setText(status);
                valueTextView.setTag(colorKey);
                valueTextView.setTextColor(color);
                if (checkDrawable != null) {
                    checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                }
                setChecked(active);
                return;
            }
            int colorKey;
            if (SharedConfig.currentProxy == currentInfo && useProxySettings) {
                if (currentConnectionState == ConnectionsManager.ConnectionStateConnected || currentConnectionState == ConnectionsManager.ConnectionStateUpdating) {
                    colorKey = Theme.key_windowBackgroundWhiteBlueText6;
                    if (currentInfo.ping != 0) {
                        valueTextView.setText(getString(R.string.Connected) + ", " + LocaleController.formatString("Ping", R.string.Ping, currentInfo.ping));
                    } else {
                        valueTextView.setText(getString(R.string.Connected));
                    }
                    if (!currentInfo.checking && !currentInfo.available) {
                        currentInfo.availableCheckTime = 0;
                    }
                } else {
                    colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                    valueTextView.setText(getString(R.string.Connecting));
                }
            } else {
                if (currentInfo.checking) {
                    valueTextView.setText(getString(R.string.Checking));
                    colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                } else if (currentInfo.available) {
                    if (currentInfo.ping != 0) {
                        valueTextView.setText(getString(R.string.Available) + ", " + LocaleController.formatString("Ping", R.string.Ping, currentInfo.ping));
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

        public void setSelectionEnabled(boolean enabled, boolean animated) {
            if (isSelectionEnabled == enabled && animated) {
                return;
            }
            isSelectionEnabled = enabled;

            float fromX = 0, toX = LocaleController.isRTL ? -AndroidUtilities.dp(32) : AndroidUtilities.dp(32);
            if (!animated) {
                float x = enabled ? toX : fromX;
                textView.setTranslationX(x);
                valueTextView.setTranslationX(x);
                shareImageView.setTranslationX(x);
                checkImageView.setTranslationX(x);
                checkBox.setTranslationX((LocaleController.isRTL ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32)) + x);
                shareImageView.setVisibility(enabled ? GONE : VISIBLE);
                shareImageView.setAlpha(1f);
                shareImageView.setScaleX(1f);
                shareImageView.setScaleY(1f);
                checkImageView.setVisibility(enabled ? GONE : VISIBLE);
                checkImageView.setAlpha(1f);
                checkImageView.setScaleX(1f);
                checkImageView.setScaleY(1f);
                checkBox.setVisibility(enabled ? VISIBLE : GONE);
                checkBox.setAlpha(1f);
                checkBox.setScaleX(1f);
                checkBox.setScaleY(1f);
            } else {
                ValueAnimator animator = ValueAnimator.ofFloat(enabled ? 0 : 1, enabled ? 1 : 0).setDuration(200);
                animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
                animator.addUpdateListener(animation -> {
                    float val = (float) animation.getAnimatedValue();
                    float x = AndroidUtilities.lerp(fromX, toX, val);
                    textView.setTranslationX(x);
                    valueTextView.setTranslationX(x);
                    shareImageView.setTranslationX(x);
                    checkImageView.setTranslationX(x);
                    checkBox.setTranslationX((LocaleController.isRTL ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32)) + x);

                    float scale = 0.5f + val * 0.5f;
                    checkBox.setScaleX(scale);
                    checkBox.setScaleY(scale);
                    checkBox.setAlpha(val);

                    scale = 0.5f + (1f - val) * 0.5f;
                    shareImageView.setScaleX(scale);
                    shareImageView.setScaleY(scale);
                    shareImageView.setAlpha(1f - val);
                    checkImageView.setScaleX(scale);
                    checkImageView.setScaleY(scale);
                    checkImageView.setAlpha(1f - val);
                });
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        if (enabled) {
                            checkBox.setAlpha(0f);
                            checkBox.setVisibility(VISIBLE);
                        } else {
                            shareImageView.setAlpha(0f);
                            shareImageView.setVisibility(VISIBLE);
                            checkImageView.setAlpha(0f);
                            checkImageView.setVisibility(VISIBLE);
                        }
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (enabled) {
                            shareImageView.setVisibility(GONE);
                            checkImageView.setVisibility(GONE);
                        } else {
                            checkBox.setVisibility(GONE);
                        }
                    }
                });
                animator.start();
            }
        }

        public void setItemSelected(boolean selected, boolean animated) {
            if (selected == isSelected && animated) {
                return;
            }
            isSelected = selected;
            checkBox.setChecked(selected, animated);
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

    private void showProxyQrCode(Context context, SharedConfig.ProxyInfo proxyInfo) {
        if (context == null || proxyInfo == null) {
            return;
        }
        String link = proxyInfo.getLink();
        if (TextUtils.isEmpty(link)) {
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
        // The VLESS engine keeps the master switch on even when no native proxy
        // rows exist (only the hidden 127.0.0.1 shadow entry is stored).
        useProxySettings = VlessProxyManager.isEnabled() || proxyEnabledPref && !SharedConfig.proxyList.isEmpty();
        useProxyForCalls = proxyEnabledPref && preferences.getBoolean("proxy_enabled_calls", false);

        updateRows(true);

        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyChangedByRotation);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
    }

    private final static int na_menu_other = 1001;
    private final static int na_menu_add_input_telegram = 1002;
    private final static int na_menu_add_import_from_clipboard = 1003;
    private final static int na_menu_retest_ping = 1004;
    private final static int na_menu_delete_all = 1005;
    private final static int na_menu_delete_unavailable = 1006;
    private final static int na_menu_vless_add = 1007;
    private final static int na_menu_vless_subscribe = 1008;
    private final static int na_menu_vless_test = 1009;
    private final static int na_menu_add = 1010;
    private final static int na_menu_add_scan_qr = 1011;
    private final static int na_menu_add_input_socks = 1012;
    private final static int na_menu_add_input_vmess = 1013;
    private final static int na_menu_add_input_trojan = 1014;
    private final static int na_menu_add_input_ss = 1015;

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

        // na: action bar menu — "＋" add menu + "⋮" tools menu.
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem addItem = menu.addItem(na_menu_add, R.drawable.add);
        addItem.setContentDescription(LocaleController.getString("AddProxy", R.string.AddProxy));
        addItem.addSubItem(na_menu_add_import_from_clipboard, LocaleController.getString("ImportProxyFromClipboard", R.string.ImportProxyFromClipboard)).setOnClickListener((v) -> importFromClipboardMenu());
        addItem.addSubItem(na_menu_add_scan_qr, LocaleController.getString("ScanQRCode", R.string.ScanQRCode)).setOnClickListener((v) -> scanQrCodeMenu());
        addItem.addSubItem(na_menu_add_input_socks, LocaleController.getString("AddProxySocks5", R.string.AddProxySocks5)).setOnClickListener((v) -> presentFragment(new ProxySettingsActivity()));
        addItem.addSubItem(na_menu_add_input_telegram, LocaleController.getString("AddProxyTelegram", R.string.AddProxyTelegram)).setOnClickListener((v) -> presentFragment(new ProxySettingsActivity()));
        addItem.addSubItem(na_menu_add_input_vmess, LocaleController.getString("AddProxyVmess", R.string.AddProxyVmess)).setOnClickListener((v) -> presentFragment(new tw.nekomimi.nekogram.settings.VmessNodeEditActivity()));
        addItem.addSubItem(na_menu_add_input_trojan, LocaleController.getString("AddProxyTrojan", R.string.AddProxyTrojan)).setOnClickListener((v) -> presentFragment(new tw.nekomimi.nekogram.settings.TrojanNodeEditActivity()));
        addItem.addSubItem(na_menu_add_input_ss, LocaleController.getString("AddProxySS", R.string.AddProxySS)).setOnClickListener((v) -> presentFragment(new tw.nekomimi.nekogram.settings.ShadowsocksNodeEditActivity()));

        otherItem = menu.addItem(na_menu_other, R.drawable.ic_ab_other);
        otherItem.setContentDescription(LocaleController.getString("AccDescrMoreOptions", R.string.AccDescrMoreOptions));
        otherItem.addSubItem(na_menu_vless_add, LocaleController.getString(R.string.ProxyAddNode)).setOnClickListener((v) ->
                presentFragment(new tw.nekomimi.nekogram.settings.VlessNodeEditActivity()));
        otherItem.addSubItem(na_menu_vless_subscribe, LocaleController.getString(R.string.VlessImportSubscription)).setOnClickListener((v) ->
                VlessImportHelper.showSubscriptionDialog(ProxyListActivity.this, () -> updateRows(true)));
        otherItem.addSubItem(na_menu_vless_test, LocaleController.getString(R.string.VlessTestNodes)).setOnClickListener((v) ->
                testVlessNodes());
        otherItem.addSubItem(na_menu_retest_ping, LocaleController.getString("RetestPing", R.string.RetestPing)).setOnClickListener((v) -> {
            checkProxyList(true);
            for (int a = proxyStartRow; a < proxyEndRow; a++) {
                RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                if (holder != null) {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    cell.updateStatus();
                }
            }
        });
        otherItem.addSubItem(na_menu_delete_all, LocaleController.getString("DeleteAllServer", R.string.DeleteAllServer)).setOnClickListener((v) -> AlertUtil.showConfirm(getParentActivity(),
                LocaleController.getString("DeleteAllServer", R.string.DeleteAllServer),
                R.drawable.msg_delete, LocaleController.getString("Delete", R.string.Delete),
                true, () -> {
                    // Deleting every native server must not leave the proxy engine
                    // running against a deleted shadow entry.
                    if (VlessProxyManager.isEnabled()) {
                        VlessProxyManager.setEnabled(false);
                    }
                    SharedConfig.deleteAllProxy();
                    if (SharedConfig.currentProxy == null) {
                        useProxySettings = false;
                        useProxyForCalls = false;
                    }
                    updateRows(true);
                })
        );
        otherItem.addSubItem(na_menu_delete_unavailable, LocaleController.getString("DeleteUnavailableServer", R.string.DeleteUnavailableServer)).setOnClickListener((v) -> {
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
                        NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                        NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                        updateRows(true);
                        if (listAdapter != null) {
                            if (SharedConfig.currentProxy == null) {
                                listAdapter.notifyItemChanged(useProxyRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                                listAdapter.notifyItemChanged(callsRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                            }
                            listAdapter.clearSelected();
                        }
                    });
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        ((DefaultItemAnimator) listView.getItemAnimator()).setDelayAnimations(false);
        ((DefaultItemAnimator) listView.getItemAnimator()).setTranslationInterpolator(CubicBezierInterpolator.DEFAULT);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position == useProxyRow) {
                // VLESS-aware master switch. The switch controls whichever proxy
                // scheme is active: the built-in VLESS engine when it is running,
                // otherwise a saved native proxy.
                if (VlessProxyManager.isEnabled()) {
                    // Turning the VLESS engine off.
                    VlessProxyManager.setEnabled(false);
                    useProxySettings = false;
                    useProxyForCalls = false;
                    TextCheckCell vlessSwitch = (TextCheckCell) view;
                    vlessSwitch.setChecked(false);
                    updateRows(true);
                    NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    return;
                }
                if (!useProxySettings && VlessProxyManager.hasConfig()) {
                    // Turning on: a VLESS node is configured, so boot the engine
                    // (never auto-enable the stopped 127.0.0.1 shadow entry).
                    VlessProxyManager.selectNode(VlessProxyManager.getVlessLink());
                    useProxySettings = true;
                    useProxyForCalls = false;
                    TextCheckCell vlessSwitch = (TextCheckCell) view;
                    vlessSwitch.setChecked(true);
                    updateRows(false);
                    NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    for (int a = vlessStartRow; a < vlessEndRow; a++) {
                        RecyclerListView.Holder h = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                        if (h != null) {
                            ((TextDetailProxyCell) h.itemView).updateStatus();
                        }
                    }
                    return;
                }
                if (SharedConfig.currentProxy == null) {
                    if (!proxyList.isEmpty()) {
                        SharedConfig.currentProxy = proxyList.get(0);

                        if (!useProxySettings) {
                            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                            editor.putString("proxy_ip", SharedConfig.currentProxy.address);
                            editor.putString("proxy_pass", SharedConfig.currentProxy.password);
                            editor.putString("proxy_user", SharedConfig.currentProxy.username);
                            editor.putInt("proxy_port", SharedConfig.currentProxy.port);
                            editor.putString("proxy_secret", SharedConfig.currentProxy.secret);
                            editor.commit();
                        }
                    } else {
                        presentFragment(new ProxySettingsActivity());
                        return;
                    }
                }
                useProxySettings = !useProxySettings;
                updateRows(true);

                SharedPreferences preferences = MessagesController.getGlobalMainSettings();

                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(useProxySettings);
                if (!useProxySettings) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(callsRow);
                    if (holder != null) {
                        textCheckCell = (TextCheckCell) holder.itemView;
                        textCheckCell.setChecked(false);
                    }
                    useProxyForCalls = false;
                }

                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putBoolean("proxy_enabled", useProxySettings);
                editor.commit();

                ConnectionsManager.setProxySettings(useProxySettings, SharedConfig.currentProxy.address, SharedConfig.currentProxy.port, SharedConfig.currentProxy.username, SharedConfig.currentProxy.password, SharedConfig.currentProxy.secret);
                NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);

                for (int a = proxyStartRow; a < proxyEndRow; a++) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.updateStatus();
                    }
                }
            } else if (position == rotationRow) {
                SharedConfig.proxyRotationEnabled = !SharedConfig.proxyRotationEnabled;
                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(SharedConfig.proxyRotationEnabled);
                SharedConfig.saveConfig();

                updateRows(true);
            } else if (position == callsRow) {
                useProxyForCalls = !useProxyForCalls;
                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(useProxyForCalls);
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putBoolean("proxy_enabled_calls", useProxyForCalls);
                editor.commit();
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                if (!selectedItems.isEmpty() || !selectedVless.isEmpty()) {
                    listAdapter.toggleSelected(position);
                    return;
                }
                // Native proxy takes over: stop the VLESS engine if it was running.
                if (VlessProxyManager.isEnabled()) {
                    VlessProxyManager.setEnabled(false);
                }
                SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                useProxySettings = true;
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putString("proxy_ip", info.address);
                editor.putString("proxy_pass", info.password);
                editor.putString("proxy_user", info.username);
                editor.putInt("proxy_port", info.port);
                editor.putString("proxy_secret", info.secret);
                editor.putBoolean("proxy_enabled", useProxySettings);
                if (!info.secret.isEmpty()) {
                    useProxyForCalls = false;
                    editor.putBoolean("proxy_enabled_calls", false);
                }
                editor.commit();
                SharedConfig.currentProxy = info;
                for (int a = proxyStartRow; a < proxyEndRow; a++) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.setChecked(cell.currentInfo == info);
                        cell.updateStatus();
                    }
                }
                for (int a = vlessStartRow; a < vlessEndRow; a++) {
                    RecyclerListView.Holder h = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (h != null) {
                        ((TextDetailProxyCell) h.itemView).updateStatus();
                    }
                }
                updateRows(false);
                RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(useProxyRow);
                if (holder != null) {
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    textCheckCell.setChecked(true);
                }
                ConnectionsManager.setProxySettings(useProxySettings, SharedConfig.currentProxy.address, SharedConfig.currentProxy.port, SharedConfig.currentProxy.username, SharedConfig.currentProxy.password, SharedConfig.currentProxy.secret);
            } else if (position >= vlessStartRow && position < vlessEndRow) {
                if (!selectedItems.isEmpty() || !selectedVless.isEmpty()) {
                    listAdapter.toggleSelected(position);
                    return;
                }
                String link = vlessNodes.get(position - vlessStartRow);
                // Starts the engine with this node (or re-runs it) and, when the
                // proxy was off, points Telegram at 127.0.0.1:LOCAL_PORT.
                VlessProxyManager.selectNode(link);
                useProxySettings = true;
                updateRows(false);
                RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(useProxyRow);
                if (holder != null) {
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    textCheckCell.setChecked(true);
                }
                for (int a = proxyStartRow; a < proxyEndRow; a++) {
                    RecyclerListView.Holder h = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (h != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) h.itemView;
                        cell.setChecked(false);
                        cell.updateStatus();
                    }
                }
                for (int a = vlessStartRow; a < vlessEndRow; a++) {
                    RecyclerListView.Holder h = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (h != null) {
                        ((TextDetailProxyCell) h.itemView).updateStatus();
                    }
                }
            } else if (position == vlessManageRow) {
                // 8.x structure: the list page is the only carrier, "add" opens
                // the field form (same as AddProxy -> ProxySettingsActivity).
                presentFragment(new tw.nekomimi.nekogram.settings.VlessNodeEditActivity());
            } else if (position == proxyAddRow) {
                presentFragment(new ProxySettingsActivity());
            } else if (position == deleteAllRow) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setMessage(getString(R.string.DeleteAllProxiesConfirm));
                builder.setNegativeButton(getString(R.string.Cancel), null);
                builder.setTitle(getString(R.string.DeleteProxyTitle));
                builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    for (SharedConfig.ProxyInfo info : proxyList) {
                        SharedConfig.deleteProxy(info);
                    }
                    useProxyForCalls = false;
                    useProxySettings = false;
                    NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    updateRows(true);
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(useProxyRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                        listAdapter.notifyItemChanged(callsRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                        listAdapter.clearSelected();
                    }
                });
                AlertDialog dialog = builder.create();
                showDialog(dialog);
                TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (button != null) {
                    button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= proxyStartRow && position < proxyEndRow || position >= vlessStartRow && position < vlessEndRow) {
                listAdapter.toggleSelected(position);
                return true;
            }
            return false;
        });

        ActionBarMenu actionMode = actionBar.createActionMode();
        selectedCountTextView = new NumberTextView(actionMode.getContext());
        selectedCountTextView.setTextSize(18);
        selectedCountTextView.setTypeface(AndroidUtilities.bold());
        selectedCountTextView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(selectedCountTextView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        selectedCountTextView.setOnTouchListener((v, event) -> true);

        shareMenuItem = actionMode.addItemWithWidth(MENU_SHARE, R.drawable.msg_share, AndroidUtilities.dp(54));
        shareMenuItem.setContentDescription(getString(R.string.StickersShare));
        deleteMenuItem = actionMode.addItemWithWidth(MENU_DELETE, R.drawable.msg_delete, AndroidUtilities.dp(54));
        deleteMenuItem.setContentDescription(getString(R.string.Delete));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                switch (id) {
                    case -1:
                        if (selectedItems.isEmpty() && selectedVless.isEmpty()) {
                            finishFragment();
                        } else {
                            listAdapter.clearSelected();
                        }
                        break;
                    case MENU_DELETE:
                        int deleteCount = selectedItems.size() + selectedVless.size();
                        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                        builder.setMessage(getString(deleteCount > 1 ? R.string.DeleteProxyMultiConfirm : R.string.DeleteProxyConfirm));
                        builder.setNegativeButton(getString(R.string.Cancel), null);
                        builder.setTitle(getString(R.string.DeleteProxyTitle));
                        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                            for (SharedConfig.ProxyInfo info : selectedItems) {
                                SharedConfig.deleteProxy(info);
                            }
                            for (String link : selectedVless) {
                                VlessProxyManager.removeNode(link);
                            }
                            if (!VlessProxyManager.isEnabled() && SharedConfig.currentProxy == null) {
                                useProxyForCalls = false;
                                useProxySettings = false;
                            }
                            NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                            NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                            updateRows(true);
                            if (listAdapter != null) {
                                if (SharedConfig.currentProxy == null) {
                                    listAdapter.notifyItemChanged(useProxyRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                                    listAdapter.notifyItemChanged(callsRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                                }
                                listAdapter.clearSelected();
                            }
                        });
                        AlertDialog dialog = builder.create();
                        showDialog(dialog);
                        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                        if (button != null) {
                            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                        }
                        break;
                    case MENU_SHARE:
                        StringBuilder links = new StringBuilder();
                        for (SharedConfig.ProxyInfo info : selectedItems) {
                            if (links.length() > 0) {
                                links.append("\n\n");
                            }
                            links.append(info.getLink());
                        }
                        for (String link : selectedVless) {
                            if (links.length() > 0) {
                                links.append("\n\n");
                            }
                            links.append(link);
                        }

                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, links.toString());
                        int shareCount = selectedItems.size() + selectedVless.size();
                        Intent chooserIntent = Intent.createChooser(shareIntent, getString(shareCount > 1 ? R.string.ShareLinks : R.string.ShareLink));
                        chooserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(chooserIntent);

                        if (listAdapter != null) {
                            listAdapter.clearSelected();
                        }
                        break;
                }
            }
        });

        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selectedItems.isEmpty() || !selectedVless.isEmpty()) {
            if (invoked) listAdapter.clearSelected();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void updateRows(boolean notify) {
        rowCount = 0;
        useProxyRow = rowCount++;

        // VLESS (built-in sing-box) section. A manage row is always present and
        // acts as the "add" entry while no node has been configured yet.
        if (notify) {
            vlessNodes.clear();
            vlessNodes.addAll(VlessProxyManager.getNodes());
        }
        if (vlessNodes.isEmpty()) {
            vlessHeaderRow = -1;
            vlessStartRow = -1;
            vlessEndRow = -1;
            vlessManageRow = rowCount++;
        } else {
            vlessHeaderRow = rowCount++;
            vlessStartRow = rowCount;
            rowCount += vlessNodes.size();
            vlessEndRow = rowCount;
            vlessManageRow = rowCount++;
        }

        if (useProxySettings && SharedConfig.currentProxy != null && SharedConfig.proxyList.size() > 1 && IS_PROXY_ROTATION_AVAILABLE) {
            rotationRow = rowCount++;
            if (SharedConfig.proxyRotationEnabled) {
                rotationTimeoutRow = rowCount++;
                rotationTimeoutInfoRow = rowCount++;
            } else {
                rotationTimeoutRow = -1;
                rotationTimeoutInfoRow = -1;
            }
        } else {
            rotationRow = -1;
            rotationTimeoutRow = -1;
            rotationTimeoutInfoRow = -1;
        }
        if (rotationTimeoutInfoRow == -1) {
            useProxyShadowRow = rowCount++;
        } else {
            useProxyShadowRow = -1;
        }
        connectionsHeaderRow = rowCount++;

        if (notify) {
            proxyList.clear();
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                // The local VLESS entry (127.0.0.1:LOCAL_PORT) is rendered by the
                // VLESS section above, not as a native user proxy row.
                if ("127.0.0.1".equals(info.address) && info.port == VlessProxyManager.LOCAL_PORT) {
                    continue;
                }
                proxyList.add(info);
            }

            boolean checking = false;
            if (!wasCheckedAllList) {
                for (SharedConfig.ProxyInfo info : proxyList) {
                    if (info.checking || info.availableCheckTime == 0) {
                        checking = true;
                        break;
                    }
                }
                if (!checking) {
                    wasCheckedAllList = true;
                }
            }

            boolean isChecking = checking;
            Collections.sort(proxyList, (o1, o2) -> {
                long bias1 = SharedConfig.currentProxy == o1 ? -200000 : 0;
                if (!o1.available) {
                    bias1 += 100000;
                }
                long bias2 = SharedConfig.currentProxy == o2 ? -200000 : 0;
                if (!o2.available) {
                    bias2 += 100000;
                }
                return Long.compare(isChecking && o1 != SharedConfig.currentProxy ? SharedConfig.proxyList.indexOf(o1) * 10000L : o1.ping + bias1,
                        isChecking && o2 != SharedConfig.currentProxy ? SharedConfig.proxyList.indexOf(o2) * 10000L : o2.ping + bias2);
            });
        }

        if (!proxyList.isEmpty()) {
            proxyStartRow = rowCount;
            rowCount += proxyList.size();
            proxyEndRow = rowCount;
        } else {
            proxyStartRow = -1;
            proxyEndRow = -1;
        }
        proxyAddRow = rowCount++;
        proxyShadowRow = rowCount++;
        if (SharedConfig.currentProxy == null || SharedConfig.currentProxy.secret.isEmpty()) {
            boolean change = callsRow == -1;
            callsRow = rowCount++;
            callsDetailRow = rowCount++;
            if (!notify && change) {
                listAdapter.notifyItemChanged(proxyShadowRow);
                listAdapter.notifyItemRangeInserted(proxyShadowRow + 1, 2);
            }
        } else {
            boolean change = callsRow != -1;
            callsRow = -1;
            callsDetailRow = -1;
            if (!notify && change) {
                listAdapter.notifyItemChanged(proxyShadowRow);
                listAdapter.notifyItemRangeRemoved(proxyShadowRow + 1, 2);
            }
        }
        if (proxyList.size() >= 10) {
            deleteAllRow = rowCount++;
        } else {
            deleteAllRow = -1;
        }
        checkProxyList();
        if (notify && listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
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
        if (!ProxyUtil.parseProxies(trimmed).isEmpty()) {
            VlessImportHelper.importText(ProxyListActivity.this, trimmed, () -> updateRows(true));
            return;
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

    /**
     * Measures TCP latency for every node on the global queue and shows the
     * result on the node row (the native page pings native proxies the same way
     * through [checkProxyList]).
     */
    private void testVlessNodes() {
        if (vlessNodes.isEmpty()) {
            return;
        }
        final ArrayList<String> nodes = new ArrayList<>(vlessNodes);
        Utilities.globalQueue.postRunnable(() -> {
            for (String link : nodes) {
                if (link == null) {
                    continue;
                }
                long ping = VlessProxyManager.pingNode(link);
                AndroidUtilities.runOnUIThread(() -> {
                    VlessProxyManager.setPing(link, ping);
                    if (listAdapter == null || vlessStartRow < 0) {
                        return;
                    }
                    int idx = vlessNodes.indexOf(link);
                    if (idx >= 0) {
                        listAdapter.notifyItemChanged(vlessStartRow + idx);
                    }
                });
            }
        });
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
        if (id == NotificationCenter.proxyChangedByRotation) {
            listView.forAllChild(view -> {
                RecyclerView.ViewHolder holder = listView.getChildViewHolder(view);
                if (holder.itemView instanceof TextDetailProxyCell) {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    if (cell.vlessLink != null) {
                        cell.updateStatus();
                    } else {
                        cell.setChecked(cell.currentInfo == SharedConfig.currentProxy);
                        cell.updateStatus();
                    }
                }
            });

            updateRows(false);
        } else if (id == NotificationCenter.proxySettingsChanged) {
            updateRows(true);
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (currentConnectionState != state) {
                currentConnectionState = state;
                if (listView != null) {
                    // VLESS rows track connection state even when the active
                    // proxy is the local 127.0.0.1 entry (not in proxyList).
                    listView.forAllChild(view -> {
                        RecyclerView.ViewHolder holder = listView.getChildViewHolder(view);
                        if (holder.itemView instanceof TextDetailProxyCell && ((TextDetailProxyCell) holder.itemView).vlessLink != null) {
                            ((TextDetailProxyCell) holder.itemView).updateStatus();
                        }
                    });
                }
                if (listView != null && SharedConfig.currentProxy != null) {
                    int idx = proxyList.indexOf(SharedConfig.currentProxy);
                    if (idx >= 0) {
                        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(idx + proxyStartRow);
                        if (holder != null) {
                            TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                            cell.updateStatus();
                        }
                    }

                    if (currentConnectionState == ConnectionsManager.ConnectionStateConnected) {
                        updateRows(true);
                    }
                }
            }
        } else if (id == NotificationCenter.proxyCheckDone) {
            if (listView != null) {
                SharedConfig.ProxyInfo proxyInfo = (SharedConfig.ProxyInfo) args[0];
                int idx = proxyList.indexOf(proxyInfo);
                if (idx >= 0) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(idx + proxyStartRow);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.updateStatus();
                    }
                }

                boolean checking = false;
                if (!wasCheckedAllList) {
                    for (SharedConfig.ProxyInfo info : proxyList) {
                        if (info.checking || info.availableCheckTime == 0) {
                            checking = true;
                            break;
                        }
                    }
                    if (!checking) {
                        wasCheckedAllList = true;
                    }
                }
                if (!checking) {
                    updateRows(true);
                }
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final static int VIEW_TYPE_SHADOW = 0,
                VIEW_TYPE_TEXT_SETTING = 1,
                VIEW_TYPE_HEADER = 2,
                VIEW_TYPE_TEXT_CHECK = 3,
                VIEW_TYPE_INFO = 4,
                VIEW_TYPE_PROXY_DETAIL = 5,
                VIEW_TYPE_SLIDE_CHOOSER = 6;

        public static final int PAYLOAD_CHECKED_CHANGED = 0;
        public static final int PAYLOAD_SELECTION_CHANGED = 1;
        public static final int PAYLOAD_SELECTION_MODE_CHANGED = 2;

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;

            setHasStableIds(true);
        }

        public void toggleSelected(int position) {
            if (position >= proxyStartRow && position < proxyEndRow) {
                SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                if (selectedItems.contains(info)) {
                    selectedItems.remove(info);
                } else {
                    selectedItems.add(info);
                }
                notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED);
            } else if (position >= vlessStartRow && position < vlessEndRow) {
                String link = vlessNodes.get(position - vlessStartRow);
                if (selectedVless.contains(link)) {
                    selectedVless.remove(link);
                } else {
                    selectedVless.add(link);
                }
                notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED);
            } else {
                return;
            }
            checkActionMode();
        }

        public void clearSelected() {
            selectedItems.clear();
            selectedVless.clear();
            if (proxyStartRow >= 0) {
                notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_CHANGED);
            }
            if (vlessStartRow >= 0) {
                notifyItemRangeChanged(vlessStartRow, vlessEndRow - vlessStartRow, PAYLOAD_SELECTION_CHANGED);
            }
            checkActionMode();
        }

        private void checkActionMode() {
            int selectedCount = selectedItems.size() + selectedVless.size();
            boolean actionModeShowed = actionBar.isActionModeShowed();
            if (selectedCount > 0) {
                selectedCountTextView.setNumber(selectedCount, actionModeShowed);
                if (!actionModeShowed) {
                    actionBar.showActionMode();
                    if (proxyStartRow >= 0) {
                        notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
                    }
                    if (vlessStartRow >= 0) {
                        notifyItemRangeChanged(vlessStartRow, vlessEndRow - vlessStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
                    }
                }
            } else if (actionModeShowed) {
                actionBar.hideActionMode();
                if (proxyStartRow >= 0) {
                    notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
                }
                if (vlessStartRow >= 0) {
                    notifyItemRangeChanged(vlessStartRow, vlessEndRow - vlessStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
                }
            }
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SHADOW: {
                    break;
                }
                case VIEW_TYPE_TEXT_SETTING: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == proxyAddRow) {
                        textCell.setText(getString(R.string.AddProxy), deleteAllRow != -1);
                    } else if (position == deleteAllRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                        textCell.setText(getString(R.string.DeleteAllProxies), false);
                    } else if (position == vlessManageRow) {
                        if (vlessNodes.isEmpty()) {
                            textCell.setText(getString(R.string.ProxyAddNode), false);
                        } else {
                            textCell.setText(getString(R.string.ProxyManageNodes), false);
                        }
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == connectionsHeaderRow) {
                        headerCell.setText(getString(R.string.ProxyConnections));
                    } else if (position == vlessHeaderRow) {
                        headerCell.setText(getString(R.string.ProxyNodes));
                    }
                    break;
                }
                case VIEW_TYPE_TEXT_CHECK: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == useProxyRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxySettings), useProxySettings, rotationRow != -1);
                    } else if (position == callsRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyForCalls), useProxyForCalls, false);
                    } else if (position == rotationRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyRotation), SharedConfig.proxyRotationEnabled, true);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == callsDetailRow) {
                        cell.setText(getString(R.string.UseProxyForCallsInfo));
                    } else if (position == rotationTimeoutInfoRow) {
                        cell.setText(getString(R.string.ProxyRotationTimeoutInfo));
                    }
                    break;
                }
                case VIEW_TYPE_PROXY_DETAIL: {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    if (position >= vlessStartRow && position < vlessEndRow) {
                        String link = vlessNodes.get(position - vlessStartRow);
                        cell.setVlessNode(link);
                        cell.setItemSelected(selectedVless.contains(link), false);
                        cell.setSelectionEnabled(!selectedItems.isEmpty() || !selectedVless.isEmpty(), false);
                    } else {
                        SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                        cell.setProxy(info);
                        cell.setChecked(SharedConfig.currentProxy == info);
                        cell.setItemSelected(selectedItems.contains(info), false);
                        cell.setSelectionEnabled(!selectedItems.isEmpty(), false);
                    }
                    break;
                }
                case VIEW_TYPE_SLIDE_CHOOSER: {
                    if (position == rotationTimeoutRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        ArrayList<Integer> options = new ArrayList<>(ProxyRotationController.ROTATION_TIMEOUTS);
                        String[] values = new String[options.size()];
                        for (int i = 0; i < options.size(); i++) {
                            values[i] = LocaleController.formatString(R.string.ProxyRotationTimeoutSeconds, options.get(i));
                        }
                        chooseView.setCallback(i -> {
                            SharedConfig.proxyRotationTimeout = i;
                            SharedConfig.saveConfig();
                        });
                        chooseView.setOptions(SharedConfig.proxyRotationTimeout, values);
                    }
                    break;
                }
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List payloads) {
            if (holder.getItemViewType() == VIEW_TYPE_PROXY_DETAIL && !payloads.isEmpty()) {
                TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                if (position >= vlessStartRow && position < vlessEndRow) {
                    if (payloads.contains(PAYLOAD_SELECTION_CHANGED)) {
                        cell.setItemSelected(selectedVless.contains(vlessNodes.get(position - vlessStartRow)), true);
                    }
                    if (payloads.contains(PAYLOAD_SELECTION_MODE_CHANGED)) {
                        cell.setSelectionEnabled(!selectedItems.isEmpty() || !selectedVless.isEmpty(), true);
                    }
                    cell.updateStatus();
                } else {
                    if (payloads.contains(PAYLOAD_SELECTION_CHANGED)) {
                        cell.setItemSelected(selectedItems.contains(proxyList.get(position - proxyStartRow)), true);
                    }
                    if (payloads.contains(PAYLOAD_SELECTION_MODE_CHANGED)) {
                        cell.setSelectionEnabled(!selectedItems.isEmpty(), true);
                    }
                }
            } else if (holder.getItemViewType() == VIEW_TYPE_TEXT_CHECK && payloads.contains(PAYLOAD_CHECKED_CHANGED)) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == callsRow) {
                    checkCell.setChecked(useProxyForCalls);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                }
            } else {
                super.onBindViewHolder(holder, position, payloads);
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
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == useProxyRow || position == rotationRow || position == callsRow || position == proxyAddRow || position == deleteAllRow || position == vlessManageRow || position >= proxyStartRow && position < proxyEndRow || position >= vlessStartRow && position < vlessEndRow;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_TEXT_SETTING:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
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
                case VIEW_TYPE_SLIDE_CHOOSER:
                    view = new SlideChooseView(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
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
            // Random stable ids, could be anything non-repeating
            if (position == useProxyShadowRow) {
                return -1;
            } else if (position == proxyShadowRow) {
                return -2;
            } else if (position == proxyAddRow) {
                return -3;
            } else if (position == useProxyRow) {
                return -4;
            } else if (position == callsRow) {
                return -5;
            } else if (position == connectionsHeaderRow) {
                return -6;
            } else if (position == deleteAllRow) {
                return -8;
            } else if (position == rotationRow) {
                return -9;
            } else if (position == rotationTimeoutRow) {
                return -10;
            } else if (position == rotationTimeoutInfoRow) {
                return -11;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                return proxyList.get(position - proxyStartRow).hashCode();
            } else if (position >= vlessStartRow && position < vlessEndRow) {
                return vlessNodes.get(position - vlessStartRow).hashCode();
            } else if (position == vlessHeaderRow) {
                return -12;
            } else if (position == vlessManageRow) {
                return -13;
            } else {
                return -7;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == useProxyShadowRow || position == proxyShadowRow) {
                return VIEW_TYPE_SHADOW;
            } else if (position == proxyAddRow || position == deleteAllRow || position == vlessManageRow) {
                return VIEW_TYPE_TEXT_SETTING;
            } else if (position == useProxyRow || position == rotationRow || position == callsRow) {
                return VIEW_TYPE_TEXT_CHECK;
            } else if (position == connectionsHeaderRow || position == vlessHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == rotationTimeoutRow) {
                return VIEW_TYPE_SLIDE_CHOOSER;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                return VIEW_TYPE_PROXY_DETAIL;
            } else if (position >= vlessStartRow && position < vlessEndRow) {
                return VIEW_TYPE_PROXY_DETAIL;
            } else {
                return VIEW_TYPE_INFO;
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, TextCheckCell.class, HeaderCell.class, TextDetailProxyCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));

//        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextDetailProxyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText6));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGreenText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_text_RedRegular));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"shareImageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3));
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
