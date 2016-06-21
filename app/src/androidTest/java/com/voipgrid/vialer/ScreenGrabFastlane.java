package com.voipgrid.vialer;


import android.support.test.rule.ActivityTestRule;

import com.voipgrid.vialer.onboarding.SetupActivity;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import tools.fastlane.screengrab.Screengrab;
import tools.fastlane.screengrab.locale.LocaleTestRule;

@RunWith(JUnit4.class)
public class ScreenGrabFastlane  {
    @ClassRule
    public static final LocaleTestRule localeTestRule = new LocaleTestRule();

    @Rule
    public ActivityTestRule<SetupActivity> activityRule = new ActivityTestRule<>(SetupActivity.class);

    @Test
    public void testTakeScreenshot() {
        Screengrab.screenshot("before_button_click");
    }
}
