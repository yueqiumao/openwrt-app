package com.ax6.luyouqi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ax6.luyouqi.ui.theme.Ax6Dimens
import com.ax6.luyouqi.ui.theme.Ax6Tokens

@Composable
fun LoginScreen(
    host: String,
    port: String,
    username: String,
    password: String,
    secure: Boolean,
    busy: Boolean,
    error: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSecureChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ax6Tokens.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("AX6 助手", color = Ax6Tokens.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(26.dp))

        field(
            label = "路由器地址",
            value = host,
            placeholder = "192.168.1.1",
            onValueChange = onHostChange,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(12.dp))
        field(
            label = "端口",
            value = port,
            placeholder = "80",
            onValueChange = onPortChange,
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(12.dp))
        field(
            label = "用户名",
            value = username,
            placeholder = "留空即 root",
            onValueChange = onUsernameChange,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(12.dp))
        field(
            label = "密码",
            value = password,
            placeholder = "LuCI 登录密码",
            onValueChange = onPasswordChange,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            isPassword = true,
        )

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = secure, onCheckedChange = onSecureChange)
            Spacer(Modifier.width(10.dp))
            Text("使用 HTTPS", color = Ax6Tokens.Muted, fontSize = 13.sp)
        }

        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(error, color = Ax6Tokens.Danger, fontSize = 13.sp)
        }

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onConnect,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(Ax6Dimens.RadiusSm),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ax6Tokens.Accent,
                contentColor = Ax6Tokens.Bg,
            ),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    color = Ax6Tokens.Bg,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("连接", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "密码仅保存在本机，由 Android Keystore 加密；不写日志、不进备份。",
            color = Ax6Tokens.Dim,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun field(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    isPassword: Boolean = false,
) {
    Column {
        Text(label, color = Ax6Tokens.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Ax6Tokens.Dim, fontSize = 14.sp) },
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Ax6Dimens.RadiusSm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Ax6Tokens.Field,
                unfocusedContainerColor = Ax6Tokens.Field,
                focusedTextColor = Ax6Tokens.Text,
                unfocusedTextColor = Ax6Tokens.Text,
                focusedBorderColor = Ax6Tokens.Accent,
                unfocusedBorderColor = Ax6Tokens.Line,
            ),
        )
    }
}
