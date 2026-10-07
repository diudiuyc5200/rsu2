package org.bakasu.bakasu.ui.screen.moduleRepo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.Download
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material.icons.twotone.OpenInBrowser
import androidx.compose.material.icons.twotone.Person
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.bakasu.bakasu.R
import org.bakasu.bakasu.domain.model.CatalogModule
import org.bakasu.bakasu.domain.model.ModuleCatalogFailure
import org.bakasu.bakasu.domain.model.ModuleRelease
import org.bakasu.bakasu.domain.model.ModuleReleaseAsset
import org.bakasu.bakasu.ui.activity.PermissionRequestInterface
import org.bakasu.bakasu.ui.component.GithubMarkdown
import org.bakasu.bakasu.ui.component.HorizontalPagerWithInteraction
import org.bakasu.bakasu.ui.component.MarkdownContent
import org.bakasu.bakasu.ui.component.RepositoryPageScaffold
import org.bakasu.bakasu.ui.component.SwipeableSnackbarHost
import org.bakasu.bakasu.ui.component.moduleAssetDetails
import org.bakasu.bakasu.ui.component.rememberCustomDialog
import org.bakasu.bakasu.ui.component.rememberRepositoryInstallDialog
import org.bakasu.bakasu.ui.component.rememberRepositoryScrollBehavior
import org.bakasu.bakasu.ui.component.settings.AppBackButton
import org.bakasu.bakasu.ui.component.settings.SegmentedColumn
import org.bakasu.bakasu.ui.component.settings.SettingsBaseWidget
import org.bakasu.bakasu.ui.component.settings.lazySegmentColumn
import org.bakasu.bakasu.ui.navigation.LocalNavigator
import org.bakasu.bakasu.ui.navigation.Navigator
import org.bakasu.bakasu.ui.navigation.Route
import org.bakasu.bakasu.ui.theme.CardConfig
import org.bakasu.bakasu.ui.theme.ThemeConfig
import org.bakasu.bakasu.ui.theme.blurEffect
import org.bakasu.bakasu.ui.theme.blurSource
import org.bakasu.bakasu.ui.theme.renderBackgroundBlur
import org.bakasu.bakasu.ui.util.LocalPermissionRequestInterface
import org.bakasu.bakasu.ui.util.LocalSnackbarHost
import org.bakasu.bakasu.ui.util.adaptiveScaffoldWindowInsets
import org.bakasu.bakasu.ui.viewmodel.ModuleDetailUiAction
import org.bakasu.bakasu.ui.viewmodel.ModuleDetailViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * @author AlexLiuDev233
 * @date 2025/12/7
 */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnlineModuleDetailScreen(
    moduleId: String,
    repositoryUrl: String,
) {
    val viewModel = koinViewModel<ModuleDetailViewModel>(
        key = "$repositoryUrl#$moduleId",
        parameters = { parametersOf(moduleId, repositoryUrl) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val module = state.module
    if (module == null) {
        RepositoryPageScaffold(stringResource(R.string.module_repo)) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) {
                    LoadingIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(
                                when (state.error) {
                                    ModuleCatalogFailure.Offline -> R.string.network_offline
                                    ModuleCatalogFailure.NotFound -> R.string.repo_module_unavailable
                                    else -> R.string.repo_error_network
                                },
                            ),
                        )
                        if (state.error != ModuleCatalogFailure.NotFound) {
                            FilledTonalButton(onClick = { viewModel.dispatch(ModuleDetailUiAction.Retry) }) {
                                Text(stringResource(R.string.network_retry))
                            }
                        }
                    }
                }
            }
        }
        return
    }
    OnlineModuleDetailContent(module, viewModel)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnlineModuleDetailContent(module: CatalogModule, viewModel: ModuleDetailViewModel) {
    val themeConfig: ThemeConfig = koinInject()
    val cardConfig: CardConfig = koinInject()
    val navigator = LocalNavigator.current
    val snackBarHost = LocalSnackbarHost.current
    val coroutineScope = rememberCoroutineScope()

    val tabTitles = listOf(stringResource(R.string.readme), stringResource(R.string.release), stringResource(R.string.info))
    val uriHandler = LocalUriHandler.current
    val pagerState = rememberPagerState(pageCount = { tabTitles.size })
    val scrollBehavior = rememberRepositoryScrollBehavior(pagerState.currentPage)
    val confirmDownload = rememberRepositoryInstallDialog()
    fun downloadLatest(asset: ModuleReleaseAsset) {
        val current = viewModel.resolveAsset(asset) ?: return
        if (current.latestAsset?.assets?.any { it.hasSameIdentity(asset) } != true) return
        confirmDownload(current, asset) { viewModel.resolveAsset(asset)?.latestAsset?.assets?.any { it.hasSameIdentity(asset) } == true }
    }
    val chooseDialog = rememberCustomDialog { dismiss ->
        ChooseDialogContent(module, onSelect = { downloadLatest(it) }, dismiss = dismiss)
    }
    val installLatest = {
        val assets = module.latestAsset?.assets.orEmpty()
        if (assets.size == 1) {
            downloadLatest(assets.single())
        } else if (assets.size > 1) {
            chooseDialog.show()
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier.blurEffect(),
            ) {
                LargeFlexibleTopAppBar(
                    title = { Text(module.moduleName) },
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        AppBackButton(
                            onClick = {
                                navigator.pop()
                            },
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                uriHandler.openUri(module.pageUrl)
                            },
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.OpenInBrowser,
                                contentDescription = stringResource(R.string.open_module_home_page),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors().copy(
                        containerColor =
                            if (themeConfig.isEnableBlur) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer.copy(cardConfig.cardAlpha)
                            },
                        scrolledContainerColor =
                            if (themeConfig.isEnableBlur) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer.copy(cardConfig.cardAlpha)
                            },
                    ),
                    windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = 12.dp)),
                )

                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor =
                        if (themeConfig.isEnableBlur) {
                            Color.Transparent
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer.copy(cardConfig.cardAlpha)
                        },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(title) },
                        )
                    }
                }

                BackHandler(
                    pagerState.currentPage != 0,
                ) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                }
            }
        },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = adaptiveScaffoldWindowInsets(),
        floatingActionButton = {
            if (module.latestAsset?.assets?.isNotEmpty() == true) {
                FloatingActionButton(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    onClick = installLatest,
                ) {
                    Icon(Icons.TwoTone.Download, contentDescription = stringResource(R.string.install))
                }
            }
        },
        snackbarHost = { SwipeableSnackbarHost(hostState = snackBarHost) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .blurSource(),
        ) {
            HorizontalPagerWithInteraction(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2,
            ) { page ->
                when (page) {
                    0 -> ReadmeTab(module, scrollBehavior.nestedScrollConnection, innerPadding, viewModel)

                    1 -> ReleasesTab(
                        module,
                        scrollBehavior.nestedScrollConnection,
                        innerPadding,
                        viewModel,
                    )

                    2 -> InfoTab(module, scrollBehavior.nestedScrollConnection, innerPadding)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InfoTab(
    module: CatalogModule,
    nestedScrollConnection: NestedScrollConnection,
    innerPadding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current
    val authorTitle = stringResource(R.string.author)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp)
            .nestedScroll(nestedScrollConnection),
    ) {
        item {
            Spacer(Modifier.height(innerPadding.calculateTopPadding()))
        }
        lazySegmentColumn(module.authorList, title = authorTitle) { _, author ->
            SettingsBaseWidget(
                icon = Icons.TwoTone.Person,
                title = author.name,
                onClick = if (author.link.isNotBlank()) ({ uriHandler.openUri(author.link) }) else null,
            ) {
                if (author.link.isNotBlank()) {
                    Icon(
                        modifier = Modifier.size(24.dp),
                        imageVector = Icons.TwoTone.Link,
                        contentDescription = stringResource(R.string.author_link),
                    )
                }
            }
        }

        if (module.sourceUrl.isNotBlank()) {
            item {
                SegmentedColumn(
                    title = stringResource(R.string.source_code),
                ) {
                    item {
                        SettingsBaseWidget(
                            icon = Icons.TwoTone.Code,
                            title = module.sourceUrl,
                            onClick = {
                                uriHandler.openUri(module.sourceUrl)
                            },
                        )
                    }
                }
            }
        }

        item {
            SegmentedColumn(title = stringResource(R.string.module_repo)) {
                item {
                    SettingsBaseWidget(
                        title = module.repositoryName,
                        description = module.repositoryUrl,
                        iconPlaceholder = false,
                        onClick = { uriHandler.openUri(module.repositoryUrl) },
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding() + 88.dp))
        }
    }
}

@Composable
fun ReleasesTab(
    module: CatalogModule,
    nestedScrollConnection: NestedScrollConnection,
    innerPadding: PaddingValues,
    viewModel: ModuleDetailViewModel? = null,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(innerPadding.calculateTopPadding()))
        }
        items(
            items = module.releases,
            key = { "${it.versionCode ?: it.tagName}:${it.assets.firstOrNull()?.downloadUrl.orEmpty()}" },
        ) {
            ReleaseCard(module, it, viewModel)
        }
        item {
            Spacer(Modifier.height(innerPadding.calculateBottomPadding() + 88.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReadmeTab(
    module: CatalogModule,
    nestedScrollConnection: NestedScrollConnection,
    innerPadding: PaddingValues,
    viewModel: ModuleDetailViewModel? = null,
) {
    val state = viewModel?.state?.collectAsStateWithLifecycle()?.value
    val remote = module.readmeUrl.isNotBlank()
    val document = state?.documents?.get(module.readmeUrl)
    LaunchedEffect(module.readmeUrl) { viewModel?.loadDocument(module.readmeUrl) }
    val htmlLoading = remember(module.readme) { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
        when {
            remote && (document == null || document.loading) -> LoadingIndicator()

            remote && document?.failed == true -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.repo_document_error))
                TextButton(onClick = { viewModel.loadDocument(module.readmeUrl, retry = true) }) {
                    Text(stringResource(R.string.network_retry))
                }
            }

            (if (remote) document?.text else module.readme).isNullOrBlank() -> Text(stringResource(R.string.repo_no_readme))

            else -> {
                LazyColumn(
                    Modifier.fillMaxSize().nestedScroll(nestedScrollConnection),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                ) {
                    item {
                        if (remote) {
                            MarkdownContent(document?.text.orEmpty())
                        } else {
                            GithubMarkdown(
                                content = module.readme,
                                backgroundColor = Color.Transparent,
                                loading = htmlLoading,
                                callerProvideLoadingIndicator = true,
                            )
                        }
                    }
                }
                if (!remote && htmlLoading.value) LoadingIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReleaseCard(
    module: CatalogModule,
    release: ModuleRelease,
    viewModel: ModuleDetailViewModel? = null,
) {
    val themeConfig: ThemeConfig = koinInject()
    val cardConfig: CardConfig = koinInject()
    val confirmDownload = rememberRepositoryInstallDialog()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .renderBackgroundBlur(MaterialTheme.colorScheme.surfaceBright),
        shape = RoundedCornerShape(16.dp),
        color =
            if (themeConfig.isEnableBlurExp) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.surfaceBright.copy(cardConfig.cardAlpha)
            },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = release.name,
                    style = MaterialTheme.typography.bodyMediumEmphasized,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = release.publishedAt,
                    style = MaterialTheme.typography.bodySmallEmphasized,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(
                    top = 5.dp,
                    bottom = 5.dp,
                ),
            )
            if (release.assets.isEmpty()) return@Surface

            Column {
                release.assets.forEach { assetInfo ->
                    val onClick: () -> Unit = {
                        val current = viewModel?.resolveAsset(assetInfo)
                        if (current != null) {
                            confirmDownload(current, assetInfo) { viewModel.resolveAsset(assetInfo) != null }
                        }
                    }
                    SettingsBaseWidget(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .renderBackgroundBlur(tintColor = MaterialTheme.colorScheme.surfaceBright),
                        title = assetInfo.name,
                        onClick = { onClick() },
                        iconPlaceholder = false,
                        description = moduleAssetDetails(assetInfo),
                        isOnBackground = false,
                        containerColor = Color.Transparent,
                    ) {
                        FilledTonalButton(
                            onClick = onClick,
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Icon(
                                modifier = Modifier.size(20.dp),
                                imageVector = Icons.TwoTone.Download,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Preview
private fun ReleaseCardPreview() {
    val release = ModuleRelease(
        name = "name",
        tagName = "tagName",
        publishedAt = "publishedAt",
        assets = ArrayList<ModuleReleaseAsset>().apply {
            add(
                ModuleReleaseAsset(
                    name = "name",
                    downloadUrl = "downloadUrl",
                    size = 0,
                    downloadCount = 0,
                ),
            )
            add(
                ModuleReleaseAsset(
                    name = "name2",
                    downloadUrl = "downloadUrl2",
                    size = 0,
                    downloadCount = 0,
                ),
            )
        },
    )

    val fakeModule = initFakeRepoModuleForPreview()

    CompositionLocalProvider(
        LocalNavigator provides Navigator(Route.ModuleRepoDetail(fakeModule.moduleId, fakeModule.repositoryUrl)),
        LocalPermissionRequestInterface provides object : PermissionRequestInterface {
            override fun requestPermission(
                permission: String,
                callback: (Boolean) -> Unit,
                requestDescription: String,
            ) {
            }

            override fun requestPermissions(
                permissions: Array<String>,
                callback: (Map<String, @JvmSuppressWildcards Boolean>) -> Unit,
                requestDescription: Map<String, String>,
            ) {
            }
        },
    ) {
        ReleaseCard(fakeModule, release)
    }
}
