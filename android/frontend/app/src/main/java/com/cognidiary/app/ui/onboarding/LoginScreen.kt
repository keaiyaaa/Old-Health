package com.cognidiary.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.data.remote.ApiException
import com.cognidiary.app.ui.components.BackButton
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.PrimaryButton
import com.cognidiary.app.ui.components.SecondaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.StatusReferral
import com.cognidiary.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * 登录页（原型图 1）：账号名称或邮箱 + 密码 + 登录；底部「注册」「忘记密码」。
 *
 * - 登录在产品说明与免责声明之后（负责人 2026-09-28 指示）
 * - 远程模式走 /v1/auth 真实账号体系，令牌由 TokenStore 加密保存
 * - 成功后去向由 AppNavHost 决定（家属端还要过查看密码）
 */
@Composable
fun LoginScreen(
    mode: UserMode,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    onGoRegister: () -> Unit,
    onGoForgot: () -> Unit,
) {
    val prefs = ServiceLocator.prefs
    val remote = prefs.useRemoteBackend
    val scope = rememberCoroutineScope()

    // 远程模式不预填本机演示账号名（那是一期本地演示留下的，与后端账号无关）
    var account by rememberSaveable {
        mutableStateOf(if (remote) "" else prefs.accountName.orEmpty())
    }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 老人端首次绑定（家属端「添加老人」给出的称呼 + 绑定码）
    var needBind by remember { mutableStateOf(false) }
    var bindName by rememberSaveable { mutableStateOf("") }
    var bindCode by rememberSaveable { mutableStateOf("") }

    fun submit() {
        error = null
        busy = true
        if (remote) {
            scope.launch {
                try {
                    val client = if (mode == UserMode.ELDER) "ELDER_APP" else "FAMILY_APP"
                    val info = ServiceLocator.auth.login(account, password, client)
                    if (info == null) {
                        error = Copy.Login.WRONG
                        busy = false
                        return@launch
                    }
                    if (mode != UserMode.ELDER) {
                        busy = false
                        prefs.loggedOut = false
                        onLoggedIn()
                        return@launch
                    }
                    // 老人端：选人并锁定会话（API-01 1.13）；首次使用先绑定
                    val subjects = ServiceLocator.auth.listSubjects()
                    when {
                        subjects.isEmpty() -> {
                            error = Copy.Family.BIND_HINT
                            busy = false
                            needBind = true
                        }
                        else -> {
                            ServiceLocator.auth.lockSubject(subjects.first().subjectId)
                            busy = false
                            prefs.loggedOut = false
                            onLoggedIn()
                        }
                    }
                } catch (e: Exception) {
                    error = (e as? ApiException)?.message ?: "网络不给力，请稍后再试"
                    busy = false
                }
            }
        } else {
            if (!prefs.verify(account, password)) {
                busy = false
                error = Copy.Login.WRONG
                return
            }
            busy = false
            prefs.loggedOut = false
            onLoggedIn()
        }
    }

    fun bind() {
        if (bindName.isBlank() || bindCode.isBlank()) {
            error = Copy.Family.BIND_HINT
            return
        }
        busy = true
        scope.launch {
            try {
                val (subjectId, _) = ServiceLocator.auth.createOrBindSubject(
                    bindName, "70-74", bindCode)
                ServiceLocator.auth.lockSubject(subjectId)
                busy = false
                prefs.loggedOut = false
                onLoggedIn()
            } catch (e: Exception) {
                error = (e as? ApiException)?.message ?: "网络不给力，请稍后再试"
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = ScreenPadding.family, vertical = Spacing.x4),
    ) {
        BackButton(onBack = onBack)
        Spacer(modifier = Modifier.height(Spacing.x2))

        Text(
            text = if (mode == UserMode.FAMILY) {
                Copy.Login.FAMILY_TITLE
            } else {
                Copy.Login.ELDER_TITLE
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.x6))

        OutlinedTextField(
            value = account,
            onValueChange = { account = it },
            label = { Text(text = Copy.Login.ACCOUNT_LABEL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(text = Copy.Login.PASSWORD_LABEL) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(Spacing.x3))
            // 错误文案用深暖色，全站无红色（合规红线）
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = StatusReferral,
            )
        }

        // 老人端首次绑定：输入家属给的称呼与绑定码（API-01 1.9）
        if (needBind && remote && mode == UserMode.ELDER) {
            Spacer(modifier = Modifier.height(Spacing.x6))
            Text(
                text = Copy.Family.BIND_TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(Spacing.x3))
            OutlinedTextField(
                value = bindName,
                onValueChange = { bindName = it },
                label = { Text(text = Copy.Family.BIND_NAME_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Spacing.x3))
            OutlinedTextField(
                value = bindCode,
                onValueChange = { bindCode = it },
                label = { Text(text = Copy.Family.BIND_CODE_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Spacing.x4))
            PrimaryButton(
                text = Copy.Family.BIND_SUBMIT,
                onClick = { bind() },
                enabled = bindName.isNotBlank() && bindCode.isNotBlank() && !busy,
            )
        }

        // ★ 风险提醒：每次登录（尤其退出后重新登录）必须展示（合规要求，非装饰）
        if (!needBind) {
            Spacer(modifier = Modifier.height(Spacing.x6))
            InfoBlock(text = Copy.Login.RISK_REMINDER)
        }

        Spacer(modifier = Modifier.height(Spacing.x6))
        PrimaryButton(
            text = Copy.Login.LOGIN_BUTTON,
            onClick = { submit() },
            enabled = account.isNotBlank() && password.isNotEmpty() && !busy,
        )

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(Spacing.x4))
        // 原型图 1 底部：注册 | 忘记密码
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            SecondaryButton(
                text = Copy.Login.REGISTER_BUTTON,
                onClick = onGoRegister,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = Copy.Login.FORGOT_BUTTON,
                onClick = onGoForgot,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
