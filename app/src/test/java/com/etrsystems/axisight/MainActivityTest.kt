package com.etrsystems.axisight

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @Test
    fun activityShouldBeCreated() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assert(activity != null)
    }
}
