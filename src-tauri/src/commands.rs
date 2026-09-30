use std::path::PathBuf;

use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;

use crate::app::AppState;
use crate::config::settings::{AppSettings, QuickSaveMode, ThemePreference};
use crate::events;
use crate::transfer;
use crate::types::{
    AppBootstrap, FirewallRuleResult, PlatformCapabilities, SendDraft, TransferSummary,
};
use crate::windows_integration;

#[tauri::command]
pub async fn get_app_bootstrap(state: State<'_, AppState>) -> Result<AppBootstrap, String> {
    Ok(state.bootstrap().await)
}

#[tauri::command]
pub async fn get_platform_capabilities() -> Result<PlatformCapabilities, String> {
    Ok(platform_capabilities())
}

#[tauri::command]
pub async fn get_diagnostics_report(state: State<'_, AppState>) -> Result<String, String> {
    let bootstrap = state.bootstrap().await;
    let mut settings = bootstrap.settings.clone();
    settings.pair_code = if settings.require_pair_code {
        "******".to_string()
    } else {
        "off".to_string()
    };

    let report = serde_json::json!({
        "app": bootstrap.app_name,
        "generatedAtMs": crate::types::now_ms(),
        "statePath": state.persistence_path().await.map(|path| path.display().to_string()),
        "settings": settings,
        "localAdapters": bootstrap.local_adapters,
        "peers": bootstrap.peers,
        "incomingOffer": bootstrap.incoming_offer,
        "sendDraft": bootstrap.send_draft.as_ref().map(|draft| serde_json::json!({
            "fileCount": draft.file_count,
            "directoryCount": draft.directory_count,
            "totalBytes": draft.total_bytes,
            "topLevelItems": draft.top_level_items,
        })),
        "transfers": bootstrap.transfers,
    });

    serde_json::to_string_pretty(&report).map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn refresh_discovery(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<AppBootstrap, String> {
    let bootstrap = state.refresh_discovery().await;
    events::emit_devices_updated(&app, &bootstrap.peers)?;
    Ok(bootstrap)
}

#[tauri::command]
pub async fn set_receive_enabled(
    app: AppHandle,
    state: State<'_, AppState>,
    enabled: bool,
) -> Result<AppSettings, String> {
    let settings = state.set_receive_enabled(enabled).await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn set_remote_access_enabled(
    app: AppHandle,
    state: State<'_, AppState>,
    enabled: bool,
) -> Result<AppSettings, String> {
    let settings = state.set_remote_access_enabled(enabled).await;
    if enabled {
        if let Err(error) = crate::remote::ensure_listener(state.inner().clone(), app.clone()).await
        {
            let settings = state.set_remote_access_enabled(false).await;
            state
                .set_remote_access_status(crate::types::RemoteAccessStatus::disabled())
                .await;
            events::emit_settings_updated(&app, &settings)?;
            return Err(error);
        }
        let restart_state = state.inner().clone();
        let restart_app = app.clone();
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            let still_enabled = restart_state
                .bootstrap()
                .await
                .settings
                .remote_access_enabled;
            if still_enabled && !restart_state.is_remote_server_running() {
                if let Err(error) =
                    crate::remote::ensure_listener(restart_state.clone(), restart_app.clone()).await
                {
                    tracing::warn!("remote listener delayed restart failed: {error}");
                    let settings = restart_state.set_remote_access_enabled(false).await;
                    restart_state
                        .set_remote_access_status(crate::types::RemoteAccessStatus::disabled())
                        .await;
                    let _ = events::emit_settings_updated(&restart_app, &settings);
                }
            }
        });
    } else {
        state
            .set_remote_access_status(crate::types::RemoteAccessStatus::disabled())
            .await;
    }
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn set_remote_auto_port_mapping(
    app: AppHandle,
    state: State<'_, AppState>,
    enabled: bool,
) -> Result<AppSettings, String> {
    let settings = state.set_remote_auto_port_mapping(enabled).await;
    if settings.remote_access_enabled {
        let _ = crate::remote::refresh_mapping(state.inner().clone()).await;
    }
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn create_remote_invite(
    state: State<'_, AppState>,
    public_host: String,
) -> Result<String, String> {
    crate::remote::create_invite(state.inner().clone(), public_host).await
}

#[tauri::command]
pub async fn create_overlay_invite(
    state: State<'_, AppState>,
    overlay_endpoint: String,
) -> Result<String, String> {
    crate::remote::create_overlay_invite(state.inner().clone(), overlay_endpoint).await
}

#[tauri::command]
pub async fn get_remote_access_status(
    state: State<'_, AppState>,
) -> Result<crate::types::RemoteAccessStatus, String> {
    Ok(state.remote_access_status().await)
}

#[tauri::command]
pub async fn refresh_remote_mapping(
    state: State<'_, AppState>,
) -> Result<crate::types::RemoteAccessStatus, String> {
    Ok(crate::remote::refresh_mapping(state.inner().clone()).await)
}

#[tauri::command]
pub async fn reset_remote_identity(state: State<'_, AppState>) -> Result<(), String> {
    crate::remote::reset_identity(state.inner().clone()).await
}

#[tauri::command]
pub async fn add_remote_peer(
    app: AppHandle,
    state: State<'_, AppState>,
    invite: String,
) -> Result<crate::types::PeerDevice, String> {
    crate::remote::connect_invite(state.inner().clone(), app, invite).await
}

#[tauri::command]
pub async fn set_device_name(
    app: AppHandle,
    state: State<'_, AppState>,
    device_name: String,
) -> Result<AppSettings, String> {
    let settings = state.set_device_name(device_name).await?;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn set_require_pair_code(
    app: AppHandle,
    state: State<'_, AppState>,
    enabled: bool,
) -> Result<AppSettings, String> {
    let settings = state.set_require_pair_code(enabled).await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn set_quick_save_mode(
    app: AppHandle,
    state: State<'_, AppState>,
    quick_save_mode: QuickSaveMode,
) -> Result<AppSettings, String> {
    let settings = state.set_quick_save_mode(quick_save_mode).await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn untrust_device(
    app: AppHandle,
    state: State<'_, AppState>,
    device_id: String,
) -> Result<AppSettings, String> {
    let settings = state.untrust_device(&device_id).await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn clear_trusted_devices(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<AppSettings, String> {
    let settings = state.clear_trusted_devices().await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn set_theme_preference(
    app: AppHandle,
    state: State<'_, AppState>,
    theme: ThemePreference,
) -> Result<AppSettings, String> {
    let settings = state.set_theme(theme).await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn set_concurrent_file_streams(
    app: AppHandle,
    state: State<'_, AppState>,
    count: u16,
) -> Result<AppSettings, String> {
    let settings = state.set_concurrent_file_streams(count).await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn set_default_receive_folder(
    app: AppHandle,
    state: State<'_, AppState>,
    folder: Option<String>,
) -> Result<AppSettings, String> {
    let settings = state.set_default_receive_folder(folder).await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
#[cfg(target_os = "windows")]
pub async fn pick_default_receive_folder(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<Option<AppSettings>, String> {
    let selection = app
        .dialog()
        .file()
        .set_title("Choose default receive folder")
        .blocking_pick_folder();

    let Some(selection) = selection else {
        return Ok(None);
    };

    let path = selection.into_path().map_err(|error| error.to_string())?;
    let settings = state
        .set_default_receive_folder(Some(path.display().to_string()))
        .await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(Some(settings))
}

#[tauri::command]
#[cfg(not(target_os = "windows"))]
pub async fn pick_default_receive_folder(
    _app: AppHandle,
    _state: State<'_, AppState>,
) -> Result<Option<AppSettings>, String> {
    Err("Default receive folder picking is not available on this platform yet.".to_string())
}

#[tauri::command]
pub async fn set_adapter_priority_order(
    app: AppHandle,
    state: State<'_, AppState>,
    adapter_ids: Vec<String>,
) -> Result<AppSettings, String> {
    let settings = state.set_adapter_priority_order(adapter_ids).await;
    events::emit_settings_updated(&app, &settings)?;
    let bootstrap = state.refresh_discovery().await;
    events::emit_devices_updated(&app, &bootstrap.peers)?;
    Ok(settings)
}

#[tauri::command]
pub async fn set_disabled_adapter_ids(
    app: AppHandle,
    state: State<'_, AppState>,
    adapter_ids: Vec<String>,
) -> Result<AppSettings, String> {
    let settings = state.set_disabled_adapter_ids(adapter_ids).await;
    events::emit_settings_updated(&app, &settings)?;
    let bootstrap = state.refresh_discovery().await;
    events::emit_devices_updated(&app, &bootstrap.peers)?;
    Ok(settings)
}

#[tauri::command]
pub async fn set_device_kind(
    app: AppHandle,
    state: State<'_, AppState>,
    device_kind: String,
) -> Result<AppSettings, String> {
    let settings = state.set_device_kind(device_kind).await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn regenerate_pair_code(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<AppSettings, String> {
    let settings = state.regenerate_pair_code().await;
    events::emit_settings_updated(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
pub async fn pick_send_files(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<Option<SendDraft>, String> {
    let selection = app
        .dialog()
        .file()
        .set_title("Choose files to send")
        .blocking_pick_files();

    let Some(selection) = selection else {
        return Ok(None);
    };

    let paths = convert_dialog_paths(selection)?;
    let draft = state.stage_send_paths(paths).await?;
    events::emit_send_draft_updated(&app, &Some(draft.clone()))?;
    Ok(Some(draft))
}

#[tauri::command]
#[cfg(target_os = "windows")]
pub async fn pick_send_folder(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<Option<SendDraft>, String> {
    let selection = app
        .dialog()
        .file()
        .set_title("Choose a folder to send")
        .blocking_pick_folder();

    let Some(selection) = selection else {
        return Ok(None);
    };

    let path = selection.into_path().map_err(|error| error.to_string())?;
    let draft = state.stage_send_paths(vec![path]).await?;
    events::emit_send_draft_updated(&app, &Some(draft.clone()))?;
    Ok(Some(draft))
}

#[tauri::command]
#[cfg(not(target_os = "windows"))]
pub async fn pick_send_folder(
    _app: AppHandle,
    _state: State<'_, AppState>,
) -> Result<Option<SendDraft>, String> {
    Err("Folder sending is not available on this platform yet.".to_string())
}

#[tauri::command]
pub async fn stage_dropped_paths(
    app: AppHandle,
    state: State<'_, AppState>,
    paths: Vec<String>,
) -> Result<Option<SendDraft>, String> {
    if paths.is_empty() {
        return Ok(None);
    }

    let paths = paths.into_iter().map(PathBuf::from).collect::<Vec<_>>();
    let draft = state.stage_send_paths(paths).await?;
    events::emit_send_draft_updated(&app, &Some(draft.clone()))?;
    Ok(Some(draft))
}

#[tauri::command]
pub async fn clear_send_draft(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    state.clear_send_draft().await;
    events::emit_send_draft_updated(&app, &None)?;
    Ok(())
}

#[tauri::command]
#[cfg(target_os = "windows")]
pub async fn pick_receive_folder(app: AppHandle) -> Result<Option<String>, String> {
    let selection = app
        .dialog()
        .file()
        .set_title("Choose where incoming files should be saved")
        .blocking_pick_folder();

    let Some(selection) = selection else {
        return Ok(None);
    };

    let path = selection.into_path().map_err(|error| error.to_string())?;
    Ok(Some(path.display().to_string()))
}

#[tauri::command]
#[cfg(not(target_os = "windows"))]
pub async fn pick_receive_folder(_app: AppHandle) -> Result<Option<String>, String> {
    Err("Receive folder picking is not available on this platform yet.".to_string())
}

#[tauri::command]
pub async fn send_staged_offer(
    app: AppHandle,
    state: State<'_, AppState>,
    target_device_id: String,
    pair_code: Option<String>,
) -> Result<TransferSummary, String> {
    transfer::control::send_offer(state.inner().clone(), app, target_device_id, pair_code).await
}

#[tauri::command]
pub async fn respond_to_incoming_offer(
    app: AppHandle,
    state: State<'_, AppState>,
    transfer_id: String,
    accept: bool,
    save_path: Option<String>,
) -> Result<(), String> {
    transfer::control::respond_to_offer(state.inner().clone(), app, transfer_id, accept, save_path)
        .await
}

#[tauri::command]
pub async fn add_manual_peer(
    app: AppHandle,
    state: State<'_, AppState>,
    ip: String,
    port: Option<u16>,
) -> Result<crate::types::PeerDevice, String> {
    transfer::control::probe_manual_peer(state.inner().clone(), app, ip, port).await
}

#[tauri::command]
pub async fn cancel_transfer(
    app: AppHandle,
    state: State<'_, AppState>,
    transfer_id: String,
) -> Result<(), String> {
    transfer::control::cancel_transfer(state.inner().clone(), app, transfer_id).await
}

#[tauri::command]
pub async fn set_transfer_paused(
    app: AppHandle,
    state: State<'_, AppState>,
    transfer_id: String,
    paused: bool,
) -> Result<(), String> {
    transfer::control::set_transfer_paused(state.inner().clone(), app, transfer_id, paused).await
}

#[tauri::command]
pub async fn retry_transfer(
    app: AppHandle,
    state: State<'_, AppState>,
    transfer_id: String,
) -> Result<TransferSummary, String> {
    transfer::control::retry_transfer(state.inner().clone(), app, transfer_id).await
}

#[tauri::command]
pub async fn clear_transfer_history(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<(), String> {
    state.clear_transfer_history().await;
    events::emit_transfers_updated(&app, &state.transfers().await)?;
    Ok(())
}

#[tauri::command]
pub async fn delete_transfer_history_item(
    app: AppHandle,
    state: State<'_, AppState>,
    transfer_id: String,
) -> Result<(), String> {
    state.delete_transfer_history_item(&transfer_id).await;
    events::emit_transfers_updated(&app, &state.transfers().await)?;
    Ok(())
}

#[tauri::command]
pub async fn ensure_firewall_rules(
    state: State<'_, AppState>,
) -> Result<FirewallRuleResult, String> {
    let settings = state.bootstrap().await.settings;
    Ok(windows_integration::ensure_firewall_rules(&settings))
}

#[tauri::command]
pub async fn remove_firewall_rules() -> Result<FirewallRuleResult, String> {
    Ok(windows_integration::remove_firewall_rules())
}

#[tauri::command]
pub async fn configure_direct_lan(
    state: State<'_, AppState>,
    adapter_id: String,
    role: String,
) -> Result<FirewallRuleResult, String> {
    let bootstrap = state.bootstrap().await;
    let adapter = bootstrap
        .local_adapters
        .iter()
        .find(|adapter| adapter.id == adapter_id)
        .ok_or_else(|| "Network adapter was not found.".to_string())?;
    Ok(windows_integration::configure_direct_lan_adapter(
        &adapter.friendly_name,
        &role,
    ))
}

#[tauri::command]
pub async fn revert_direct_lan(
    state: State<'_, AppState>,
    adapter_id: String,
) -> Result<FirewallRuleResult, String> {
    let bootstrap = state.bootstrap().await;
    let adapter = bootstrap
        .local_adapters
        .iter()
        .find(|adapter| adapter.id == adapter_id)
        .ok_or_else(|| "Network adapter was not found.".to_string())?;
    Ok(windows_integration::revert_direct_lan_adapter(
        &adapter.friendly_name,
    ))
}

fn convert_dialog_paths(paths: Vec<tauri_plugin_dialog::FilePath>) -> Result<Vec<PathBuf>, String> {
    paths
        .into_iter()
        .map(|path| path.into_path().map_err(|error| error.to_string()))
        .collect()
}

fn platform_capabilities() -> PlatformCapabilities {
    PlatformCapabilities {
        platform: platform_name().to_string(),
        can_pick_folders: cfg!(target_os = "windows"),
        can_drag_drop_files: cfg!(target_os = "windows"),
        can_configure_firewall: cfg!(target_os = "windows"),
        supports_network_adapter_ordering: cfg!(target_os = "windows"),
        supports_android_nsd: false,
        supports_notifications: true,
    }
}

fn platform_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "android") {
        "android"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else if cfg!(target_os = "linux") {
        "linux"
    } else {
        "unknown"
    }
}
