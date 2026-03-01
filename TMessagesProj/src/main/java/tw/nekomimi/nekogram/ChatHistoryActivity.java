/*
 * This is the source code of Nagramx_Fork for Android.
 * It is licensed under GNU GPL v. 3 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * 
 * https://github.com/Keeperorowner/NagramX_Fork
 * 
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright @Chen_hai, 2025
 */

package tw.nekomimi.nekogram;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.util.Log;
import org.telegram.messenger.BuildVars;

import static android.view.View.MeasureSpec;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BlurredRecyclerView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.messenger.browser.Browser;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;

import java.util.ArrayList;
import java.util.LinkedList;

import tw.nekomimi.nekogram.BackButtonMenuRecent;
import java.util.HashSet;

public class ChatHistoryActivity extends BaseFragment {

    private static final String TAG = "ChatHistoryActivity";

    // Official Telegram user IDs that should be filtered
    private static final long TELEGRAM_SERVICE_USER_ID = 777000L;
    private static final long REPLIES_BOT_USER_ID = 708513L;
    private static final long STICKERS_BOT_USER_ID = 429000L;
    private static final long BOTFATHER_USER_ID = 136817688L;

    // Chat categories
    public enum ChatCategory {
        ALL(0, "All"),
        CHANNELS(1, "Channels"),
        GROUPS(2, "Groups"),
        USERS(3, "Users"),
        BOTS(4, "Bots");

        public final int id;
        public final String title;

        ChatCategory(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    // UI Components
    private ViewPagerFixed viewPager;
    private ViewPagerFixed.TabsView tabsView;

    // Data
    private ArrayList<HistoryItem> allHistoryItems = new ArrayList<>();
    private ArrayList<HistoryItem> filteredHistoryItems = new ArrayList<>();

    // Scroll position - only save for current tab
    private android.os.Parcelable savedScrollState = null;
    private int savedScrollTab = -1; // Which tab the saved position belongs to


    // Search
    private boolean isSearchMode = false;
    private String searchQuery = "";
    private ActionBarMenuItem searchItem;

    // State preservation - save search state and current tab
    private boolean savedSearchMode = false;
    private String savedSearchQuery = "";
    private int savedCurrentTab = 0; // Save current selected tab index
    private boolean isOpeningChat = false; // Flag indicating whether opening chat
    private boolean hasBeenInitialized = false; // Flag indicating whether initialized
    private boolean isRestoringSearchState = false; // Flag to prevent exitSearchMode during restoration

    // Multi-selection mode
    private boolean isMultiSelectMode = false;
    private ArrayList<HistoryItem> selectedItems = new ArrayList<>();
    private ActionBarMenuItem deleteItem;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadHistoryItems();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        saveState();
    }

    /**
     * Save current state (search state and current tab)
     */
    private void saveState() {
        // Save search state
        // Do not preserve search state if query is empty to avoid inconsistent UI on return
        if (isSearchMode && !android.text.TextUtils.isEmpty(searchQuery)) {
            savedSearchMode = true;
            savedSearchQuery = searchQuery;
        } else {
            savedSearchMode = false;
            savedSearchQuery = "";
        }
        
        // Save current tab index
        if (viewPager != null) {
            savedCurrentTab = viewPager.getCurrentPosition();
        }
        
        if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Save state: searchMode=" + savedSearchMode + ", query=" + savedSearchQuery + ", currentTab=" + savedCurrentTab);
    }

    /**
     * Restore previously saved state (search state and current tab)
     */
    private void restoreState() {
        restoreState(true);
    }

    private void restoreState(boolean refreshSearchResults) {
        if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Start restoring state: searchMode=" + savedSearchMode + ", query=" + savedSearchQuery + ", currentTab=" + savedCurrentTab);
        
        // Restore current tab first
        if (viewPager != null && savedCurrentTab >= 0 && savedCurrentTab < ChatCategory.values().length) {
            viewPager.setPosition(savedCurrentTab); // Restore tab position without animation
            // Also update the tab bar selection to match the content without scroll animation
            if (tabsView != null) {
                tabsView.selectTabWithId(savedCurrentTab, 1.0f);
            }
            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Tab restored to position: " + savedCurrentTab);
        }
        
        // Restore search state
        if (savedSearchMode && !android.text.TextUtils.isEmpty(savedSearchQuery)) {
            isSearchMode = true;
            searchQuery = savedSearchQuery;
            
            // Update title
            updateTitle();
            
            // Restore search field state
            if (searchItem != null) {
                searchItem.postDelayed(() -> {
                    searchItem.openSearch(false);
                    if (searchItem.getSearchField() != null) {
                        searchItem.getSearchField().setText(savedSearchQuery);
                    }
                }, 50);
            }
            
            // Keep the existing filtered snapshot unless the caller explicitly
            // asks to rebuild search results.
            if (refreshSearchResults) {
                performSearch(savedSearchQuery);
            }
            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Search state restored");
        }
    }



