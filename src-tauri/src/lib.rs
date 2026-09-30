mod app;
mod commands;
mod config;
mod discovery;
mod error;
mod events;
mod logging;
mod notifications;
mod remote;
mod remote_codec;
mod transfer;
mod types;
mod windows_integration;
mod wifi_direct;

use app::AppState;
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    logging::init();

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .manage(AppState::new())
        .setup(|app| {
            let state = app.state::<AppState>().inner().clone();
            tauri::async_runtime::block_on(state.initialize_persistence())?;
            state.start_discovery(app.handle().clone());
            state.start_control_server(app.handle().clone());
            state.start_remote_server(app.handle().clone());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            wifi_direct::direct_wifi,
            commands::get_app_bootstrap,
            commands::get_platform_capabilities,
            commands::get_diagnostics_report,
            commands::refresh_discovery,
            commands::set_receive_enabled,
            commands::set_remote_access_enabled,
            commands::set_remote_auto_port_mapping,
            commands::create_remote_invite,
            commands::create_overlay_invite,
            commands::add_remote_peer,
            commands::get_remote_access_status,
            commands::refresh_remote_mapping,
            commands::reset_remote_identity,
            commands::set_device_name,
            commands::set_require_pair_code,
            commands::set_quick_save_mode,
            commands::untrust_device,
            commands::clear_trusted_devices,
            commands::set_theme_preference,
            commands::set_concurrent_file_streams,
            commands::set_default_receive_folder,
            commands::pick_default_receive_folder,
            commands::set_adapter_priority_order,
            commands::set_disabled_adapter_ids,
            commands::set_device_kind,
            commands::regenerate_pair_code,
            commands::pick_send_files,
            commands::pick_send_folder,
            commands::stage_dropped_paths,
            commands::clear_send_draft,
            commands::pick_receive_folder,
            commands::add_manual_peer,
            commands::send_staged_offer,
            commands::respond_to_incoming_offer,
            commands::cancel_transfer,
            commands::set_transfer_paused,
            commands::retry_transfer,
            commands::clear_transfer_history,
            commands::delete_transfer_history_item,
            commands::ensure_firewall_rules,
            commands::remove_firewall_rules,
            commands::configure_direct_lan,
            commands::revert_direct_lan
        ])
        .run(tauri::generate_context!())
        .expect("failed to run LanDrop app");
}
