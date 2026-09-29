package com.cognidiary.app.ui.onboarding

import androidx.compose.foundation.layout.Column
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
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.remote.ApiException
import com.cognidiary.app.ui.components.BackButton
import com.cognidiary.app.ui.components.PrimaryButton
import com.cognidiary.app.ui.components.SecondaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.StatusReferral
import kotlinx.coroutines.launch

/**
 * 修改密码页（原型图 3，忘记密码入口）：邮箱 / 新密码 / 邮箱验证码 → 修改。
 *
 * 后端契约（API-01 1.4）：POST /v1/auth/password-reset，204 静默成功——
 * 邮箱不存在也返回成功（防枚举），所以这里统一提示「用新密码登录」。
 * 成功后所有设备会被踢下线（服务端吊销全部会话）。
 */
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun sendCode() {
        error = null
        busy = true
        scope.launch {
            try {
                ServiceLocator.auth.sendEmailCode(email, "PASSWORD_RESET")
                error = Copy.Login.CODE_SENT
                busy = false
            } catch (e: Exception) {
                error = (e as? ApiException)?.message ?: "网络不给力，请稍后再试"
                busy = false
            }
        }
    }

    fun submit() {
        error = null
        if (password.length < 8) {
            error = Copy.Login.REGISTER_SHORT
            return
        }
        busy = true
        scope.launch {
            try {
                ServiceLocator.auth.passwordReset(email, code, password)
                busy = false
                onDone()
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
            text = Copy.Login.FORGOT_TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(Spacing.x6))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(text = Copy.Login.EMAIL_LABEL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(text = Copy.Login.NEW_PASSWORD_LABEL) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(text = Copy.Login.EMAIL_CODE_LABEL) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        SecondaryButton(
            text = Copy.Login.SEND_CODE_BUTTON,
            onClick = { sendCode() },
            enabled = email.isNotBlank() && !busy,
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(Spacing.x3))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = StatusReferral,
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(Spacing.x6))
        PrimaryButton(
            text = Copy.Login.FORGOT_SUBMIT,
            onClick = { submit() },
            enabled = email.isNotBlank() && password.length >= 8 &&
                code.isNotBlank() && !busy,
        )
        Spacer(modifier = Modifier.height(Spacing.x4))
    }
}
