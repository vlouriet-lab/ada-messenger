package com.ada.messenger.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ada.messenger.core.AdaCoreViewModel
import com.ada.messenger.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

// V-33: Onboarding carousel data
private data class OnboardingSlide(
    val icon: ImageVector,
    val titleRes: Int,
    val descRes: Int,
)

private val onboardingSlides = listOf(
    OnboardingSlide(
        icon = Icons.Default.Lock,
        titleRes = R.string.onboarding_slide1_title,
        descRes = R.string.onboarding_slide1_desc,
    ),
    OnboardingSlide(
        icon = Icons.Default.Hub,
        titleRes = R.string.onboarding_slide2_title,
        descRes = R.string.onboarding_slide2_desc,
    ),
    OnboardingSlide(
        icon = Icons.Default.Shield,
        titleRes = R.string.onboarding_slide3_title,
        descRes = R.string.onboarding_slide3_desc,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: AdaCoreViewModel,
    onIdentityCreated: () -> Unit,
) {
    var displayName by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val initialized by viewModel.initialized.collectAsState()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { onboardingSlides.size + 1 }) // +1 for profile creation

    // Navigate when core finishes initializing
    LaunchedEffect(initialized) {
        if (initialized) {
            isLoading = false
            onIdentityCreated()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                if (page < onboardingSlides.size) {
                    // Carousel slide
                    val slide = onboardingSlides[page]
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(128.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Icon(
                                imageVector = slide.icon,
                                contentDescription = null,
                                modifier = Modifier.size(60.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = stringResource(slide.titleRes),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(slide.descRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    // Profile creation page (last slide)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.onboarding_create_profile_title),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.onboarding_profile_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text(stringResource(R.string.onboarding_display_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                isLoading = true
                                viewModel.create(displayName.trim())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = displayName.isNotBlank() && !isLoading,
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.onboarding_profile_creating))
                            } else {
                                Text(stringResource(R.string.onboarding_profile_button))
                            }
                        }
                    }
                }
            }

            // Page indicator + navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Dot indicators
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(onboardingSlides.size + 1) { index ->
                        val isActive = pagerState.currentPage == index
                        val width by animateDpAsState(
                            targetValue = if (isActive) 24.dp else 8.dp,
                            animationSpec = tween(300),
                            label = "dotWidth",
                        )
                        val color by animateColorAsState(
                            targetValue = if (isActive)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outlineVariant,
                            animationSpec = tween(300),
                            label = "dotColor",
                        )
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .height(8.dp)
                                .width(width)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
            }

            // Next / Back buttons
            if (pagerState.currentPage < onboardingSlides.size) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (pagerState.currentPage > 0) {
                        TextButton(onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        }) {
                            Text(stringResource(R.string.button_back))
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Button(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }) {
                        Text(stringResource(R.string.button_next))
                    }
                }
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

