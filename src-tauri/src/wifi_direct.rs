use serde::Serialize;

#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DirectWifiDeviceInfo {
    name: String,
    status: String,
}

#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DirectWifiStatus {
    state: String,
    ssid: Option<String>,
    password: Option<String>,
    addresses: Vec<String>,
    devices: Vec<DirectWifiDeviceInfo>,
}

#[tauri::command]
pub async fn direct_wifi(action: String) -> Result<DirectWifiStatus, String> {
    if !matches!(action.as_str(), "start" | "stop" | "status") {
        return Err("Unknown direct Wi-Fi action".into());
    }
    #[cfg(windows)]
    return tauri::async_runtime::spawn_blocking(move || native::request(action))
        .await
        .map_err(|error| error.to_string())?;
    #[cfg(not(windows))]
    Err("Direct Wi-Fi hosting requires Windows.".into())
}

#[cfg(windows)]
mod native {
    use super::*;
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{mpsc, Arc, Mutex, OnceLock};
    use windows::{
        core::HSTRING,
        Devices::WiFiDirect::*,
        Foundation::TypedEventHandler,
        Win32::System::WinRT::{RoInitialize, RoUninitialize, RO_INIT_MULTITHREADED},
    };

    type Reply = mpsc::Sender<Result<DirectWifiStatus, String>>;
    static COMMANDS: OnceLock<mpsc::Sender<(String, Reply)>> = OnceLock::new();

    struct Host {
        publisher: WiFiDirectAdvertisementPublisher,
        listener: WiFiDirectConnectionListener,
        token: i64,
        devices: Arc<Mutex<Vec<WiFiDirectDevice>>>,
        /// Device id -> display name, captured from the connection request
        /// (WiFiDirectDevice itself exposes no name property).
        names: Arc<Mutex<HashMap<String, String>>>,
        ssid: String,
        password: String,
        accepting: Arc<AtomicBool>,
    }

    impl Host {
        fn start() -> windows::core::Result<Self> {
            let publisher = WiFiDirectAdvertisementPublisher::new()?;
            let advertisement = publisher.Advertisement()?;
            advertisement.SetIsAutonomousGroupOwnerEnabled(true)?;
            let legacy = advertisement.LegacySettings()?;
            legacy.SetIsEnabled(true)?;
            let id = uuid::Uuid::new_v4().simple().to_string();
            let ssid = format!("DIRECT-LanDrop-{}", &id[..6]);
            let password = uuid::Uuid::new_v4().simple().to_string()[..20].to_string();
            legacy.SetSsid(&HSTRING::from(&ssid))?;
            legacy
                .Passphrase()?
                .SetPassword(&HSTRING::from(&password))?;
            let listener = WiFiDirectConnectionListener::new()?;
            let devices = Arc::new(Mutex::new(Vec::new()));
            let names: Arc<Mutex<HashMap<String, String>>> = Arc::new(Mutex::new(HashMap::new()));
            let connected = devices.clone();
            let connected_names = names.clone();
            let accepting = Arc::new(AtomicBool::new(true));
            let accept_connections = accepting.clone();
            let token = listener.ConnectionRequested(&TypedEventHandler::<
                WiFiDirectConnectionListener,
                WiFiDirectConnectionRequestedEventArgs,
            >::new(move |_, args| {
                if let Some(args) = args.as_ref() {
                    let request = args.GetConnectionRequest()?;
                        let result: windows::core::Result<()> = (|| {
                            if !accept_connections.load(Ordering::Acquire) {
                                return Ok(());
                            }
                            let info = request.DeviceInformation()?;
                            let device_id = info.Id()?;
                            let id = device_id.to_string();
                            let peer_name = info
                                .Name()
                                .map(|name| name.to_string())
                                .unwrap_or_else(|_| "Connected device".to_string());
                            let device = WiFiDirectDevice::FromIdAsync(&device_id)?.get()?;
                            if let Ok(mut devices) = connected.lock() {
                                if accept_connections.load(Ordering::Acquire) {
                                    devices.retain(|existing: &WiFiDirectDevice| {
                                        if existing
                                            .ConnectionStatus()
                                            .is_ok_and(|s| s == WiFiDirectConnectionStatus::Connected)
                                        {
                                            true
                                        } else {
                                            let _ = existing.Close();
                                            false
                                        }
                                    });
                                    devices.push(device);
                                    if let Ok(mut label) = connected_names.lock() {
                                        // Drop names of pruned devices.
                                        let live: Vec<String> = devices
                                            .iter()
                                            .filter_map(|d| {
                                                d.DeviceId().ok().map(|v| v.to_string())
                                            })
                                            .collect();
                                        label.retain(|key, _| live.contains(key));
                                        label.insert(id, peer_name);
                                    }
                                } else {
                                    let _ = device.Close();
                                }
                            } else {
                                let _ = device.Close();
                            }
                            Ok(())
                        })();
                    let _ = request.Close();
                    result?;
                }
                Ok(())
            }))?;
            let host = Self {
                publisher,
                listener,
                token,
                devices,
                names,
                ssid,
                password,
                accepting,
            };
            host.publisher.Start()?;
            Ok(host)
        }

