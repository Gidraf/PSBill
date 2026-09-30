package com.example.psbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.psbill.core.NavModule
import com.example.psbill.ui.theme.AjiriwaColors
import kotlinx.coroutines.launch

/**
 * Obsidian Kinetic App Scaffold & Modularized Drawer:
 * Modularized sidebar navigation view, active state indicators, WebSocket status pill, and Sign Out item.
 */

/**
 * Obsidian Kinetic App Shell — Enterprise Command Center Navigation Shell:
 * Glassmorphic Drawer (#0B1326 / #171F33), active item pill (#3131C0 / #B0B2FF),
 * Lead Admin badge, version tag, and top bar with live connection status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    brandName: String,
    subtitle: String,
    items: List<NavModule>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onLogout: () -> Unit,
    topBarActions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentTitle = items.firstOrNull { it.key == selectedKey }?.title ?: brandName

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = AjiriwaColors.Canvas,
                modifier = Modifier
                    .width(280.dp)
                    .border(1.dp, AjiriwaColors.Border, RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)),
            ) {
                DrawerHeader(brandName, subtitle)
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(items, key = { it.key }) { item ->
                        val isSelected = item.key == selectedKey
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    item.title,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            selected = isSelected,
                            icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                onSelect(item.key)
                                scope.launch { drawerState.close() }
                            },
                            shape = CircleShape,
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = AjiriwaColors.SecondaryContainer,
                                unselectedContainerColor = AjiriwaColors.Canvas,
                                selectedTextColor = AjiriwaColors.OnSecondaryContainer,
                                unselectedTextColor = AjiriwaColors.TextSecondary,
                                selectedIconColor = AjiriwaColors.OnSecondaryContainer,
                                unselectedIconColor = AjiriwaColors.TextSecondary,
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                        )
                    }
                }
                
                NavigationDrawerItem(
                    label = { Text("Sign out", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
                    selected = false,
                    icon = { Icon(Icons.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = { scope.launch { drawerState.close() }; onLogout() },
                    shape = CircleShape,
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = AjiriwaColors.Canvas,
                        unselectedTextColor = AjiriwaColors.Danger,
                        unselectedIconColor = AjiriwaColors.Danger,
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "V2.4.0-Stable",
                        color = AjiriwaColors.TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
    ) {
        Scaffold(
            containerColor = AjiriwaColors.Canvas,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                currentTitle,
                                color = AjiriwaColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                letterSpacing = (-0.02).sp
                            )
                            Text(brandName, color = AjiriwaColors.TextSecondary, fontSize = 11.sp)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = AjiriwaColors.Primary)
                        }
                    },
                    actions = { topBarActions() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AjiriwaColors.Surface),
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                content(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun DrawerHeader(brandName: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(AjiriwaColors.SurfaceAlt, AjiriwaColors.Canvas)))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AjiriwaColors.PrimaryAccent, AjiriwaColors.PrimaryDim)))
                    .border(2.dp, AjiriwaColors.Primary.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(brandName.take(1).uppercase(), color = AjiriwaColors.OnPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Command Center", color = AjiriwaColors.Primary, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(subtitle.ifEmpty { "Lead Admin" }, color = AjiriwaColors.TextSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AjiriwaColors.Border)
        )
    }
}

