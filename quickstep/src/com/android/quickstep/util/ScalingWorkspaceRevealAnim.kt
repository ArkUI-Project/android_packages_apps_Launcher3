/*
 * Copyright (C) 2024 The Android Open Source Project
 * Modified by the ArkUI Project in 2026 for continuous app/home motion and blur.
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

package com.android.quickstep.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import android.view.SurfaceControl
import android.view.View
import androidx.core.graphics.transform
import androidx.core.view.isVisible
import com.android.app.animation.Animations
import com.android.app.animation.Interpolators
import com.android.app.animation.Interpolators.EMPHASIZED
import com.android.app.animation.Interpolators.LINEAR
import com.android.launcher3.CellLayout
import com.android.launcher3.Flags
import com.android.launcher3.LauncherAnimUtils.HOTSEAT_SCALE_PROPERTY_FACTORY
import com.android.launcher3.LauncherAnimUtils.SCALE_INDEX_WORKSPACE_STATE
import com.android.launcher3.LauncherAnimUtils.VIEW_ALPHA
import com.android.launcher3.LauncherAnimUtils.WORKSPACE_SCALE_PROPERTY_FACTORY
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.anim.AnimatorListeners
import com.android.launcher3.anim.DesktopAnimationSettings
import com.android.launcher3.anim.PendingAnimation
import com.android.launcher3.anim.PropertySetter
import com.android.launcher3.statehandlers.DepthController
import com.android.launcher3.states.StateAnimationConfig
import com.android.launcher3.states.StateAnimationConfig.SKIP_DEPTH_CONTROLLER
import com.android.launcher3.states.StateAnimationConfig.SKIP_OVERVIEW
import com.android.launcher3.states.StateAnimationConfig.SKIP_SCRIM
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.quickstep.views.RecentsView

const val TAG = "ScalingWorkspaceRevealAnim"

/**
 * Creates an animation where the workspace and hotseat fade in while revealing from the center of
 * the screen outwards radially. This is used in conjunction with the swipe up to home animation.
 */