        fn status(&self) -> Result<DirectWifiStatus, String> {
            let status = self.publisher.Status().map_err(|e| e.to_string())?;
            let state = match status {
                WiFiDirectAdvertisementPublisherStatus::Started => "started",
                WiFiDirectAdvertisementPublisherStatus::Created => "starting",
                WiFiDirectAdvertisementPublisherStatus::Stopped => "stopped",
                WiFiDirectAdvertisementPublisherStatus::Aborted => "unavailable",
                _ => "starting",
            };
            let addresses = ipconfig::get_adapters()
                .unwrap_or_default()
                .iter()
                .filter(|a| a.description().to_lowercase().contains("wi-fi direct"))
                .flat_map(|a| a.ip_addresses().iter().copied())
                .filter(|ip| ip.is_ipv4() && !ip.is_loopback() && !ip.is_unspecified())
                .map(|ip| ip.to_string())
                .collect();
            // Surface the peers Windows accepted into the group. The
            // ConnectionRequested handler only retains connected devices, but
            // links can drop afterwards, so re-check and prune here.
            let mut devices = Vec::new();
            if let Ok(mut connected) = self.devices.lock() {
                connected.retain(|existing| {
                    let alive = existing
                        .ConnectionStatus()
                        .is_ok_and(|s| s == WiFiDirectConnectionStatus::Connected);
                    if !alive {
                        let _ = existing.Close();
                    }
                    alive
                });
                let labels = self.names.lock().ok();
                for device in connected.iter() {
                    let id = device
                        .DeviceId()
                        .map(|value| value.to_string())
                        .unwrap_or_default();
                    let name = labels
                        .as_ref()
                        .and_then(|map| map.get(&id).cloned())
                        .unwrap_or_else(|| "Connected device".to_string());
                    devices.push(DirectWifiDeviceInfo {
                        name,
                        status: "connected".to_string(),
                    });
                }
            }
            Ok(DirectWifiStatus {
                state: state.into(),
                ssid: Some(self.ssid.clone()),
                password: Some(self.password.clone()),
                addresses,
                devices,
            })
        }
    }

    impl Drop for Host {
        fn drop(&mut self) {
            self.accepting.store(false, Ordering::Release);
            let _ = self.listener.RemoveConnectionRequested(self.token);
            let _ = self.publisher.Stop();
            if let Ok(mut devices) = self.devices.lock() {
                for device in devices.drain(..) {
                    let _ = device.Close();
                }
            }
        }
    }

    pub(super) fn request(action: String) -> Result<DirectWifiStatus, String> {
        let commands = COMMANDS.get_or_init(|| {
            let (sender, receiver) = mpsc::channel::<(String, Reply)>();
            std::thread::spawn(move || {
                let initialized = unsafe { RoInitialize(RO_INIT_MULTITHREADED) };
                let mut host: Option<Host> = None;
                for (action, reply) in receiver {
                    let result = (|| {
                        initialized.as_ref().map_err(|e| e.to_string())?;
                        match action.as_str() {
                            "start" => {
                                if host.as_ref().is_some_and(|h| h.status().is_ok_and(|s| s.state == "started" || s.state == "starting")) {
                                    return host.as_ref().unwrap().status();
                                }
                                host.take();
                                host = Some(Host::start().map_err(|e| format!("Cannot host direct Wi-Fi: {e}. Check that Wi-Fi is on and Windows Mobile Hotspot is off."))?);
                            }
                            "stop" => { host.take(); }
                            _ => {}
                        }
                        host.as_ref().map(Host::status).unwrap_or_else(|| Ok(DirectWifiStatus {
                            state: "stopped".into(), ..Default::default()
                        }))
                    })();
                    let _ = reply.send(result);
                }
                drop(host);
                if initialized.is_ok() { unsafe { RoUninitialize() }; }
            });
            sender
        });
        let (reply, response) = mpsc::channel();
        commands.send((action, reply)).map_err(|e| e.to_string())?;
        response.recv().map_err(|e| e.to_string())?
    }

    #[test]
    fn status_and_stop_are_safe_before_start() {
        assert_eq!(request("status".into()).unwrap().state, "stopped");
        let stopped = request("stop".into()).unwrap();
        assert_eq!(stopped.state, "stopped");
        assert!(stopped.password.is_none());
    }

    #[test]
    #[ignore = "briefly creates a real Wi-Fi Direct network; run explicitly on supported hardware"]
    fn hardware_host_starts_and_stops() {
        struct StopOnExit;
        impl Drop for StopOnExit {
            fn drop(&mut self) { let _ = request("stop".into()); }
        }
        let _cleanup = StopOnExit;
        let mut status = request("start".into()).unwrap();
        for _ in 0..20 {
            if status.state == "started" || status.state == "unavailable" { break; }
            std::thread::sleep(std::time::Duration::from_millis(500));
            status = request("status".into()).unwrap();
        }
        assert_eq!(status.state, "started", "Windows did not start the direct network");
        assert!(status.password.as_ref().is_some_and(|p| p.len() >= 8));
        assert_eq!(request("stop".into()).unwrap().state, "stopped");
    }
}
