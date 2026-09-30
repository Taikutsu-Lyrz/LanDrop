use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

use walkdir::WalkDir;

use crate::types::{
    now_ms, SendDraft, TransferManifestEntry, TransferManifestEntryKind, TransferTopLevelItem,
};

pub fn build_send_draft(paths: Vec<PathBuf>) -> Result<SendDraft, String> {
    let unique_paths = dedupe_paths(paths);
    if unique_paths.is_empty() {
        return Err("no files or folders were selected".to_string());
    }

    let mut top_level_items = Vec::new();
    let mut manifest_entries = Vec::new();
    let mut manifest_paths = HashSet::new();
    let mut source_paths = Vec::new();
    let mut warnings = Vec::new();
    let mut file_count = 0u32;
    let mut directory_count = 0u32;
    let mut total_bytes = 0u64;
    let mut contains_folders = false;

    for path in unique_paths {
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) => {
                push_warning(
                    &mut warnings,
                    format!("Skipped {}: {error}", path.display()),
                );
                continue;
            }
        };

        source_paths.push(path.display().to_string());

        if metadata.is_file() {
            file_count += 1;
            total_bytes += metadata.len();
            push_manifest_entry(
                &mut manifest_entries,
                &mut manifest_paths,
                TransferManifestEntry {
                    relative_path: file_name(&path),
                    kind: TransferManifestEntryKind::File,
                    size: Some(metadata.len()),
                    modified_at_ms: metadata_modified_ms(&metadata),
                    source_path: Some(path.display().to_string()),
                },
            )?;
            top_level_items.push(TransferTopLevelItem {
                kind: "file".to_string(),
                name: file_name(&path),
                size: Some(metadata.len()),
            });
            continue;
        }

        if metadata.is_dir() {
            contains_folders = true;
            directory_count += 1;
            let root_name = file_name(&path);
            push_manifest_entry(
                &mut manifest_entries,
                &mut manifest_paths,
                TransferManifestEntry {
                    relative_path: root_name.clone(),
                    kind: TransferManifestEntryKind::Directory,
                    size: None,
                    modified_at_ms: metadata_modified_ms(&metadata),
                    source_path: None,
                },
            )?;
            top_level_items.push(TransferTopLevelItem {
                kind: "directory".to_string(),
                name: root_name.clone(),
                size: None,
            });

            for entry in WalkDir::new(&path).follow_links(false).min_depth(1) {
                let entry = match entry {
                    Ok(entry) => entry,
                    Err(error) => {
                        push_warning(
                            &mut warnings,
                            format!("Walk error under {}: {error}", path.display()),
                        );
                        continue;
                    }
                };

                let file_type = entry.file_type();
                if file_type.is_symlink() {
                    push_warning(
                        &mut warnings,
                        format!("Skipped symlink: {}", entry.path().display()),
                    );
                    continue;
                }

                if file_type.is_dir() {
                    directory_count += 1;
                    let relative_path = rooted_relative_path(&root_name, &path, entry.path())?;
                    push_manifest_entry(
                        &mut manifest_entries,
                        &mut manifest_paths,
                        TransferManifestEntry {
                            relative_path,
                            kind: TransferManifestEntryKind::Directory,
                            size: None,
                            modified_at_ms: entry
                                .metadata()
                                .ok()
                                .and_then(|metadata| metadata_modified_ms(&metadata)),
                            source_path: None,
                        },
                    )?;
                    continue;
                }

                if file_type.is_file() {
                    match entry.metadata() {
                        Ok(metadata) => {
                            file_count += 1;
                            total_bytes += metadata.len();
                            let relative_path =
                                rooted_relative_path(&root_name, &path, entry.path())?;
                            push_manifest_entry(
                                &mut manifest_entries,
                                &mut manifest_paths,
                                TransferManifestEntry {
                                    relative_path,
                                    kind: TransferManifestEntryKind::File,
                                    size: Some(metadata.len()),
                                    modified_at_ms: metadata_modified_ms(&metadata),
                                    source_path: Some(entry.path().display().to_string()),
                                },
                            )?;
                        }
                        Err(error) => {
                            push_warning(
                                &mut warnings,
                                format!("Metadata error for {}: {error}", entry.path().display()),
                            );
                        }
                    }
                }
            }
            continue;
        }

        push_warning(
            &mut warnings,
            format!("Skipped unsupported path type: {}", path.display()),
        );
    }

    if top_level_items.is_empty() {
        return Err("no readable files or folders were selected".to_string());
    }

    manifest_entries.sort_by(|left, right| {
        let left_kind = matches!(left.kind, TransferManifestEntryKind::File);
        let right_kind = matches!(right.kind, TransferManifestEntryKind::File);
        left_kind
            .cmp(&right_kind)
            .then_with(|| left.relative_path.len().cmp(&right.relative_path.len()))
            .then_with(|| left.relative_path.cmp(&right.relative_path))
    });

    Ok(SendDraft {
        root_count: top_level_items.len() as u32,
        file_count,
        directory_count,
        total_bytes,
        contains_folders,
        top_level_items,
        manifest_entries,
        source_paths,
        warnings,
        scanned_at_ms: now_ms(),
    })
}

fn dedupe_paths(paths: Vec<PathBuf>) -> Vec<PathBuf> {
    let mut seen = HashSet::new();
    let mut unique = Vec::new();

    for path in paths {
        let normalized = path.to_string_lossy().to_ascii_lowercase();
        if seen.insert(normalized) {
            unique.push(path);
        }
    }

    unique
}

fn push_warning(warnings: &mut Vec<String>, warning: String) {
    if warnings.len() < 24 {
        warnings.push(warning);
    }
}

fn file_name(path: &Path) -> String {
    path.file_name()
        .map(|name| name.to_string_lossy().to_string())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| path.display().to_string())
}

fn rooted_relative_path(
    root_name: &str,
    root_path: &Path,
    full_path: &Path,
) -> Result<String, String> {
    let relative = full_path
        .strip_prefix(root_path)
        .map_err(|error| error.to_string())?;
    let relative = relative.to_string_lossy().replace('\\', "/");
    if relative.is_empty() {
        return Ok(root_name.to_string());
    }
    Ok(format!("{root_name}/{relative}"))
}

fn metadata_modified_ms(metadata: &fs::Metadata) -> Option<u64> {
    metadata
        .modified()
        .ok()
        .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis() as u64)
}

fn push_manifest_entry(
    manifest_entries: &mut Vec<TransferManifestEntry>,
    manifest_paths: &mut HashSet<String>,
    entry: TransferManifestEntry,
) -> Result<(), String> {
    let key = entry.relative_path.to_ascii_lowercase();
    if !manifest_paths.insert(key) {
        return Err(format!(
            "selected items would collide at {} on the receiving device",
            entry.relative_path
        ));
    }

    manifest_entries.push(entry);
    Ok(())
}
