// Prevents an extra console window on Windows in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // Started by the client mod for its music module: no window, just the helper.
    if std::env::args().nth(1).as_deref() == Some("--media-helper") {
        slopclient_launcher_lib::media_helper();
        return;
    }
    slopclient_launcher_lib::run()
}
