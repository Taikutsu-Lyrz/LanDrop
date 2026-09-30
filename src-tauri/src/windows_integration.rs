use crate::config::settings::AppSettings;
use crate::types::FirewallRuleResult;

#[cfg(target_os = "windows")]
pub fn ensure_firewall_rules(settings: &AppSettings) -> FirewallRuleResult {
    use std::env;

    let exe_path = match env::current_exe() {
        Ok(path) => path.display().to_string(),
        Err(error) => {
            return FirewallRuleResult {
                success: false,
                message: format!("Unable to locate LanDrop executable: {error}"),
                commands: Vec::new(),
            };
        }
    };

    let mut commands = vec![
        "netsh advfirewall firewall delete rule name=\"LanDrop TCP\" >nul 2>&1 || ver >nul".to_string(),
        "netsh advfirewall firewall delete rule name=\"LanDrop UDP Discovery\" >nul 2>&1 || ver >nul".to_string(),
        "netsh advfirewall firewall delete rule name=\"LanDrop Secure Remote\" >nul 2>&1 || ver >nul".to_string(),
        format!(
            "netsh advfirewall firewall add rule name=\"LanDrop TCP\" dir=in action=allow program=\"{}\" protocol=TCP localport={} profile=private,public",
            exe_path, settings.tcp_port
        ),
        format!(
            "netsh advfirewall firewall add rule name=\"LanDrop UDP Discovery\" dir=in action=allow program=\"{}\" protocol=UDP localport={} profile=private,public",
            exe_path, settings.discovery_port
        ),
    ];
    if settings.remote_access_enabled {
        commands.push(format!(
            "netsh advfirewall firewall add rule name=\"LanDrop Secure Remote\" dir=in action=allow program=\"{}\" protocol=TCP localport={} profile=private,public",
            exe_path, settings.remote_listen_port
        ));
    }

    run_elevated_netsh_script(
        "LanDrop firewall setup",
        &commands,
        "Windows Firewall rules were replaced with the current LanDrop ports.",
    )
}

#[cfg(target_os = "windows")]
pub fn remove_firewall_rules() -> FirewallRuleResult {
    let commands = vec![
        "netsh advfirewall firewall delete rule name=\"LanDrop TCP\" >nul 2>&1 || ver >nul".to_string(),
        "netsh advfirewall firewall delete rule name=\"LanDrop UDP Discovery\" >nul 2>&1 || ver >nul".to_string(),
        "netsh advfirewall firewall delete rule name=\"LanDrop Secure Remote\" >nul 2>&1 || ver >nul".to_string(),
    ];
    run_elevated_netsh_script(
        "LanDrop firewall removal",
        &commands,
        "LanDrop Windows Firewall rules were removed.",
    )
}

#[cfg(target_os = "windows")]
pub fn configure_direct_lan_adapter(adapter_name: &str, role: &str) -> FirewallRuleResult {
    let ip = match role {
        "pc1" => "10.77.77.1",
        "pc2" => "10.77.77.2",
        _ => {
            return FirewallRuleResult {
                success: false,
                message: "Choose whether this computer is PC 1 or PC 2.".to_string(),
                commands: Vec::new(),
            };
        }
    };

    let commands = vec![format!(
        "netsh interface ipv4 set address name=\"{}\" static {} 255.255.255.0 none",
        adapter_name, ip
    )];

    run_elevated_netsh_script(
        "LanDrop direct cable IP setup",
        &commands,
        &format!(
            "Direct cable IP applied to {adapter_name}: {ip}. Set the other PC to the opposite role."
        ),
    )
}

#[cfg(target_os = "windows")]
pub fn revert_direct_lan_adapter(adapter_name: &str) -> FirewallRuleResult {
    let commands = vec![
        format!(
            "netsh interface ipv4 set address name=\"{}\" source=dhcp",
            adapter_name
        ),
        format!(
            "netsh interface ipv4 set dnsservers name=\"{}\" source=dhcp",
            adapter_name
        ),
    ];

    run_elevated_netsh_script(
        "LanDrop direct cable IP revert",
        &commands,
        &format!("Reverted {adapter_name} to DHCP."),
    )
}

#[cfg(target_os = "windows")]
fn run_elevated_netsh_script(
    title: &str,
    commands: &[String],
    success_message: &str,
) -> FirewallRuleResult {
    use std::env;
    use std::fs;
    use std::process::Command;

    let script_path = env::temp_dir().join(format!(
        "landrop-net-{}.cmd",
        title
            .chars()
            .map(|ch| if ch.is_ascii_alphanumeric() { ch } else { '-' })
            .collect::<String>()
    ));
    let mut script = String::from("@echo off\r\n");
    for command in commands {
        script.push_str(command);
        script.push_str("\r\nif errorlevel 1 exit /b %errorlevel%\r\n");
    }

    if let Err(error) = fs::write(&script_path, script) {
        return FirewallRuleResult {
            success: false,
            message: format!("Could not prepare direct LAN command script: {error}"),
            commands: commands.to_vec(),
        };
    }

    let script = format!(
        "Start-Process -FilePath 'cmd.exe' -ArgumentList '/c \"{}\"' -Verb RunAs -Wait",
        script_path.display()
    );
    let output = Command::new("powershell")
        .args([
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            &script,
        ])
        .output();

    match output {
        Ok(output) if output.status.success() => FirewallRuleResult {
            success: true,
            message: success_message.to_string(),
            commands: commands.to_vec(),
        },
        Ok(output) => {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let stdout = String::from_utf8_lossy(&output.stdout);
            FirewallRuleResult {
                success: false,
                message: format!(
                    "Windows did not apply the direct LAN command. {}{}",
                    stdout.trim(),
                    stderr.trim()
                ),
                commands: commands.to_vec(),
            }
        }
        Err(error) => FirewallRuleResult {
            success: false,
            message: format!("Could not launch elevated command prompt: {error}"),
            commands: commands.to_vec(),
        },
    }
}

#[cfg(not(target_os = "windows"))]
pub fn ensure_firewall_rules(_settings: &AppSettings) -> FirewallRuleResult {
    FirewallRuleResult {
        success: true,
        message: "Firewall setup is only needed on Windows.".to_string(),
        commands: Vec::new(),
    }
}

#[cfg(not(target_os = "windows"))]
pub fn remove_firewall_rules() -> FirewallRuleResult {
    FirewallRuleResult {
        success: true,
        message: "Firewall removal is only needed on Windows.".to_string(),
        commands: Vec::new(),
    }
}

#[cfg(not(target_os = "windows"))]
pub fn configure_direct_lan_adapter(_adapter_name: &str, _role: &str) -> FirewallRuleResult {
    FirewallRuleResult {
        success: false,
        message: "Direct cable IP setup is only available on Windows.".to_string(),
        commands: Vec::new(),
    }
}

#[cfg(not(target_os = "windows"))]
pub fn revert_direct_lan_adapter(_adapter_name: &str) -> FirewallRuleResult {
    FirewallRuleResult {
        success: false,
        message: "Direct cable IP revert is only available on Windows.".to_string(),
        commands: Vec::new(),
    }
}
