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

import android.os.Parcelable;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.UserCell;

/**
 * Utility class for ChatHistoryActivity and ChatHistorySearchActivity
 * Provides common methods to reduce code duplication.
 */
public final class ChatHistoryUtils {

    private ChatHistoryUtils() {
        // Prevent instantiation
    }

    /**
     * Represents display information for a chat item
     */
    public static class ChatDisplayInfo {
        public final String title;
        public final String subtitle;

        public ChatDisplayInfo(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /**
     * Gets display information for a HistoryItem
     */
    public static ChatDisplayInfo getChatDisplayInfo(ChatHistoryActivity.HistoryItem item) {
        if (item == null) {
            return new ChatDisplayInfo("", "");
        }

        String title;
        String subtitle;

        if (item.user != null) {
            title = UserObject.getUserName(item.user);
            String username = UserObject.getPublicUsername(item.user);
            if (!TextUtils.isEmpty(username)) {
                subtitle = "@" + username;
            } else {
                subtitle = "ID: " + item.user.id;
            }
        } else if (item.chat != null) {
            title = item.chat.title;
            String username = ChatObject.getPublicUsername(item.chat);
            if (!TextUtils.isEmpty(username)) {
                subtitle = "@" + username;
            } else if (ChatObject.isChannel(item.chat) && !item.chat.megagroup) {
                subtitle = LocaleController.getString(R.string.ChannelPrivate);
            } else {
                subtitle = LocaleController.getString(R.string.MegaPrivate);
            }
        } else {
            title = "";
            subtitle = "";
        }

        return new ChatDisplayInfo(title, subtitle);
    }

    /**
     * Binds data to a UserCell from a HistoryItem
     */
    public static void bindUserCell(UserCell cell, ChatHistoryActivity.HistoryItem item) {
        if (cell == null || item == null) {
            return;
        }

        ChatDisplayInfo info = getChatDisplayInfo(item);

        if (item.user != null) {
            cell.setData(item.user, null, info.title, info.subtitle, 0, false);
        } else if (item.chat != null) {
            cell.setData(item.chat, null, info.title, info.subtitle, 0, false);
        }
    }

    /**
     * Saves the current list position for a RecyclerView
     */
    public static void saveListPosition(
            RecyclerView listView,
            int tabIndex,
            SparseIntArray savedFirstVisible,
            SparseIntArray savedTopOffset,
            SparseArray<Parcelable> savedLayoutState) {
        
        if (listView == null) return;

        RecyclerView.LayoutManager lm = listView.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;

        LinearLayoutManager llm = (LinearLayoutManager) lm;
        int pos = llm.findFirstVisibleItemPosition();
        View first = llm.findViewByPosition(pos);
        int offset = first == null ? 0 : first.getTop() - listView.getPaddingTop();

        savedFirstVisible.put(tabIndex, pos);
        savedTopOffset.put(tabIndex, offset);

        try {
            Parcelable state = llm.onSaveInstanceState();
            if (state != null) {
                savedLayoutState.put(tabIndex, state);
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * Restores the list position for a RecyclerView
     */
    public static void restoreListPosition(
            RecyclerView listView,
            int tabIndex,
            SparseIntArray savedFirstVisible,
            SparseIntArray savedTopOffset,
            SparseArray<Parcelable> savedLayoutState) {
        
        if (listView == null) return;

        RecyclerView.LayoutManager lm = listView.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;

        LinearLayoutManager llm = (LinearLayoutManager) lm;
        Parcelable state = savedLayoutState.get(tabIndex);
        int pos = savedFirstVisible.get(tabIndex, -1);
        int offset = savedTopOffset.get(tabIndex, 0);

        if (state != null) {
            try {
                llm.onRestoreInstanceState(state);
                return;
            } catch (Exception ignore) {
            }
        }

        if (pos >= 0) {
            llm.scrollToPositionWithOffset(pos, offset);
        }
    }

    /**
     * Checks if a HistoryItem should be included in a specific category
     */
    public static boolean shouldIncludeInCategory(
            ChatHistoryActivity.HistoryItem item,
            int categoryIndex) {
        
        if (item == null) return false;

        // Category 0 = ALL
        if (categoryIndex == 0) {
            return true;
        }

        if (item.user != null) {
            // Category 3 = Users (non-bot), Category 4 = Bots
            if (item.user.bot) {
                return categoryIndex == 4;
            } else {
                return categoryIndex == 3;
            }
        } else if (item.chat != null) {
            // Category 1 = Channels, Category 2 = Groups
            if (item.chat.broadcast) {
                return categoryIndex == 1;
            } else {
                return categoryIndex == 2;
            }
        }

        return false;
    }

    /**
     * Category display name lookup array  
     */
    private static final int[] CATEGORY_STRING_IDS = {
        R.string.ChatCategoryAll,
        R.string.ChatCategoryChannels,
        R.string.ChatCategoryGroups,
        R.string.ChatCategoryUsers,
        R.string.ChatCategoryBots
    };

    /**
     * Gets the localized display name for a category index
     */
    public static String getCategoryDisplayName(int categoryIndex) {
        if (categoryIndex >= 0 && categoryIndex < CATEGORY_STRING_IDS.length) {
            return LocaleController.getString(CATEGORY_STRING_IDS[categoryIndex]);
        }
        return LocaleController.getString(R.string.ChatCategoryAll);
    }
}