class ScalingWorkspaceRevealAnim(
    private val launcher: QuickstepLauncher,
    siblingAnimation: RectFSpringAnim?,
    windowTargetRect: RectF?,
    playAlphaReveal: Boolean = true,
    playBlur: Boolean = true,
) {
    companion object {
        private const val FADE_DURATION_MS = 200L
        private const val MAX_ALPHA = 1f
        private const val MIN_ALPHA = 0f
        internal const val MAX_SIZE = 1f
        internal const val MIN_SIZE = 0.85f

        /** Use the same settling curve for home content, wallpaper and the opening app. */
        @JvmField
        val SCALE_INTERPOLATOR = LandscapeAppAnimation.OPEN_INTERPOLATOR

        val BLUR_INTERPOLATOR = Interpolators.clampToProgress(EMPHASIZED, 0f, 0.666f)
    }

    private val motion = DesktopAnimationSettings.read(launcher)
    private val animation = PendingAnimation(motion.homeDuration)
    private var blurLayer: SurfaceControl? = null
    private var surfaceTransactionApplier: SurfaceTransactionApplier =
        SurfaceTransactionApplier(launcher.dragLayer)

    init {
        val windowControlsBlur = siblingAnimation != null
        var cancelled = false
        val workspace = launcher.workspace
        val hotseat = launcher.hotseat
        // The gesture may already expose the blurred home screen. Capture it before the
        // zero-duration state setup resets its properties, including on interruption.
        val homeWasVisible = workspace.alpha > 0f &&
            ((workspace.getPageAt(workspace.currentPage) as? CellLayout)
                ?.shortcutsAndWidgets?.alpha ?: 0f) > 0f
        val previousScale = workspace.scaleX
        val previousWorkspaceAlpha = workspace.alpha
        val previousHotseatAlpha = hotseat.alpha
        // Alpha can remain at one behind an opaque app. Only an active content animation
        // supplies a scale worth continuing; otherwise start from the app-opening zoom.
        val continuingScale =
            workspace.getTag(com.android.app.animation.R.id.ongoing_animation) is Animator
        // Make sure the starting state is right for the animation.
        val setupConfig = StateAnimationConfig()
        setupConfig.animFlags = SKIP_OVERVIEW.or(SKIP_DEPTH_CONTROLLER).or(SKIP_SCRIM)
        setupConfig.duration = 0
        launcher.stateManager
            .createAtomicAnimation(LauncherState.BACKGROUND_APP, LauncherState.NORMAL, setupConfig)
            .start()
        launcher
            .getOverviewPanel<RecentsView<QuickstepLauncher, LauncherState>>()
            .forceFinishScroller()
        launcher.workspace.stateTransitionAnimation.setScrim(
            PropertySetter.NO_ANIM_PROPERTY_SETTER,
            LauncherState.BACKGROUND_APP,
            setupConfig,
        )
        if (playBlur && !windowControlsBlur) {
            addBlurLayer()
        }

        // Interrupt the current animation, if any.
        Animations.cancelOngoingAnimation(workspace)
        Animations.cancelOngoingAnimation(hotseat)

        val fromSize =
            if (homeWasVisible && (!motion.isIos || continuingScale)) {
                previousScale
            } else {
                motion.workspaceScale
            }

        // Scale the Workspace and Hotseat around the same pivot.
        workspace.setPivotToScaleWithSelf(hotseat)
        animation.addFloat(
            workspace,
            WORKSPACE_SCALE_PROPERTY_FACTORY[SCALE_INDEX_WORKSPACE_STATE],
            fromSize,
            MAX_SIZE,
            motion.workspaceHomeInterpolator,
        )
        animation.addFloat(
            hotseat,
            HOTSEAT_SCALE_PROPERTY_FACTORY[SCALE_INDEX_WORKSPACE_STATE],
            fromSize,
            MAX_SIZE,
            motion.workspaceHomeInterpolator,
        )

        if (playAlphaReveal) {
            // Fade in quickly at the beginning of the animation, so the content doesn't look like
            // it's popping into existence out of nowhere.
            val fadeClamp = (FADE_DURATION_MS.toFloat() / motion.homeDuration).coerceAtMost(1f)
            // Preserve partially revealed content when a return animation is interrupted.
            workspace.alpha = if (homeWasVisible) previousWorkspaceAlpha else MIN_ALPHA
            animation.setFloat(
                workspace,
                VIEW_ALPHA,
                MAX_ALPHA,
                Interpolators.clampToProgress(LINEAR, 0f, fadeClamp),
            )
            hotseat.alpha = if (homeWasVisible) previousHotseatAlpha else MIN_ALPHA
            // This needs to use setViewAlpha instead of setFloat (like workspace).
            // This is because hotseat visibility can also be changed based off of alpha in
            // WorkspaceRevealAnim which also calls setViewAlpha.
            // b/428257480 Ideally we should be settings MultiValueAlpha with 2 channels instead.
            animation.setViewAlpha(
                hotseat,
                MAX_ALPHA,
                Interpolators.clampToProgress(LINEAR, 0f, fadeClamp),
            )
        }

        val transitionConfig = StateAnimationConfig()
        transitionConfig.duration = motion.homeDuration

        var depthController: DepthController? = null
        if (playBlur) {
            // Match the Wallpaper depth to the rest of the content.
            depthController = (launcher as? QuickstepLauncher)?.depthController
            transitionConfig.setInterpolator(StateAnimationConfig.ANIM_DEPTH, motion.homeInterpolator)
            if (!windowControlsBlur) {
                depthController?.pauseBlursOnWindows(true)
            }
            depthController?.setStateWithAnimation(
                LauncherState.NORMAL,
                transitionConfig,
                animation,
            )

            // Add a blur animation to the scrim layer.
            if (!windowControlsBlur) {
                val maxBlurRadius =
                    if (Flags.allAppsBlur() || Flags.enableOverviewBackgroundWallpaperBlur()) {
                        launcher.resources.getDimensionPixelSize(R.dimen.max_depth_blur_radius_enhanced)
                    } else {
                        launcher.resources.getInteger(R.integer.max_depth_blur_radius)
                    }
                val blurAnimator = ValueAnimator.ofFloat(1f, 0f)
                blurAnimator.setInterpolator(BLUR_INTERPOLATOR)
                blurAnimator.addUpdateListener {
                    applyBlur(maxBlurRadius * blurAnimator.animatedValue as Float)
                }
                animation.add(blurAnimator)
            }

            // Make sure that the contrast scrim animates correctly (alongside the blur) if needed.
            transitionConfig.setInterpolator(
                StateAnimationConfig.ANIM_SCRIM_FADE,
                BLUR_INTERPOLATOR,
            )
            launcher.workspace.stateTransitionAnimation.setScrim(
                animation,
                LauncherState.NORMAL,
                transitionConfig,
            )
        }

        // To avoid awkward jumps in icon position, we want the sibling animation to always be
        // targeting the current position. Since we can't easily access this, instead we calculate
        // it using the animation of the whole of home.
        // We start by caching the final target position, as this is the base for the transforms.
        val originalTarget = RectF(windowTargetRect)
        val transformed = RectF()
        val transform = Matrix()
        animation.addOnFrameListener {
            // The iOS arrival is measured against the final icon. Retargeting it on every
            // workspace frame adds a second moving endpoint to the small settling motion.
            if (motion.isIos) return@addOnFrameListener
            transformed.set(originalTarget)

            // First we scale down using the same pivot as the workspace scale, so we find the
            // correct position AND size.
            transform.setScale(workspace.scaleX, workspace.scaleY, workspace.pivotX, workspace.pivotY)
            transformed.transform(transform)
            // Then we scale back up around the center of the current position. This is because the
            // icon animation behaves poorly if it is given a target that is smaller than the size
            // of the icon.
            transform.setScale(1 / workspace.scaleX, 1 / workspace.scaleY,
                transformed.centerX(), transformed.centerY())
            transformed.transform(transform)

            if (transformed != windowTargetRect) {
                windowTargetRect?.set(transformed)
                siblingAnimation?.onTargetPositionChanged()
            }
        }

        // Needed to avoid text artefacts during the scale animation.
        workspace.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        hotseat.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        animation.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    super.onAnimationCancel(animation)
                    cancelled = true
                    Log.d(TAG, "onAnimationCancel")
                }

                override fun onAnimationPause(animation: Animator) {
                    super.onAnimationPause(animation)
                    Log.d(TAG, "onAnimationPause")
                }
            }
        )

        animation.addListener(
            AnimatorListeners.forEndCallback(
                Runnable {
                    Log.d(TAG, "onAnimationEnd, workspace and hotseat are visible")
                    val tagId = com.android.app.animation.R.id.ongoing_animation
                    val finishedAnimation = getAnimators()
                    val ownsWorkspace = workspace.getTag(tagId).let {
                        it == null || it === finishedAnimation
                    }
                    val ownsHotseat = hotseat.getTag(tagId).let {
                        it == null || it === finishedAnimation
                    }
                    // A completed reveal is fully visible. An interrupted reveal leaves its
                    // current alpha for the next animation to continue from.
                    if (!cancelled) {
                        if (ownsWorkspace) workspace.alpha = MAX_ALPHA
                        if (ownsHotseat) hotseat.alpha = MAX_ALPHA
                    }
                    if (!cancelled && (!hotseat.isVisible || !workspace.isVisible)) {
                        Log.e(
                            TAG,
                            "Unexpected invisibility after animation end:" +
                                " workspace.isVisible=${workspace.isVisible}" +
                                ", workspace.alpha=${workspace.alpha}" +
                                ", hotseat.isVisible=${hotseat.isVisible}" +
                                ", hotseat.alpha=${hotseat.alpha}",
                            Exception(),
                        )
                    }

                    if (ownsWorkspace) workspace.setLayerType(View.LAYER_TYPE_NONE, null)
                    if (ownsHotseat) hotseat.setLayerType(View.LAYER_TYPE_NONE, null)

                    // Detach only our own tags. The setter cancels the tagged animator,
                    // which may now belong to the app being launched during this reveal.
                    if (workspace.getTag(tagId) === finishedAnimation) {
                        workspace.setTag(tagId, null)
                    }
                    if (hotseat.getTag(tagId) === finishedAnimation) {
                        hotseat.setTag(tagId, null)
                    }
                    removeBlurLayer()
                    if (!windowControlsBlur) depthController?.pauseBlursOnWindows(false)
                }
            )
        )
    }

    fun getAnimators(): AnimatorSet {
        return animation.buildAnim()
    }

    fun start() {
        val animators = getAnimators()
        // Make sure to cache the current animation, so it can be properly interrupted.
        // TODO(b/367591368): ideally these animations would be refactored to be controlled
        //  centrally so each instances doesn't need to care about this coordination.
        Animations.setOngoingAnimation(launcher.workspace, animators)
        Animations.setOngoingAnimation(launcher.hotseat, animators)
        launcher.stateManager.setCurrentAnimation(animators, LauncherState.NORMAL)
        animators.start()
    }

    private fun addBlurLayer() {
        if (!Flags.blurredHomeAnimation()) {
            return
        }
        val parent = launcher.dragLayer.viewRootImpl?.surfaceControl ?: return
        if (!parent.isValid) {
            Log.e(TAG, "Parent surface is not ready at the moment. Can't apply blur.")
            return
        }
        val blurLayer =
            SurfaceControl.Builder()
                .setName("Home to launcher blur layer")
                .setCallsite("ScalingWorkspaceRevealAnim")
                .setParent(parent)
                .setOpaque(false)
                .setHidden(false)
                .build()

        // Schedule the initial setup of the blur layer.
        val setupTransaction = SurfaceTransaction()
        setupTransaction.forSurface(blurLayer).setAlpha(0f).setShow()
        surfaceTransactionApplier.scheduleApply(setupTransaction)

        this.blurLayer = blurLayer
    }

    private fun removeBlurLayer() {
        blurLayer?.let {
            if (it.isValid) {
                // Schedule the removal of the blur layer.
                val removalTransaction = SurfaceTransaction()
                removalTransaction.forSurface(it).setRemove()
                surfaceTransactionApplier.scheduleApply(removalTransaction)
            }
        }
        blurLayer = null
    }

    private fun applyBlur(blurRadius: Float) {
        blurLayer?.let {
            if (it.isValid) {
                // Schedule the blur update.
                val blurUpdateTransaction = SurfaceTransaction()
                blurUpdateTransaction.forSurface(it).setBackgroundBlurRadius(blurRadius.toInt())
                surfaceTransactionApplier.scheduleApply(blurUpdateTransaction)
            }
        }
    }
}
