package com.personal.musicapp.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.personal.musicapp.ui.theme.RosePrimary

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginDialog(
    currentCookie: String?,
    onSaveCookie: (String) -> Unit,
    onClearCookie: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isManualMode by remember { mutableStateOf(false) }
    var manualCookieText by remember { mutableStateOf("") }
    var isLoadingPage by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val isLoggedIn = !currentCookie.isNullOrBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF0000).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFFFF0000),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "YouTube 账号授权",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (isLoggedIn) "已激活会员专属高码率" else "免广告 · 登录享 256kbps 会员音质",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    color = if (isLoggedIn) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isManualMode && !isLoggedIn) {
                            IconButton(onClick = { webViewRef?.reload() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "刷新",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isLoadingPage && !isManualMode && !isLoggedIn) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = RosePrimary
                    )
                }

                // Main Content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (isLoggedIn) {
                        // Already logged in screen
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "YouTube Premium 凭证有效",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "当前已自动启用会员最高 256kbps 音质解析与无广告特权。",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        CookieManager.getInstance().removeAllCookies(null)
                                        onClearCookie()
                                        Toast.makeText(context, "已退出登录", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("退出登录")
                                }
                                Button(
                                    onClick = onDismiss,
                                    colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                                ) {
                                    Text("完成")
                                }
                            }
                        }
                    } else if (isManualMode) {
                        // Manual cookie input fallback
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "手动粘贴 Cookie",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "若网页登录遇到 Google 拦截，可在电脑浏览器打开 music.youtube.com，复制 Cookie 粘贴在此：",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = manualCookieText,
                                onValueChange = { manualCookieText = it },
                                placeholder = { Text("粘贴包含 SAPISID 或 SSID 的 Cookie 字符串...", fontSize = 12.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                maxLines = 10
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { isManualMode = false }) {
                                    Text("返回网页登录")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (manualCookieText.isNotBlank()) {
                                            onSaveCookie(manualCookieText.trim())
                                            Toast.makeText(context, "YouTube 凭证保存成功", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                                ) {
                                    Text("保存凭证")
                                }
                            }
                        }
                    } else {
                        // WebView Login
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewRef = this
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.setSupportZoom(true)
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false

                                    // Replace embedded webview markers to prevent Google 403 disallowed_useragent
                                    val defaultUa = settings.userAgentString
                                    settings.userAgentString = defaultUa
                                        .replace("; wv", "")
                                        .replace("Version/4.0 ", "")

                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            isLoadingPage = true
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            isLoadingPage = false

                                            val musicCookie = cookieManager.getCookie("https://music.youtube.com") ?: ""
                                            val googleCookie = cookieManager.getCookie("https://accounts.google.com") ?: ""
                                            val combinedCookie = "$musicCookie; $googleCookie"

                                            if (combinedCookie.contains("SAPISID") || combinedCookie.contains("SSID") || combinedCookie.contains("LOGIN_INFO")) {
                                                val validCookie = if (musicCookie.isNotBlank()) musicCookie else combinedCookie
                                                onSaveCookie(validCookie)
                                                Toast.makeText(context, "YouTube 会员授权成功！", Toast.LENGTH_SHORT).show()
                                                onDismiss()
                                            }
                                        }
                                    }

                                    loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com")
                                }
                            },
                            onRelease = { webView ->
                                webView.stopLoading()
                                webView.destroy()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Bottom toolbar
                if (!isLoggedIn) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "登录成功后会自动识别并保存",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { isManualMode = !isManualMode }
                            ) {
                                Text(
                                    text = if (isManualMode) "切换网页登录" else "手动输入 Cookie",
                                    fontSize = 12.sp,
                                    color = RosePrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
