/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.folder

import android.view.ViewGroup
import android.view.View
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.Toast
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.FolderResizeFrame
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings
import com.android.launcher3.R
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.popup.PopupContainer
import com.android.launcher3.shortcuts.DeepShortcutView

/** Uses the launcher's own tonal action rows and deferred drag behavior. */
object FolderActionsPopup {
    @JvmStatic
    fun show(icon: FolderIcon): PopupContainer<Launcher> {
        val launcher = Launcher.getLauncher(icon.context)
        AbstractFloatingView.closeOpenViews(launcher, false, AbstractFloatingView.TYPE_ACTION_POPUP)
        val focus = FolderFocus(icon)
        focus.start()
        val resizeFrame = FolderResizeFrame.showForFolder(icon)
        val popup = object : PopupContainer<Launcher>(icon.context, icon, icon.mInfo) {
            private var resizing = false
            private var keepResizeFrame = false
            private var authenticatingResize = false

            override fun addChildrenForAccessibility(children: ArrayList<View>) {
                super.addChildrenForAccessibility(children)
                resizeFrame?.let { children.add(it.resizeHandle) }
            }

            override fun onControllerInterceptTouchEvent(event: MotionEvent): Boolean {
                if (resizeFrame?.onControllerInterceptTouchEvent(event) == true) {
                    if (icon.mInfo.isPrivacyLocked) {
                        authenticatingResize = true
                        close(false)
                        launcher.dragController.cancelDrag()
                        icon.authenticatePrivacy { FolderResizeFrame.showForFolder(icon) }
                    } else {
                        resizing = true
                        keepResizeFrame = true
                        close(false)
                        launcher.dragController.cancelDrag()
                    }
                    return true
                }
                return super.onControllerInterceptTouchEvent(event)
            }

            override fun onControllerTouchEvent(event: MotionEvent): Boolean {
                if (authenticatingResize) return true
                return if (resizing) resizeFrame?.onControllerTouchEvent(event) == true
                    else super.onControllerTouchEvent(event)
            }

            override fun handleClose(animate: Boolean) {
                if (!keepResizeFrame) resizeFrame?.close(false)
                focus.close(animate)
                super.handleClose(animate)
            }

            override fun onDragStart(
                drag: com.android.launcher3.DropTarget.DragObject,
                options: com.android.launcher3.dragndrop.DragOptions,
            ) {
                resizeFrame?.close(false)
                focus.close(false)
                super.onDragStart(drag, options)
            }

            override fun onDetachedFromWindow() {
                focus.close(false)
                super.onDetachedFromWindow()
            }
        }
        popup.id = R.id.popup_container
        popup.clipChildren = false
        popup.clipToPadding = false
        popup.orientation = LinearLayout.VERTICAL
        popup.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val rows: ViewGroup = popup.inflateAndAdd(R.layout.system_shortcut_rows_container, popup)
        popup.systemShortcutContainer = rows

        fun action(id: Int, label: Int, drawable: Int, run: () -> Unit) {
            val row: DeepShortcutView = popup.inflateAndAdd(R.layout.system_shortcut, rows)
            row.id = id
            row.iconView.setBackgroundResource(drawable)
            row.bubbleText.setText(label)
            row.setFilterTouchesWhenObscured(true)
            row.setOnClickListener {
                popup.close(false)
                launcher.dragController.cancelDrag()
                icon.post {
                    if (icon.isAttachedToWindow) run()
                }
            }
        }

        action(R.id.folder_action_open, R.string.folder_action_open, R.drawable.arkui_ic_folder) {
            icon.folder.animateOpen()
        }
        if (icon.mInfo.container == LauncherSettings.Favorites.CONTAINER_DESKTOP) {
            action(R.id.folder_action_edit, R.string.folder_action_edit, R.drawable.ic_setting) {
                if (icon.mInfo.isPrivacyLocked) {
                    icon.authenticatePrivacy { FolderEditorSheet.show(icon) }
                } else FolderEditorSheet.show(icon)
            }
        }
        action(
            R.id.folder_action_lock,
            if (icon.mInfo.isPrivacyLocked) R.string.folder_action_unlock else R.string.folder_action_lock,
            R.drawable.ic_protected_locked,
        ) {
            val locked = icon.mInfo.isPrivacyLocked
            // Enabling a lock also verifies (or creates) the password before saving the flag.
            icon.authenticatePrivacy {
                icon.mInfo.setOption(FolderInfo.FLAG_PRIVACY_LOCKED, !locked, launcher.modelWriter)
                icon.refreshFolderPresentation()
                Toast.makeText(
                    launcher,
                    if (locked) R.string.folder_lock_removed else R.string.folder_lock_enabled,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        launcher.dragController.addDragListener(popup)
        popup.show()
        return popup
    }
}
