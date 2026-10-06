/*
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.arkui.widget.tests;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;

/** Avoid scanning every installed APK on the main thread during instrumentation startup. */
public final class WidgetTestRunner extends InstrumentationTestRunner {
    @Override
    public void onCreate(Bundle arguments) {
        if (arguments == null) arguments = new Bundle();
        if (!arguments.containsKey("class")) {
            arguments.putString("class", FrostedWidgetTest.class.getName());
        }
        super.onCreate(arguments);
    }
}
