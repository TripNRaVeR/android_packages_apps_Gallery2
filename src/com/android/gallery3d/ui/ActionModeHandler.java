/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gallery3d.ui;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.ActionMode.Callback;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.widget.ShareActionProvider;
import com.actionbarsherlock.widget.ShareActionProvider.OnShareTargetSelectedListener;
import com.android.gallery3d.R;
import com.android.gallery3d.app.AbstractGalleryActivity;
import com.android.gallery3d.common.ApiHelper;
import com.android.gallery3d.common.Utils;
import com.android.gallery3d.data.DataManager;
import com.android.gallery3d.data.MediaObject;
import com.android.gallery3d.data.Path;
import com.android.gallery3d.ui.MenuExecutor.ProgressListener;
import com.android.gallery3d.util.Future;
import com.android.gallery3d.util.GalleryUtils;
import com.android.gallery3d.util.ThreadPool.Job;
import com.android.gallery3d.util.ThreadPool.JobContext;

import java.util.ArrayList;

public class ActionModeHandler implements Callback, PopupList.OnPopupItemClickListener {

    @SuppressWarnings("unused")
    private static final String TAG = "ActionModeHandler";

    private static final int SUPPORT_MULTIPLE_MASK = MediaObject.SUPPORT_DELETE
            | MediaObject.SUPPORT_ROTATE | MediaObject.SUPPORT_SHARE
            | MediaObject.SUPPORT_CACHE | MediaObject.SUPPORT_IMPORT;

    public interface ActionModeListener {
        public boolean onActionItemClicked(MenuItem item);
    }

    private final AbstractGalleryActivity mActivity;
    private final MenuExecutor mMenuExecutor;
    private final SelectionManager mSelectionManager;
    private final NfcAdapter mNfcAdapter;
    private Menu mMenu;
    private MenuItem mShareMenuItem;
    private SelectionMenu mSelectionMenu;
    private ActionModeListener mListener;
    private Future<?> mMenuTask;
    private final Handler mMainHandler;
    private ActionMode mActionMode;

    public ActionModeHandler(
            AbstractGalleryActivity activity, SelectionManager selectionManager) {
        mActivity = Utils.checkNotNull(activity);
        mSelectionManager = Utils.checkNotNull(selectionManager);
        mMenuExecutor = new MenuExecutor(activity, selectionManager);
        mMainHandler = new Handler(activity.getMainLooper());
        mNfcAdapter = NfcAdapter.getDefaultAdapter(mActivity.getAndroidContext());
    }

    public void startActionMode() {
        SherlockActivity a = mActivity;
        mActionMode = a.startActionMode(this);
        View customView = LayoutInflater.from(a).inflate(
                R.layout.action_mode, null);
        mActionMode.setCustomView(customView);
        mSelectionMenu = new SelectionMenu(a,
                (Button) customView.findViewById(R.id.selection_menu), this);
        updateSelectionMenu();
    }

    public void finishActionMode() {
        mActionMode.finish();
    }

    public void setTitle(String title) {
        mSelectionMenu.setTitle(title);
    }

    public void setActionModeListener(ActionModeListener listener) {
        mListener = listener;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        GLRoot root = mActivity.getGLRoot();
        root.lockRenderThread();
        try {
            boolean result;
            // Give listener a chance to process this command before it's routed to
            // ActionModeHandler, which handles command only based on the action id.
            // Sometimes the listener may have more background information to handle
            // an action command.
            if (mListener != null) {
                result = mListener.onActionItemClicked(item);
                if (result) {
                    mSelectionManager.leaveSelectionMode();
                    return result;
                }
            }
            ProgressListener listener = null;
            String confirmMsg = null;
            int action = item.getItemId();
            if (action == R.id.action_import) {
                listener = new ImportCompleteListener(mActivity);
            } else if (item.getItemId() == R.id.action_delete) {
                confirmMsg = mActivity.getResources().getQuantityString(
                        R.plurals.delete_selection, mSelectionManager.getSelectedCount());
            }
            mMenuExecutor.onMenuClicked(item, confirmMsg, listener);
        } finally {
            root.unlockRenderThread();
        }
        return true;
    }

    @Override
    public boolean onPopupItemClick(int itemId) {
        GLRoot root = mActivity.getGLRoot();
        root.lockRenderThread();
        try {
            if (itemId == R.id.action_select_all) {
                updateSupportedOperation();
                mMenuExecutor.onMenuClicked(itemId, null, false, true);
            }
            return true;
        } finally {
            root.unlockRenderThread();
        }
    }

    private void updateSelectionMenu() {
        // update title
        int count = mSelectionManager.getSelectedCount();
        String format = mActivity.getResources().getQuantityString(
                R.plurals.number_of_items_selected, count);
        setTitle(String.format(format, count));

        // For clients who call SelectionManager.selectAll() directly, we need to ensure the
        // menu status is consistent with selection manager.
        mSelectionMenu.updateSelectAllMode(mSelectionManager.inSelectAllMode());
    }

    private final OnShareTargetSelectedListener mShareTargetSelectedListener =
            new OnShareTargetSelectedListener() {
        @Override
        public boolean onShareTargetSelected(ShareActionProvider source, Intent intent) {
            mSelectionManager.leaveSelectionMode();
            return false;
        }
    };

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.getMenuInflater().inflate(R.menu.operation, menu);

