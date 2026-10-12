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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
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

/**
 * Modules to install, from the community's own index.
 *
 * The shape of this screen is the one KernelSU-Next's repositories screen uses, because it is the
 * shape this kind of list wants: a card per repository with its avatar, its name and what it is,
 * and the releases folded away inside it until it is opened. What is deliberately not copied is the
 * GitHub token its version asks for - a person browsing a list of modules does not need one, so
 * this reads a plain file for the list and the unauthenticated API for the releases.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesCatalogScreen(
    installedIds: Set<String>,
    onBack: () -> Unit,
    onInstalled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var repos by remember { mutableStateOf<List<ModuleRepo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var openRepo by remember { mutableStateOf<String?>(null) }
    var releases by remember { mutableStateOf<List<Release>>(emptyList()) }
    var loadingReleases by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { ModuleCatalog.index() }
            .onSuccess { repos = it }
            .onFailure { error = it.message }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.modules_catalog)) },
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
            error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            repos?.forEach { repo ->
                item {
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
                            releases = emptyList()
                            loadingReleases = true
                            scope.launch {
                                runCatching { ModuleCatalog.releases(repo.fullName) }
                                    .onSuccess { releases = it }
                                    .onFailure { error = it.message }
                                loadingReleases = false
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
                                    modifier = Modifier.padding(top = 10.dp)
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
                                Text(
                                    text = " ${repo.stars}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                repo.language?.let { language ->
                                    Text(
                                        text = "  -  $language",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            if (openRepo == repo.fullName) {
                                if (loadingReleases) {
                                    LinearWavyProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp)
                                    )
                                }

                                releases.forEach { release ->
                                    val zip = release.moduleZip

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = release.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = zip?.name
                                                    ?: stringResource(R.string.modules_catalog_no_zip),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }

                                        PillButton(
                                            onClick = {
                                                if (zip == null) return@PillButton

                                                installing = release.label
                                                scope.launch {
                                                    runCatching {
                                                        val bytes = ModuleCatalog.download(zip.url)
                                                        AdbModuleManager.install(context, bytes)
                                                    }
                                                        .onSuccess { onInstalled() }
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
                                                text = if (installing == release.label) {
                                                    stringResource(R.string.modules_catalog_installing)
                                                } else {
                                                    stringResource(R.string.modules_catalog_install)
                                                },
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    }
                                }

                                if (!loadingReleases && releases.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.modules_catalog_no_releases),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 12.dp)
                                    )
                                }

                                // Whether this repository is already installed, said where the
                                // installing happens rather than left for the list behind to show.
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
