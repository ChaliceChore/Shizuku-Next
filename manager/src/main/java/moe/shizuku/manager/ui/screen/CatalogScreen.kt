package moe.shizuku.manager.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModuleCatalog
import moe.shizuku.manager.module.ModuleRepo
import moe.shizuku.manager.module.Release
import moe.shizuku.manager.ui.component.PillButton
import moe.shizuku.manager.ui.component.PillButtonQuiet
import moe.shizuku.manager.utils.CustomTabsHelper

/** What a catalog lists. */
enum class CatalogKind {
    /** Modules, found by the topic their authors put on them. */
    MODULES,

    /** Everything in the community's index of repositories. */
    APPS,
}

private enum class CatalogSort(val label: Int) {
    STARS(R.string.catalog_sort_stars),
    UPDATED(R.string.catalog_sort_updated),
    NAME(R.string.catalog_sort_name),
}

/**
 * Repositories as a list of cards, for modules and for apps alike.
 *
 * One screen for both because they are the same list of the same kind of thing - a repository with
 * an avatar, a paragraph and a release - and the only differences are where the list comes from and
 * what a card offers: a module's card installs, an app's card opens on GitHub. The search, the sort
 * and the language filter are the app's own, since a list of over a thousand repositories is not
 * something to read in order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    kind: CatalogKind,
    installedIds: Set<String>,
    onBack: () -> Unit,
    onInstalled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var repos by remember { mutableStateOf<List<ModuleRepo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(CatalogSort.STARS) }
    var language by remember { mutableStateOf<String?>(null) }

    var openRepo by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<Map<String, Release?>>(emptyMap()) }
    var loadingLatest by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(kind) {
        runCatching {
            if (kind == CatalogKind.MODULES) ModuleCatalog.modules() else ModuleCatalog.index()
        }
            .onSuccess { repos = it }
            .onFailure { error = it.message }
    }

    val all = repos ?: emptyList()

    val languages = remember(all) {
        all.mapNotNull { it.language }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(8)
    }

    val shown = remember(all, query, sort, language) {
        all.filter { repo ->
            val matchesQuery = query.isBlank() ||
                repo.fullName.contains(query, ignoreCase = true) ||
                repo.description.contains(query, ignoreCase = true)

            matchesQuery && (language == null || repo.language == language)
        }.sortedWith(
            when (sort) {
                CatalogSort.STARS -> compareByDescending { it.stars }
                CatalogSort.UPDATED -> compareByDescending { it.updatedAt }
                CatalogSort.NAME -> compareBy { it.name.lowercase() }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    stringResource(
                        if (kind == CatalogKind.MODULES) R.string.modules_catalog else R.string.catalog_apps_title
                    )
                )
            },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        if (repos == null && error == null) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.catalog_search)) },
                    singleLine = true
                )
            }

            if (kind == CatalogKind.APPS) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CatalogSort.entries.size) { index ->
                            val option = CatalogSort.entries[index]
                            FilterChip(
                                selected = sort == option,
                                onClick = { sort = option },
                                label = { Text(stringResource(option.label)) }
                            )
                        }
                    }
                }

                if (languages.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(languages.size) { index ->
                                val name = languages[index]
                                FilterChip(
                                    selected = language == name,
                                    onClick = { language = if (language == name) null else name },
                                    label = { Text(name) }
                                )
                            }
                        }
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            items(shown.size) { index ->
                val repo = shown[index]

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large),
                    onClick = {
                        if (openRepo == repo.fullName) {
                            openRepo = null
                            return@Card
                        }

                        openRepo = repo.fullName

                        if (latest.containsKey(repo.fullName)) return@Card
                        loadingLatest = repo.fullName
                        scope.launch {
                            val release = runCatching { ModuleCatalog.latestRelease(repo.fullName) }
                                .getOrNull()
                            latest = latest + (repo.fullName to release)
                            loadingLatest = null
                        }
                    }
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(url = repo.avatarUrl)

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp)
                            ) {
                                Text(
                                    text = repo.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = repo.owner?.login.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Icon(
                                imageVector = if (openRepo == repo.fullName) {
                                    Icons.Filled.KeyboardArrowUp
                                } else {
                                    Icons.Filled.KeyboardArrowDown
                                },
                                contentDescription = null
                            )
                        }

                        if (repo.description.isNotEmpty()) {
                            Text(
                                text = repo.description,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 10.dp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(text = " ${repo.stars}", style = MaterialTheme.typography.labelMedium)
                            repo.language?.let { language ->
                                Text(text = "  -  $language", style = MaterialTheme.typography.labelMedium)
                            }
                            repo.updatedAt.take(10).takeIf { it.isNotEmpty() }?.let { updated ->
                                Text(text = "  -  $updated", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        if (openRepo == repo.fullName) {
                            if (loadingLatest == repo.fullName) {
                                LinearWavyProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp)
                                )
                            }

                            val release = latest[repo.fullName]
                            val zip = release?.moduleZip

                            // Only the newest release is shown, the way the community's own site
                            // shows it: what a repository is now, rather than everything it has
                            // ever been.
                            Text(
                                text = release?.let {
                                    stringResource(
                                        R.string.catalog_latest_release,
                                        it.label.ifEmpty { stringResource(R.string.catalog_no_release) }
                                    )
                                } ?: stringResource(
                                    if (loadingLatest == repo.fullName) {
                                        R.string.catalog_loading_release
                                    } else {
                                        R.string.catalog_no_release
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )

                            Text(
                                text = zip?.name ?: stringResource(R.string.modules_catalog_no_zip),
                                style = MaterialTheme.typography.bodySmall
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (zip != null) {
                                    PillButton(
                                        onClick = {
                                            installing = repo.fullName
                                            scope.launch {
                                                runCatching {
                                                    AdbModuleManager.install(
                                                        context,
                                                        ModuleCatalog.download(zip.url)
                                                    )
                                                }
                                                    .onSuccess {
                                                        onInstalled()
                                                    }
                                                    .onFailure { error = it.message }
                                                installing = null
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = stringResource(
                                                if (installing == repo.fullName) {
                                                    R.string.modules_catalog_installing
                                                } else {
                                                    R.string.modules_catalog_install
                                                }
                                            ),
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }

                                PillButtonQuiet(onClick = {
                                    CustomTabsHelper.launchUrlOrCopy(context, repo.htmlUrl)
                                }) {
                                    Icon(
                                        imageVector = Icons.Outlined.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.catalog_view_github),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }

                            if (repo.fullName in installedIds) {
                                Text(
                                    text = stringResource(R.string.modules_catalog_installed),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A repository's avatar, fetched once and kept for as long as the row is. */
@Composable
private fun Avatar(url: String?) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect

        bitmap = runCatching {
            val bytes = ModuleCatalog.download(url)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

    val image = bitmap

    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        // A placeholder of the same size, so the row does not move when the image lands.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}