        mMenu = menu;
        mShareMenuItem = menu.findItem(R.id.action_share);
        if (mShareMenuItem != null) {
            ((ShareActionProvider) mShareMenuItem.getActionProvider())
                    .setOnShareTargetSelectedListener(mShareTargetSelectedListener);
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        mSelectionManager.leaveSelectionMode();
    }

    // Menu options are determined by selection set itself.
    // We cannot expand it because MenuExecuter executes it based on
    // the selection set instead of the expanded result.
    // e.g. LocalImage can be rotated but collections of them (LocalAlbum) can't.
    private int computeMenuOptions(JobContext jc) {
        ArrayList<Path> unexpandedPaths = mSelectionManager.getSelected(false);
        if (unexpandedPaths.isEmpty()) {
            // This happens when starting selection mode from overflow menu
            // (instead of long press a media object)
            return 0;
        }
        int operation = MediaObject.SUPPORT_ALL;
        DataManager manager = mActivity.getDataManager();
        int type = 0;
        for (Path path : unexpandedPaths) {
            if (jc.isCancelled()) return 0;
            int support = manager.getSupportedOperations(path);
            type |= manager.getMediaType(path);
            operation &= support;
        }

        switch (unexpandedPaths.size()) {
            case 1:
                final String mimeType = MenuExecutor.getMimeType(type, false);
                if (!GalleryUtils.isEditorAvailable(mActivity, mimeType)) {
                    operation &= ~MediaObject.SUPPORT_EDIT;
                }
                break;
            default:
                operation &= SUPPORT_MULTIPLE_MASK;
        }

        return operation;
    }

    @TargetApi(ApiHelper.VERSION_CODES.JELLY_BEAN)
    private void setNfcBeamPushUris(Uri[] uris) {
        if (mNfcAdapter != null && ApiHelper.HAS_SET_BEAM_PUSH_URIS) {
            mNfcAdapter.setBeamPushUris(uris, mActivity);
        }
    }

    // Share intent needs to expand the selection set so we can get URI of
    // each media item
    private Intent computeSharingIntent(JobContext jc) {
        ArrayList<Path> expandedPaths = mSelectionManager.getSelected(true);
        if (expandedPaths.size() == 0) {
            setNfcBeamPushUris(null);
            return null;
        }
        final ArrayList<Uri> uris = new ArrayList<Uri>();
        DataManager manager = mActivity.getDataManager();
        int type = 0;
        boolean isPanorama = true;
        final Intent intent = new Intent();
        for (Path path : expandedPaths) {
            if (jc.isCancelled()) return null;
            int support = manager.getSupportedOperations(path);
            type |= manager.getMediaType(path);

            if ((support & MediaObject.SUPPORT_SHARE) != 0) {
                uris.add(manager.getContentUri(path));
            }
            if ((support & MediaObject.SUPPORT_PANORAMA) == 0) {
                isPanorama = false;
            }
        }

        final int size = uris.size();
        if (size > 0) {
            final String mimeType = MenuExecutor.getMimeType(type, isPanorama);
            if (size > 1) {
                intent.setAction(Intent.ACTION_SEND_MULTIPLE).setType(mimeType);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            } else {
                intent.setAction(Intent.ACTION_SEND).setType(mimeType);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            }
            setNfcBeamPushUris(uris.toArray(new Uri[uris.size()]));
        } else {
            setNfcBeamPushUris(null);
        }

        return intent;
    }

    public void updateSupportedOperation(Path path, boolean selected) {
        // TODO: We need to improve the performance
        updateSupportedOperation();
    }

    public void updateSupportedOperation() {
        // Interrupt previous unfinished task, mMenuTask is only accessed in main thread
        if (mMenuTask != null) mMenuTask.cancel();

        updateSelectionMenu();

        // Disable share action until share intent is in good shape
        if (mShareMenuItem != null) mShareMenuItem.setEnabled(false);

        // Generate sharing intent and update supported operations in the background
        // The task can take a long time and be canceled in the mean time.
        mMenuTask = mActivity.getThreadPool().submit(new Job<Void>() {
            @Override
            public Void run(final JobContext jc) {
                // Pass1: Deal with unexpanded media object list for menu operation.
                final int operation = computeMenuOptions(jc);

                // Pass2: Deal with expanded media object list for sharing operation.
                final Intent intent = (mShareMenuItem != null) ? computeSharingIntent(jc) : null;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mMenuTask = null;
                        if (jc.isCancelled()) return;
                        MenuExecutor.updateMenuOperation(mMenu, operation);
                        if (mShareMenuItem != null) {
                            mShareMenuItem.setEnabled(true);
                            ((ShareActionProvider) mShareMenuItem.getActionProvider())
                                    .setShareIntent(intent);
                        }
                    }
                });
                return null;
            }
        });
    }

    public void pause() {
        if (mMenuTask != null) {
            mMenuTask.cancel();
            mMenuTask = null;
        }
        mMenuExecutor.pause();
    }

    public void resume() {
        if (mSelectionManager.inSelectionMode()) updateSupportedOperation();
    }
}
