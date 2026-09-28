package com.lemwoodmc.launcher.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lemwoodmc.launcher.ui.components.PageTitle
import com.lemwoodmc.launcher.ui.theme.LmcBlue
import com.lemwoodmc.launcher.ui.theme.LmcGreen
import com.lemwoodmc.launcher.ui.theme.LmcGreenGlow
import com.lemwoodmc.launcher.viewmodel.LauncherViewModel

/**
 * 账户管理页（骨架）：离线账户列表 + Microsoft OAuth 入口（留接口）。
 */
@Composable
fun AccountScreen() {
    val vm: LauncherViewModel = viewModel()
    val accounts by vm.accounts.collectAsState()
    val selected by vm.settings.collectAsState()

    Column(Modifier.fillMaxSize()) {
        PageTitle("账户", "管理游戏账户")

        TextButton(
            onClick = { /* Microsoft OAuth 设备码流程，留接口 */ },
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = LmcBlue),
            modifier = Modifier.padding(start = 12.dp),
        ) { Text("+ 添加 Microsoft 账户", fontWeight = FontWeight.Bold) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(accounts, key = { it.id }) { a ->
                val isCurrent = selected.selectedAccountId == a.id
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrent) LmcGreenGlow else MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(2.dp))
                            Text(a.type, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = { vm.selectAccount(a.id) },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = LmcGreen),
                        ) { Text(if (isCurrent) "使用中" else "使用", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
