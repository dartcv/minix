package me.dartcv.minix

import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherShowsTheLocalWorkspaceWithoutAuthentication() {
        composeRule.onNodeWithText("minix · v1.0").assertIsDisplayed()
        composeRule.onNodeWithText("本地工作区已准备好").assertIsDisplayed()
        composeRule.onNodeWithText("登录").assertDoesNotExist()
        composeRule.onNodeWithText("卡密").assertDoesNotExist()
    }

    @Test
    fun rootControlRequiresAnExplicitUserAction() {
        composeRule.onNodeWithText("同 UID 控制").assertIsDisplayed()
        composeRule.onNodeWithText("未连接").assertIsDisplayed()

        composeRule.onNodeWithText("控制").performClick()
        composeRule.onNodeWithText("本地目标会话").assertIsDisplayed()
        composeRule.onNodeWithText("连接服务").assertIsDisplayed()
    }

    @Test
    fun navigationOpensEveryCoreScreenAndReturnsHome() {
        composeRule.onNodeWithText("控制").performClick()
        composeRule.onNodeWithText("控制面板").assertIsDisplayed()

        composeRule.onNodeWithText("预设").performClick()
        composeRule.onNodeWithText("本地预设").assertIsDisplayed()

        composeRule.onNodeWithText("本地库").performClick()
        composeRule.onNodeWithText("设备内的活动记录、媒体与离线资料。").assertIsDisplayed()

        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("设置与隐私").assertIsDisplayed()

        composeRule.onNodeWithText("主页").performClick()
        composeRule.onNodeWithText("本地工作区已准备好").assertIsDisplayed()
    }

    @Test
    fun deniedOverlayPermissionShowsOnlyTheSafeGrantState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(Settings.canDrawOverlays(context))
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("授予悬浮权限").assertIsDisplayed()
        composeRule.onNodeWithText("启动悬浮工具").assertDoesNotExist()
        composeRule.onNodeWithText("停止悬浮工具").assertDoesNotExist()
    }
}