    @Override
    public View createView(Context context) {
        // Setup ActionBar
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        updateTitle();

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 2) {
                    showOptionsMenu();
                } else if (id == 4) { // Delete button
                    showDeleteSelectedDialog();
                }
            }
        });

        updateTitle();
        updateActionBarForNormalMode();

        // Create main layout
        fragmentView = new SizeNotifierFrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // Create ViewPager with tabs
        createViewPager(context, (SizeNotifierFrameLayout) fragmentView);

        // Mark as initialized
        hasBeenInitialized = true;

        // Restore saved state (only when returning from chat)
        if (isOpeningChat) {
            fragmentView.post(() -> restoreState(false));
        }

        return fragmentView;
    }

    private void createViewPager(Context context, SizeNotifierFrameLayout fragmentView) {
        // Create ViewPager with page change handling
        viewPager = new ViewPagerFixed(context) {
            @Override
            protected void onTabPageSelected(int position) {
                super.onTabPageSelected(position);
                
                // Clear saved scroll position when switching tabs
                savedScrollState = null;
                savedScrollTab = -1;
                
                // Exit multi-select mode when switching tabs
                if (isMultiSelectMode) {
                    exitMultiSelectMode();
                }
            }
        };
        viewPager.setAdapter(new CategoryPagerAdapter());

        // Create tabs
        tabsView = viewPager.createTabsView(true, ViewPagerFixed.SELECTOR_TYPE_BUBBLE_STYLE);
        tabsView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        // Add tabs and viewpager to main view
        fragmentView.addView(tabsView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP));
        fragmentView.addView(viewPager,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 48, 0, 0));

        // Update tabs
        updateTabs();
    }

    private void updateTabs() {
        if (tabsView != null) {
            tabsView.removeTabs();
            for (int i = 0; i < ChatCategory.values().length; i++) {
                ChatCategory category = ChatCategory.values()[i];
                tabsView.addTab(i, getTabTitle(category));
            }
            tabsView.finishAddingTabs();
        }
    }

    private String getTabTitle(ChatCategory category) {
        String baseTitle = getCategoryDisplayName(category);
        return baseTitle;
    }

    private int getCategoryCount(ChatCategory category) {
        int count = 0;
        for (HistoryItem item : allHistoryItems) {
            if (shouldIncludeItem(item, category)) {
                count++;
            }
        }
        return count;
    }

    private boolean shouldIncludeItem(HistoryItem item, ChatCategory category) {
        // Filter out official Telegram chats (Saved Messages, Replies, etc.)
        if (item.user != null) {
            // Skip official Telegram users (like Replies bot, Saved Messages)
            if (item.user.id == TELEGRAM_SERVICE_USER_ID || 
                item.user.id == REPLIES_BOT_USER_ID ||  
                item.user.id == UserConfig.getInstance(currentAccount).getClientUserId()) { // Self
                return false;
            }
        }

        if (category == ChatCategory.ALL) {
            return true;
        }

        if (item.user != null) {
            // User dialog
            if (item.user.bot) {
                return category == ChatCategory.BOTS;
            } else {
                return category == ChatCategory.USERS;
            }
        } else if (item.chat != null) {
            // Chat dialog
            if (item.chat.broadcast) {
                return category == ChatCategory.CHANNELS;
            } else {
                return category == ChatCategory.GROUPS;
            }
        }
        return false;
    }

    private void loadHistoryItems() {
        allHistoryItems.clear();

        // Get recent dialogs directly from BackButtonMenuRecent (no reflection needed)
        LinkedList<Long> recentDialogIds = BackButtonMenuRecent.getRecentDialogs(currentAccount);

        for (Long dialogId : recentDialogIds) {
            // Skip official/system dialogs
            if (isOfficialDialog(dialogId, currentAccount)) {
                continue;
            }

            HistoryItem item = createHistoryItem(dialogId, currentAccount);
            if (item != null) {
                allHistoryItems.add(item);
            }
        }

        // Initialize filtered data
        if (isSearchMode) {
            performSearch(searchQuery);
        } else {
            filteredHistoryItems.clear();
            filteredHistoryItems.addAll(allHistoryItems);
        }

        // Update tabs after loading data
        updateTabs();
    }

    /**
     * Creates a HistoryItem from a dialog ID, loading user/chat data from cache or database
     * @param dialogId The dialog ID
     * @param account The account number
     * @return HistoryItem or null if the user/chat could not be loaded
     */
    private static HistoryItem createHistoryItem(long dialogId, int account) {
        HistoryItem item = new HistoryItem();
        item.dialogId = dialogId;

        if (dialogId > 0) {
            // User dialog
            item.user = MessagesController.getInstance(account).getUser(dialogId);
            // If user is null, try to load it from database
            if (item.user == null) {
                item.user = loadUserFromDatabase(dialogId, account);
            }
            // Skip if user is still null
            if (item.user == null) {
                return null;
            }
        } else {
            // Chat dialog
            long chatId = -dialogId;
            item.chat = MessagesController.getInstance(account).getChat(chatId);
            // If chat is null, try to load it from database
            if (item.chat == null) {
                item.chat = loadChatFromDatabase(chatId, account);
            }
            // Skip if chat is still null
            if (item.chat == null) {
                return null;
            }
        }
        return item;
    }

    /**
     * Load user from database and cache it
     */
    private static TLRPC.User loadUserFromDatabase(long userId, int account) {
        try {
            ArrayList<Long> userIds = new ArrayList<>();
            userIds.add(userId);
            ArrayList<TLRPC.User> users = MessagesStorage.getInstance(account).getUsers(userIds);
            if (!users.isEmpty()) {
                TLRPC.User user = users.get(0);
                MessagesController.getInstance(account).putUser(user, true);
                return user;
            }
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                Log.e(TAG, "Failed to load user from database: " + userId, e);
            }
        }
        return null;
    }

    /**
     * Load chat from database and cache it
     */
    private static TLRPC.Chat loadChatFromDatabase(long chatId, int account) {
        try {
            ArrayList<Long> chatIds = new ArrayList<>();
            chatIds.add(chatId);
            ArrayList<TLRPC.Chat> chats = MessagesStorage.getInstance(account).getChats(chatIds);
            if (!chats.isEmpty()) {
                TLRPC.Chat chat = chats.get(0);
                MessagesController.getInstance(account).putChat(chat, true);
                return chat;
            }
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                Log.e(TAG, "Failed to load chat from database: " + chatId, e);
            }
        }
        return null;
    }



    public static boolean isOfficialDialog(long dialogId, int account) {
        if (dialogId > 0) {
            // User dialog - filter official/system users
            TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            if (user != null) {
                // Filter special users: self, replies, official bots
                if (UserObject.isUserSelf(user) || UserObject.isReplyUser(user)) {
                    return true;
                }
                // Filter official verified or support accounts if desired
                // Uncomment the next line if you want to hide verified accounts too
                // if (user.verified || user.support) return true;
            }

            // Filter specific official user IDs
            if (dialogId == TELEGRAM_SERVICE_USER_ID || 
                dialogId == STICKERS_BOT_USER_ID || 
                dialogId == BOTFATHER_USER_ID) { 
                return true;
            }
        }
        return false;
    }

    private boolean isOfficialDialog(long dialogId) {
        return isOfficialDialog(dialogId, currentAccount);
    }

    private void updateTitle() {
        if (isSearchMode) {
            actionBar.setTitle(getString(R.string.Search));
        } else {
            actionBar.setTitle(getString(R.string.RecentChats));
        }
    }

    private void exitSearchMode() {
        // Do not exit/clear while we are programmatically toggling search UI (e.g., entering/exiting multi-select)
        if (isRestoringSearchState) {
            return;
        }
        isSearchMode = false;
        searchQuery = "";
        
        // Clear saved search state only when user actively exits search (not during multi-select transitions)
        if (!isMultiSelectMode) {
            savedSearchMode = false;
            savedSearchQuery = "";
        }

        updateTitle();
        refreshAllPages();
    }

    private void performSearch(String query) {
        filteredHistoryItems.clear();

        if (TextUtils.isEmpty(query)) {
            // In empty search, show all items (browse mode)
            filteredHistoryItems.addAll(allHistoryItems);
        } else {
            String lowerQuery = query.toLowerCase();
            for (HistoryItem item : allHistoryItems) {
            if (matchesSearchQuery(item, lowerQuery)) {
                filteredHistoryItems.add(item);
            }
        }
        }

        refreshAllPages();
    }

    public static boolean matchesSearchQuery(HistoryItem item, String query) {
        // Search in user/chat name
        String name = "";
        if (item.user != null) {
            name = ContactsController.formatName(item.user.first_name, item.user.last_name);
        } else if (item.chat != null) {
            name = item.chat.title;
        }

        if (name.toLowerCase().contains(query)) {
            return true;
        }

        // Search in username (prefer public username, fallback to local fields)
        String username = "";
        if (item.user != null) {
            username = UserObject.getPublicUsername(item.user);
            if (TextUtils.isEmpty(username)) {
                username = UserObject.getUserName(item.user);
            }
        } else if (item.chat != null) {
            username = ChatObject.getPublicUsername(item.chat);
            if (TextUtils.isEmpty(username)) {
                username = item.chat.title;
            }
        }

        if (!TextUtils.isEmpty(username) && username.toLowerCase().contains(query)) {
            return true;
        }

        return false;
    }

    private boolean shouldShowAccountSwitch() {
        // Don't show if only one account
        if (UserConfig.getActivatedAccountsCount() <= 1) {
            return false;
        }

        // Check if any accounts are hidden by passcode
        // If all other accounts are hidden, don't show the switch button
        int visibleAccounts = 0;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.getInstance(i).isClientActivated() && !PasscodeHelper.isAccountHidden(i)) {
                visibleAccounts++;
            }
        }

        return visibleAccounts > 1;
    }

    private void showOptionsMenu() {
        // Create dialog menu items
        ArrayList<String> items = new ArrayList<>();
        ArrayList<Integer> icons = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();

        // Add account switch option (only if multiple accounts and not hidden by passcode)
        if (shouldShowAccountSwitch()) {
            items.add(getString(R.string.SwitchAccountNax));
            icons.add(R.drawable.left_status_profile);
            actions.add(() -> showAccountSwitchDialog());
        }

        // Add clear history option
        items.add(getString(R.string.ClearRecentChats));
        icons.add(R.drawable.msg_delete);
        actions.add(() -> showClearHistoryDialog());

        // Create and show dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.Settings));
        
        // Convert to arrays for AlertDialog
        String[] itemsArray = items.toArray(new String[0]);
        int[] iconsArray = new int[icons.size()];
        for (int i = 0; i < icons.size(); i++) {
            iconsArray[i] = icons.get(i);
        }
        
        builder.setItems(itemsArray, iconsArray, (dialog, which) -> {
            if (which >= 0 && which < actions.size()) {
                actions.get(which).run();
            }
        });
        
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showAccountSwitchDialog() {
        if (!shouldShowAccountSwitch()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.SwitchAccountNax));

        ArrayList<String> accounts = new ArrayList<>();
        ArrayList<Integer> accountIds = new ArrayList<>();

        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.getInstance(i).isClientActivated() && !PasscodeHelper.isAccountHidden(i)) {
                TLRPC.User user = UserConfig.getInstance(i).getCurrentUser();
                if (user != null) {
                    String name = ContactsController.formatName(user.first_name, user.last_name);
                    if (i == currentAccount) {
                        name += " (" + getString(R.string.CurrentNax) + ")";
                    }
                    accounts.add(name);
                    accountIds.add(i);
                }
            }
        }

        builder.setItems(accounts.toArray(new String[0]), (dialog, which) -> {
            int selectedAccount = accountIds.get(which);
            if (selectedAccount != currentAccount) {
                switchToAccount(selectedAccount);
            }
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void switchToAccount(int accountId) {
        currentAccount = accountId;
        
        // Clear saved state when switching accounts
        clearSavedState();
        
        updateTitle();
        loadHistoryItems();
        refreshAllPages();
    }

    private void showClearHistoryDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.ClearRecentChats));
        builder.setMessage(getString(R.string.ClearRecentChatAlert));

        builder.setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
            clearHistory();
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void clearHistory() {
        // Clear recent dialogs directly (no reflection needed)
        BackButtonMenuRecent.clearRecentDialogs(currentAccount);

        // Clear saved state
        clearSavedState();

        // Immediately refresh the interface
        loadHistoryItems();
        refreshAllPages();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, getString(R.string.ClearRecentChats)).show();
    }

    /**
     * Clear saved search state
     */
    private void clearSavedState() {
        savedSearchMode = false;
        savedSearchQuery = "";
        isOpeningChat = false;
        savedScrollState = null;
        savedScrollTab = -1;
    }

    /**
     * Save current scroll position
     */
    private void saveScrollPosition() {
        if (viewPager == null) return;
        View v = viewPager.getCurrentView();
        if (!(v instanceof FrameLayout)) return;
        
        FrameLayout container = (FrameLayout) v;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof RecyclerView) {
                RecyclerView.LayoutManager lm = ((RecyclerView) child).getLayoutManager();
                if (lm != null) {
                    savedScrollState = lm.onSaveInstanceState();
                    savedScrollTab = viewPager.getCurrentPosition();
                }
                break;
            }
        }
    }

    /**
     * Restore scroll position if still on the same tab
     */
    private void restoreScrollPosition() {
        if (viewPager == null || savedScrollState == null) return;
        if (savedScrollTab != viewPager.getCurrentPosition()) {
            savedScrollState = null;
            savedScrollTab = -1;
            return;
        }
        
        View v = viewPager.getCurrentView();
        if (!(v instanceof FrameLayout)) return;
        
        FrameLayout container = (FrameLayout) v;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof RecyclerView) {
                RecyclerView.LayoutManager lm = ((RecyclerView) child).getLayoutManager();
                if (lm != null) {
                    lm.onRestoreInstanceState(savedScrollState);
                }
                break;
            }
        }
        
        // Clear after restore
        savedScrollState = null;
        savedScrollTab = -1;
    }

    @Override
    public void onResume() {
        super.onResume();
        
        if (BuildVars.LOGS_ENABLED) Log.d(TAG, "onResume: isOpeningChat=" + isOpeningChat + ", hasBeenInitialized=" + hasBeenInitialized + ", savedSearchMode=" + savedSearchMode);
        
        // If returning from chat page
        if (isOpeningChat && hasBeenInitialized) {
            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Returning from chat");
            isOpeningChat = false; // Reset flag

            // Keep the current list snapshot when returning from a chat so
            // the user lands back at the same visual position they left.
             
            // If search mode was opened with empty query, exit search mode on return and close search UI
            if (isSearchMode && android.text.TextUtils.isEmpty(searchQuery)) {
                if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Exiting empty search mode on return");
                try {
                    if (actionBar != null && actionBar.isSearchFieldVisible()) {
                        actionBar.closeSearchField(false);
                    }
                } catch (Exception ignore) { }
                exitSearchMode();
            }
            
            restoreState(false);
            restoreScrollPosition();
            
            return; // Don't execute the general refresh logic below
        }
        
        // Reset flag
        isOpeningChat = false;
        
        if (hasBeenInitialized) {
            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "General resume, keep list state");
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (isMultiSelectMode) {
            exitMultiSelectMode();
            return false;
        }
        if (isSearchMode) {
            try {
                if (actionBar != null && actionBar.isSearchFieldVisible()) {
                    actionBar.closeSearchField(false);
                }
            } catch (Exception ignore) { }
            exitSearchMode();
            return false;
        }
        return true; // Allow the back press to be handled by the system
    }

    private void refreshAllPages() {
        if (viewPager != null) {
            // Clear all cached views to prevent old content from showing
            clearViewPagerCache();

            // Force refresh all pages by recreating the adapter
            viewPager.setAdapter(new CategoryPagerAdapter());
            updateTabs();
        }
    }

    private void clearViewPagerCache() {
        if (viewPager != null) {
            try {
                // Force ViewPager to clear its view cache
                viewPager.removeAllViews();

                // Request layout to ensure proper refresh
                viewPager.requestLayout();

                // Small delay to ensure views are properly cleared
                viewPager.post(() -> {
                    if (viewPager != null) {
                        viewPager.invalidate();
                    }
                });
            } catch (Exception e) {
                // Ignore any exceptions during cache clearing
            }
        }
    }

    // ViewPager Adapter
    private class CategoryPagerAdapter extends ViewPagerFixed.Adapter {
        @Override
        public int getItemCount() {
            return ChatCategory.values().length;
        }

        @Override
        public String getItemTitle(int position) {
            return getTabTitle(ChatCategory.values()[position]);
        }

        @Override
        public View createView(int viewType) {
            Context context = getContext();
            if (context == null) return new View(getParentActivity());

            // Create a container to ensure proper isolation between pages
            FrameLayout container = new FrameLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    // Ensure container fills the entire available space
                    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
                }
            };
            container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            // Create RecyclerView for this category
            BlurredRecyclerView listView = new BlurredRecyclerView(context);
            listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            listView.setVerticalScrollBarEnabled(false);
            
            // Configure ItemAnimator for click animations
            DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
            itemAnimator.setChangeDuration(350);
            itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            itemAnimator.setDelayAnimations(false);
            itemAnimator.setSupportsChangeAnimations(false);
            listView.setItemAnimator(itemAnimator);

            // Add RecyclerView to container
            container.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));

            return container;
        }

        @Override
        public void bindView(View view, int position, int viewType) {
            if (view instanceof FrameLayout) {
                FrameLayout container = (FrameLayout) view;

                // Find the RecyclerView inside the container
                BlurredRecyclerView listView = null;
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child instanceof BlurredRecyclerView) {
                        listView = (BlurredRecyclerView) child;
                        break;
                    }
                }

                if (listView != null) {
                    // Clear any existing adapter to prevent data mixing
                    listView.setAdapter(null);

                    // Create fresh adapter with current data
                    CategoryListAdapter adapter = new CategoryListAdapter(getContext(), position);
                    listView.setAdapter(adapter);

                    // Set click listener
                    listView.setOnItemClickListener((itemView, itemPosition) -> {
                        adapter.onItemClick(itemView, itemPosition);
                    });
                    
                    // Set long click listener for multi-select mode
                    listView.setOnItemLongClickListener((itemView, itemPosition) -> {
                        if (itemPosition >= 0 && itemPosition < adapter.categoryItems.size()) {
                            if (!isMultiSelectMode) {
                                enterMultiSelectMode();
                            }
                            HistoryItem item = adapter.categoryItems.get(itemPosition);
                            HistoryCell cell = (HistoryCell) itemView;
                            toggleItemSelection(item, cell);
                            return true;
                        }
                        return false;
                    });
                }
            }
        }
    }

    // Category List Adapter
    private class CategoryListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;
        private ChatCategory category;
        private ArrayList<HistoryItem> categoryItems = new ArrayList<>();

        public CategoryListAdapter(Context context, int categoryIndex) {
            mContext = context;
            category = ChatCategory.values()[categoryIndex];
            updateCategoryData();
        }

        private void updateCategoryData() {
            categoryItems.clear();

            // Use filtered data in search mode, otherwise use all data
            ArrayList<HistoryItem> sourceItems = isSearchMode ? filteredHistoryItems : allHistoryItems;

            // Ensure we're working with the latest data
            if (sourceItems == null || sourceItems.isEmpty()) {
                if (BuildVars.LOGS_ENABLED) Log.d(TAG, "No data available for " + category.name() + " category");
                return;
            }

            for (HistoryItem item : sourceItems) {
                if (shouldIncludeItem(item, category)) {
                    categoryItems.add(item);
                }
            }

            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Updated " + category.name() + " category: " + categoryItems.size() + " items from " + sourceItems.size() + " total" + (isSearchMode ? " (search mode)" : ""));
        }

        public void onItemClick(View view, int position) {
            if (position >= 0 && position < categoryItems.size()) {
                HistoryItem item = categoryItems.get(position);
                if (isMultiSelectMode) {
                    HistoryCell cell = (HistoryCell) view;
                    toggleItemSelection(item, cell);
                } else {
                    openChat(item);
                }
            }
        }

        @Override
        public int getItemCount() {
            return categoryItems.isEmpty() ? 1 : categoryItems.size(); // Show empty state if no items
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = getItemViewType(position);

            if (viewType == 1) { // Empty state
                if (holder.itemView instanceof EmptyStateCell) {
                    EmptyStateCell emptyStateCell = (EmptyStateCell) holder.itemView;

                    if (isSearchMode) {
                        // In search mode, show search results
                        if (TextUtils.isEmpty(searchQuery)) {
                            emptyStateCell.setText("", getString(R.string.ChatHistory_EnterSearchQuery));
                        } else {
                            emptyStateCell.setText("", LocaleController.formatString(R.string.ChatHistory_NoResultsFor, searchQuery));
                        }
                    } else if (category == ChatCategory.ALL) {
                        // For ALL category, show "Recent Chats Empty"
                        emptyStateCell.setText("", getString(R.string.ChatHistory_NoRecentChats));
                    } else {
                        // For specific categories, show "No xx found" (no title)
                        String categoryDisplayName = getCategoryDisplayName(category);
                        emptyStateCell.setText("", LocaleController.formatString(R.string.ChatHistory_NoCategoryFound, categoryDisplayName));
                    }
                }
            } else { // History item
                if (holder.itemView instanceof HistoryCell && position >= 0 && position < categoryItems.size()) {
                    HistoryCell historyCell = (HistoryCell) holder.itemView;
                    HistoryItem item = categoryItems.get(position);
                    historyCell.setDialog(item);
                    
                    // Set multi-select mode and selection state
                    historyCell.setMultiSelectMode(isMultiSelectMode);
                    historyCell.setSelected(selectedItems.contains(item));
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return !categoryItems.isEmpty();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 1) {
                view = new EmptyStateCell(mContext);
            } else {
                view = new HistoryCell(mContext);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public int getItemViewType(int position) {
            return categoryItems.isEmpty() ? 1 : 0; // 0 = history item, 1 = empty state
        }
    }

    private String getCategoryDisplayName(ChatCategory category) {
        return ChatHistoryUtils.getCategoryDisplayName(category.id);
    }
    
    public void openChat(HistoryItem item) {
        if (item == null || (item.user == null && item.chat == null)) {
            return;
        }

        // If user is in search mode with empty query, close search UI and exit search before navigating
        if (isSearchMode && TextUtils.isEmpty(searchQuery)) {
            try {
                if (actionBar != null && actionBar.isSearchFieldVisible()) {
                    actionBar.closeSearchField(false);
                }
            } catch (Exception ignore) { }
            exitSearchMode();
        }

        isOpeningChat = true;
        saveScrollPosition();
        saveState();

        // Check if we're viewing the current user's own account
        boolean isViewingOwnAccount = (currentAccount == UserConfig.selectedAccount);

        // If viewing own account, always use internal ChatActivity
        if (isViewingOwnAccount) {
            Bundle args = new Bundle();
            if (item.dialogId < 0) {
                args.putLong("chat_id", -item.dialogId);
                presentFragment(new ChatActivity(args), false, false);
            } else {
                args.putLong("user_id", item.dialogId);
                presentFragment(new ChatActivity(args), false, false);
            }
            return;
        }

        String publicUsername = null;
        if (item.user != null) {
            publicUsername = UserObject.getPublicUsername(item.user);
        } else if (item.chat != null) {
            publicUsername = ChatObject.getPublicUsername(item.chat);
        }

        if (!TextUtils.isEmpty(publicUsername)) {
            MessagesController.getInstance(UserConfig.selectedAccount).openByUserName(publicUsername, this, 1);
        } else {
            // Check if this private chat exists in current account
            if (chatExistsInCurrentAccount(item)) {
                // Open directly if exists in current account
                Bundle args = new Bundle();
                if (item.dialogId < 0) {
                    args.putLong("chat_id", -item.dialogId);
                    presentFragment(new ChatActivity(args), false, false);
                } else {
                    args.putLong("user_id", item.dialogId);
                    presentFragment(new ChatActivity(args), false, false);
                }
            } else {
                // Show dialog for private chats when viewing other accounts
                showPrivateChatDialog(item);
            }
        }
    }

    public static ArrayList<HistoryItem> loadRecentHistoryItems(int account) {
        ArrayList<HistoryItem> items = new ArrayList<>();
        
        // Get recent dialogs directly (no reflection needed)
        LinkedList<Long> recentDialogIds = BackButtonMenuRecent.getRecentDialogs(account);
        
        for (Long dialogId : recentDialogIds) {
            if (isOfficialDialog(dialogId, account)) {
                continue;
            }
            HistoryItem item = createHistoryItem(dialogId, account);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private boolean chatExistsInCurrentAccount(HistoryItem item) {
        int selectedAccount = UserConfig.selectedAccount;

        if (item.dialogId > 0) {
            // User dialog - check if user exists in current account
            TLRPC.User user = MessagesController.getInstance(selectedAccount).getUser(item.dialogId);
            if (user == null) {
                user = loadUserFromDatabase(item.dialogId, selectedAccount);
            }
            return user != null;
        } else {
            // Chat dialog - check if chat exists in current account
            long chatId = -item.dialogId;
            TLRPC.Chat chat = MessagesController.getInstance(selectedAccount).getChat(chatId);
            if (chat == null) {
                chat = loadChatFromDatabase(chatId, selectedAccount);
            }
            return chat != null;
        }
    }

    private void showPrivateChatDialog(HistoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.AppName));

        String chatName = "";
        if (item.user != null) {
            chatName = ContactsController.formatName(item.user.first_name, item.user.last_name);
        } else if (item.chat != null) {
            chatName = item.chat.title;
        }

        builder.setMessage(LocaleController.formatString("PrivateChatMessage", R.string.PrivateChatMessage, chatName));

        builder.setPositiveButton(getString(R.string.OK), null);
        showDialog(builder.create());
    }

    private void showChatOptionsMenu(HistoryItem item, View anchorView) {
        // Determine available options
        boolean hasPublicUsername = false;
        String username = null;
        String displayName = null;
        
        if (item.user != null) {
            username = UserObject.getPublicUsername(item.user);
            displayName = UserObject.getUserName(item.user);
            hasPublicUsername = !TextUtils.isEmpty(username);
        } else if (item.chat != null) {
            username = ChatObject.getPublicUsername(item.chat);
            displayName = item.chat.title;
            hasPublicUsername = !TextUtils.isEmpty(username);
        }

        boolean isViewingOwnAccount = (currentAccount == UserConfig.selectedAccount);
        boolean canOpen = isViewingOwnAccount || hasPublicUsername || (!isViewingOwnAccount && chatExistsInCurrentAccount(item));

        // Create final copies for lambda
        final String finalUsername = username;
        final String finalDisplayName = displayName;
        final boolean finalHasPublicUsername = hasPublicUsername;
        final boolean finalCanOpen = canOpen;

        // Create popup layout
        ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(getContext());
        popupLayout.setFitItems(true);

        // Create and show popup window
        ActionBarPopupWindow popupWindow = new ActionBarPopupWindow(popupLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);

        // Add Open option
        ActionBarMenuSubItem openItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_openin, getString(R.string.Open), false, getResourceProvider());
        openItem.setVisibility(finalCanOpen);
        if (!finalCanOpen) {
            openItem.setColors(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        }
        openItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (finalCanOpen) {
                openChat(item);
            }
        });

        // Add Share option
        ActionBarMenuSubItem shareItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_share, getString(R.string.ShareFile), false, getResourceProvider());
        shareItem.setVisibility(finalHasPublicUsername);
        if (!finalHasPublicUsername) {
            shareItem.setColors(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        }
        shareItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (finalHasPublicUsername) {
                shareChat(finalUsername, item);
            }
        });

        // Add Copy option
        ActionBarMenuSubItem copyItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_copy, getString(R.string.Copy), false, getResourceProvider());
        copyItem.setVisibility(finalHasPublicUsername);
        if (!finalHasPublicUsername) {
            copyItem.setColors(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        }
        copyItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (finalHasPublicUsername) {
                copyUsername(finalUsername);
            }
        });

        // Add Delete option (always available)
        ActionBarMenuSubItem deleteItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_delete, getString(R.string.Delete), false, getResourceProvider());
        deleteItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            showDeleteChatDialog(item);
        });
        popupWindow.setPauseNotifications(true);
        popupWindow.setDismissAnimationDuration(220);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setClippingEnabled(true);
        popupWindow.setAnimationStyle(R.style.PopupContextAnimation);
        popupWindow.setFocusable(true);
        popupLayout.measure(View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST),
                           View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST));
        popupWindow.setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        popupWindow.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        popupWindow.getContentView().setFocusableInTouchMode(true);

        // Calculate position
        int[] location = new int[2];
        anchorView.getLocationInWindow(location);
        int popupX = location[0] + anchorView.getWidth() - popupLayout.getMeasuredWidth();
        int popupY = location[1];

        popupWindow.showAtLocation(anchorView, android.view.Gravity.LEFT | android.view.Gravity.TOP, popupX, popupY);
        popupWindow.dimBehind();
    }

    private void shareChat(String username, HistoryItem item) {
        try {
            String shareText = "@" + username;
            ShareAlert shareAlert = ShareAlert.createShareAlert(getContext(), null, shareText, false, shareText, false);
            shareAlert.setDelegate(new ShareAlert.ShareAlertDelegate() {
                @Override
                public void didShare() {
                    int shareCount = shareAlert.getSelectedDialogsCount();
                    
                    if (shareCount > 0) {
                        CharSequence bulletinText = AndroidUtilities.replaceTags(LocaleController.formatPluralString("ChatHistory_LinkSharedToChat", shareCount, shareCount));
                        int duration = shareCount > 1 ? org.telegram.ui.Components.Bulletin.DURATION_PROLONG : org.telegram.ui.Components.Bulletin.DURATION_SHORT;
                        shareAlert.setOnDismissListener(() -> AndroidUtilities.runOnUIThread(() ->
                                BulletinFactory.of(ChatHistoryActivity.this).createSimpleBulletin(
                                        R.raw.forward,
                                        bulletinText
                                ).hideAfterBottomSheet(false).ignoreDetach().setDuration(duration).show()
                        ));
                    }
                }
            });
            showDialog(shareAlert);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void copyUsername(String username) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("username", "@" + username);
            clipboard.setPrimaryClip(clip);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.copy,
                getString(R.string.TextCopied)).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showDeleteChatDialog(HistoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.DeleteChatTitle));

        String chatName = "";
        if (item.user != null) {
            chatName = ContactsController.formatName(item.user.first_name, item.user.last_name);
        } else if (item.chat != null) {
            chatName = item.chat.title;
        }

        builder.setMessage(LocaleController.formatString("DeleteChatMessage", R.string.DeleteChatMessage, chatName));

        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            deleteChatFromHistory(item);
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void deleteChatFromHistory(HistoryItem item) {
        deleteChatFromHistory(item, true);
    }

    private void deleteChatFromHistory(HistoryItem item, boolean refreshUI) {
        // Get recent dialogs directly (no reflection needed)
        LinkedList<Long> recentDialogIds = BackButtonMenuRecent.getRecentDialogs(currentAccount);

        // Remove the dialog from the list
        recentDialogIds.remove(item.dialogId);

        // Save the updated list directly (no reflection needed)
        BackButtonMenuRecent.saveRecentDialogs(currentAccount, recentDialogIds);

        if (refreshUI) {
            // Refresh the interface
            loadHistoryItems();
            refreshAllPages();

            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
                getString(R.string.ChatRemovedFromRecent)).show();
        }
    }


    // Data classes
    public static class HistoryItem {
        long dialogId;
        TLRPC.Chat chat;
        TLRPC.User user;
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            HistoryItem that = (HistoryItem) obj;
            return dialogId == that.dialogId;
        }
        
        @Override
        public int hashCode() {
            return Long.hashCode(dialogId);
        }
    }

    // Custom cells
    private class HistoryCell extends FrameLayout {
        private BackupImageView avatarImageView;
        private TextView nameTextView;
        private TextView usernameTextView;
        private AvatarDrawable avatarDrawable;
        private ActionBarMenuItem optionsButton;
        private CheckBox checkBox;
        private CheckBox2 checkBox2;
        private HistoryItem currentItem;
        private boolean isSelected = false;

        public HistoryCell(Context context) {
            super(context);

            avatarDrawable = new AvatarDrawable();
            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(AndroidUtilities.dp(25));
            addView(avatarImageView, LayoutHelper.createFrame(50, 50, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            // Add CheckBox for multi-select mode (kept for compatibility)
            checkBox = new CheckBox(context);
            checkBox.setVisibility(GONE);
            checkBox.setClickable(false);
            checkBox.setFocusable(false);
            addView(checkBox, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 60, 0, 0, 0));

            // Add CheckBox2 like in DialogCell
            checkBox2 = new CheckBox2(context, 21, null) {
                @Override
                public void invalidate() {
                    super.invalidate();
                    HistoryCell.this.invalidate();
                }
                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                }
            };
            checkBox2.setVisibility(GONE);
            checkBox2.setColor(-1, Theme.key_windowBackgroundWhite, Theme.key_checkboxCheck);
            checkBox2.setDrawUnchecked(false);
            checkBox2.setDrawBackgroundAsArc(3);
            addView(checkBox2, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));

            nameTextView = new TextView(context);
            nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameTextView.setTextSize(16);
            nameTextView.setLines(1);
            nameTextView.setMaxLines(1);
            nameTextView.setSingleLine(true);
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            nameTextView.setGravity(Gravity.LEFT);
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 82, 16, 64, 0));

            usernameTextView = new TextView(context);
            usernameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            usernameTextView.setTextSize(14);
            usernameTextView.setLines(1);
            usernameTextView.setMaxLines(1);
            usernameTextView.setSingleLine(true);
            usernameTextView.setEllipsize(TextUtils.TruncateAt.END);
            usernameTextView.setGravity(Gravity.LEFT);
            addView(usernameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 82, 38, 64, 0));

            // Add options button (three dots)
            optionsButton = new ActionBarMenuItem(context, null, 0, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            optionsButton.setIcon(R.drawable.ic_ab_other);
            optionsButton.setBackgroundDrawable(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
            optionsButton.setOnClickListener(v -> {
                if (currentItem != null) {
                    showChatOptionsMenu(currentItem, v);
                }
            });
            addView(optionsButton, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72), MeasureSpec.EXACTLY));
        }
        
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (checkBox2 != null) {
                int avatarLeft = AndroidUtilities.dp(16);
                int avatarTop = (getMeasuredHeight() - AndroidUtilities.dp(50)) / 2;
                int avatarSize = AndroidUtilities.dp(50);
                
                int checkBoxSize = AndroidUtilities.dp(24);
                int x = avatarLeft + avatarSize - checkBoxSize + AndroidUtilities.dp(8);
                int y = avatarTop + avatarSize - checkBoxSize + AndroidUtilities.dp(8);
                
                checkBox2.layout(x, y, x + checkBox2.getMeasuredWidth(), y + checkBox2.getMeasuredHeight());
            }
        }
        
        public void setMultiSelectMode(boolean multiSelectMode) {
            if (multiSelectMode) {
                // Keep options button visible in multi-select mode
                optionsButton.setVisibility(VISIBLE);
                // Clear any pending animation delegate from previous use
                checkBox2.setProgressDelegate(null);
                // Cancel any ongoing animation
                checkBox2.getCheckBoxBase().cancelCheckAnimator();
                // Reset checkbox to unchecked state without animation
                checkBox2.setChecked(false, false);
                // IMPORTANT: Force progress to 0 even if setChecked returned early
                // This handles the case where isChecked was already false (so setChecked returns early)
                // but progress was non-zero from cancelled animation or recycled cell
                if (checkBox2.getCheckBoxBase().getProgress() != 0) {
                    checkBox2.getCheckBoxBase().setProgress(0);
                }
                checkBox2.setVisibility(VISIBLE);
                // Note: Don't set checkbox state here - let setSelected() handle it
                // since setSelected() is called right after this in onBindViewHolder
                // with the correct selection state
            } else {
                // Keep checkbox visible during deselection animation
                optionsButton.setVisibility(VISIBLE);
                // Animate unchecked, then hide after animation completes
                setSelected(false, true);
            }
        }

        public void setSelected(boolean selected) {
            setSelected(selected, false);
        }

        public void setSelected(boolean selected, boolean hideAfterAnimation) {
            // Always update the checkbox state when in multi-select mode or when visibility changes
            // Don't skip update even if isSelected == selected, because the checkbox visual state
            // might be out of sync after cell recycling
            boolean wasSelected = isSelected;
            isSelected = selected;
            
            // Only animate if CheckBox2 is visible and state actually changed
            boolean shouldAnimate = checkBox2.getVisibility() == VISIBLE && wasSelected != selected;
            
            if (hideAfterAnimation && !selected) {
                if (checkBox2.getVisibility() == VISIBLE) {
                    // Set progress delegate to hide checkbox after animation completes
                    checkBox2.setProgressDelegate(progress -> {
                        if (progress == 0) {
                            checkBox2.setVisibility(GONE);
                            checkBox2.setProgressDelegate(null);
                        }
                    });
                    checkBox2.setChecked(false, true);
                } else {
                    checkBox2.setVisibility(GONE);
                }
                return;
            }
            
            // Update checkbox state if visible
            if (checkBox2.getVisibility() == VISIBLE) {
                checkBox2.setChecked(selected, shouldAnimate);
                // IMPORTANT: Force correct progress value in case setChecked returned early
                // This handles cases where CheckBoxBase.isChecked already matched 'selected'
                // but progress was out of sync (e.g., from cancelled animation or recycling)
                float expectedProgress = selected ? 1.0f : 0.0f;
                if (!shouldAnimate && checkBox2.getCheckBoxBase().getProgress() != expectedProgress) {
                    checkBox2.getCheckBoxBase().setProgress(expectedProgress);
                }
            }
        }

        public boolean isSelected() {
            return isSelected;
        }

        public void setDialog(HistoryItem item) {
            this.currentItem = item;
            
            // Reset selection state when binding new item to handle cell recycling
            // The actual selection state will be set by onBindViewHolder after this
            isSelected = false;

            if (item.user != null) {
                avatarDrawable.setInfo(item.user);
                avatarImageView.setForUserOrChat(item.user, avatarDrawable);
                nameTextView.setText(UserObject.getUserName(item.user));

                String username = UserObject.getPublicUsername(item.user);
                if (!TextUtils.isEmpty(username)) {
                    usernameTextView.setText("@" + username);
                    usernameTextView.setVisibility(VISIBLE);
                } else {
                    // Show user ID when no public username is available
                    usernameTextView.setText("ID: " + item.user.id);
                    usernameTextView.setVisibility(VISIBLE);
                }
            } else if (item.chat != null) {
                avatarDrawable.setInfo(item.chat);
                avatarImageView.setForUserOrChat(item.chat, avatarDrawable);
                nameTextView.setText(item.chat.title);

                String username = ChatObject.getPublicUsername(item.chat);
                if (!TextUtils.isEmpty(username)) {
                    usernameTextView.setText("@" + username);
                    usernameTextView.setVisibility(VISIBLE);
                } else {
                    // Show private group/channel indicator for chats without public username
                    if (item.chat.broadcast) {
                        usernameTextView.setText(LocaleController.getString("ChannelPrivate", R.string.ChannelPrivate));
                    } else {
                        usernameTextView.setText(LocaleController.getString("MegaPrivate", R.string.MegaPrivate));
                    }
                    usernameTextView.setVisibility(VISIBLE);
                }
            }
        }
        

    }

    private class EmptyStateCell extends FrameLayout {
        private TextView titleTextView;
        private TextView descriptionTextView;

        public EmptyStateCell(Context context) {
            super(context);

            titleTextView = new TextView(context);
            titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            titleTextView.setTextSize(17);
            titleTextView.setGravity(Gravity.CENTER);
            addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 48, 32, 0));

            descriptionTextView = new TextView(context);
            descriptionTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            descriptionTextView.setTextSize(15);
            descriptionTextView.setGravity(Gravity.CENTER);
            addView(descriptionTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 80, 32, 48));

            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Use a reasonable height for empty state, container will handle the full coverage
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(200), MeasureSpec.EXACTLY)
            );
        }

        public void setText(String title, String description) {
            if (TextUtils.isEmpty(title)) {
                titleTextView.setVisibility(GONE);
            } else {
                titleTextView.setText(title);
                titleTextView.setVisibility(VISIBLE);
            }

            if (TextUtils.isEmpty(description)) {
                descriptionTextView.setVisibility(GONE);
            } else {
                descriptionTextView.setText(description);
                descriptionTextView.setVisibility(VISIBLE);
            }
        }
    }

    // Multi-select mode methods
    private void enterMultiSelectMode() {
        // Save current search state before entering multi-select mode
        savedSearchMode = isSearchMode;
        savedSearchQuery = searchQuery;
        
        // If currently in search mode, close search field first to avoid ActionBar conflicts
        if (isSearchMode && actionBar != null && actionBar.isSearchFieldVisible()) {
            // Set flag to prevent exitSearchMode during search field closure
            isRestoringSearchState = true;
            actionBar.closeSearchField();
            // Reset flag after a short delay
            AndroidUtilities.runOnUIThread(() -> {
                isRestoringSearchState = false;
            }, 100);
        }
        
        isMultiSelectMode = true;
        selectedItems.clear();
        updateActionBarForMultiSelect();
        updateAllCellsMultiSelectMode();
    }

    private void exitMultiSelectMode() {
        isMultiSelectMode = false;
        selectedItems.clear();
        
        // Check if we need to restore search state
        boolean shouldRestoreSearch = savedSearchMode && !TextUtils.isEmpty(savedSearchQuery);
        
        updateActionBarForNormalMode();
        updateAllCellsMultiSelectMode();
        
        // Restore search state if it was active before multi-select mode
        if (shouldRestoreSearch) {
            AndroidUtilities.runOnUIThread(() -> {
                if (searchItem != null && actionBar != null) {
                    // Set flag to prevent exitSearchMode during restoration
                    isRestoringSearchState = true;
                    
                    // Set search mode and query first
                    isSearchMode = true;
                    searchQuery = savedSearchQuery;
                    
                    // Open search field with the saved query and make it visible
                    actionBar.openSearchField(savedSearchQuery, false);
                    
                    // Ensure the search field is properly displayed
                    if (searchItem.getSearchField() != null) {
                        searchItem.getSearchField().setText(savedSearchQuery);
                        searchItem.getSearchField().setSelection(savedSearchQuery.length());
                    }
                    
                    // Update title to reflect search mode
                    updateTitle();
                    
                    // Perform search to restore results
                    performSearch(savedSearchQuery);
                    
                    // Reset flag after restoration is complete
                    AndroidUtilities.runOnUIThread(() -> {
                        isRestoringSearchState = false;
                    }, 100);
                }
            }, 200);
        }
    }

    private void toggleItemSelection(HistoryItem item, HistoryCell cell) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item);
            cell.setSelected(false);
        } else {
            selectedItems.add(item);
            cell.setSelected(true);
        }
        updateActionBarTitle();
        
        // Exit multi-select mode if no items selected
        if (selectedItems.isEmpty()) {
            exitMultiSelectMode();
        }
    }

    private void updateActionBarForMultiSelect() {
        if (actionBar != null) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        exitMultiSelectMode();
                    } else if (id == 1) {
                        showDeleteSelectedDialog();
                    }
                }
            });
            
            ActionBarMenu menu = actionBar.createMenu();
            menu.clearItems();
            deleteItem = menu.addItem(1, R.drawable.msg_delete);
            updateActionBarTitle();
        }
    }

    private void updateActionBarForNormalMode() {
        if (actionBar != null) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    } else if (id == 1) {
                        presentFragment(new ChatHistorySearchActivity());
                    } else if (id == 2) {
                        showOptionsMenu();
                    }
                }
            });
            
            ActionBarMenu menu = actionBar.createMenu();
            menu.clearItems();
            searchItem = menu.addItem(1, R.drawable.ic_ab_search);
            searchItem.setOnClickListener(v -> presentFragment(new ChatHistorySearchActivity()));
            ActionBarMenuItem settingsItem = menu.addItem(2, R.drawable.msg_settings);
            settingsItem.setLongClickEnabled(false);
            updateTitle();
        }
    }

    private void updateActionBarTitle() {
        if (actionBar != null) {
            if (isMultiSelectMode) {
                actionBar.setTitle(selectedItems.size() + " " + getString(R.string.ChatHistorySelected));
            } else {
                updateTitle();
            }
        }
    }

    private void updateAllCellsMultiSelectMode() {
        if (viewPager != null) {
            for (int i = 0; i < viewPager.getChildCount(); i++) {
                View child = viewPager.getChildAt(i);
                if (child instanceof FrameLayout) {
                    FrameLayout container = (FrameLayout) child;
                    for (int j = 0; j < container.getChildCount(); j++) {
                        View containerChild = container.getChildAt(j);
                        if (containerChild instanceof RecyclerListView) {
                            RecyclerListView recyclerView = (RecyclerListView) containerChild;
                            for (int k = 0; k < recyclerView.getChildCount(); k++) {
                                View itemView = recyclerView.getChildAt(k);
                                if (itemView instanceof HistoryCell) {
                                    HistoryCell cell = (HistoryCell) itemView;
                                    cell.setMultiSelectMode(isMultiSelectMode);
                                    if (!isMultiSelectMode) {
                                        cell.setSelected(false);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void showDeleteSelectedDialog() {
        if (selectedItems.isEmpty()) {
            return;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.ChatHistoryDeleteChats));
        builder.setMessage(getString(R.string.ChatHistoryDeleteConfirmation) + selectedItems.size() + getString(R.string.ChatHistorySelected));
        builder.setPositiveButton(getString(R.string.ChatHistoryDeleteChats), (dialog, which) -> {
            deleteSelectedChats();
        });
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void deleteSelectedChats() {
        int count = selectedItems.size();
        // Batch delete without refreshing UI for each item
        for (HistoryItem item : selectedItems) {
            deleteChatFromHistory(item, false);
        }
        // Refresh UI only once after all deletions
        loadHistoryItems();
        exitMultiSelectMode();
        refreshAllPages();
        
        // Show confirmation bulletin
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
            LocaleController.formatPluralString("ChatHistory_ChatsRemoved", count)).show();
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ThemeDescription.ThemeDescriptionDelegate cellDelegate = () -> {
            if (fragmentView != null) {
                fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            }
            if (tabsView != null) {
                tabsView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            // Refresh all pages in ViewPager
            if (viewPager != null) {
                viewPager.setAdapter(new CategoryPagerAdapter());
            }
        };

        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, cellDelegate, Theme.key_windowBackgroundGray));

        if (tabsView != null) {
            themeDescriptions.add(new ThemeDescription(tabsView, ThemeDescription.FLAG_BACKGROUND, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));
        }

        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        return themeDescriptions;
    }
}
