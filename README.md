# Pure Home: a minimal launcher for your Xiaomi TV (MITV-AXSO2, Android TV 11)

A 63 KB home screen with no ads, no recommendations and no permissions.

## What's here
| File | What it does |
| --- | --- |
| `PureHome.apk` + `.sha256` | The launcher and its checksum (the installer refuses a file that doesn't match) |
| `install.sh` | Takes a snapshot, installs, sets Home, disables only PatchWall if needed, verifies, rolls back on failure |
| `debloat.sh` | Optional. Switches off the reviewed list in `lists/xiaomi-patchwall.txt` after asking |
| `restore.sh` | Puts the TV back exactly as the snapshot recorded it (`--uninstall` also removes Pure Home) |
| `lib.sh` | Shared helpers: input validation, protected-package guard |
| `capture-screens.sh` | Optional. Takes before/after screenshots into `screenshots/` |
| `backups/` | Created on first run (not committed). Holds the snapshots that `restore.sh` uses |
| `source/` | Full source (plain Java, no libraries). Rebuild with `source/build.sh`; it generates a local signing key on first build, so a self-built APK can't update one signed by another key (uninstall first) |

## One-time TV setup
1. **Settings › Device Preferences › About**: highlight **Build** and press OK 7 times.
2. **Settings › Device Preferences › Developer options**: turn on **USB debugging**.
3. Note the TV's IP address: **Settings › Network & Internet › your Wi-Fi**.

## Install (Mac on the same Wi-Fi)
    git clone <this repo> && cd PureHome
    ./install.sh 192.168.1.42
Accept "Allow USB debugging?" on the TV. When it finishes, the script asks you to turn USB debugging off and checks that the port is closed.

## Safety model
- **The launcher requests zero Android permissions.** No internet, storage or network-state access. It has no services, providers or receivers in the manifest, and `allowBackup` is off.
- **It only ever opens the exact app component the system reported.** Favourites are validated as package names before use.
- **Nothing is uninstalled.** Changes use `pm disable-user`, which is the same as Settings › Apps › Disable.
- **PatchWall is switched off only after Pure Home is installed,** and only if the TV still sends Home to it. If Home doesn't land on Pure Home, the script turns PatchWall back on automatically.
- **Protected packages are never touched,** whatever a list says: Settings, inputs/HDMI, Live TV, the remote and Bluetooth, firmware updates, the keyboard, Play services, the package installer and the system UI.
- **`debloat.sh` asks before changing anything,** and refuses to run unless Pure Home is already the home screen.
- **The scripts are strict.** They validate the IP address, check the APK checksum and run with `set -euo pipefail`.

## Using it
- Arrows move focus. **OK** opens an app. **Hold OK** or press **Menu** for options: favourite, move, hide, App info.
- The 4th favourite is **Airtel TV** (HDMI 2): OK switches the TV to your set-top box.
- The top-right buttons are **Inputs**, **Network**, **Settings** and **Edit**. Edit lets you show hidden apps or reset favourites.
- If you turn off animations in Developer options, the launcher respects that.

The scripts and the Xiaomi list are adapted from ideas in CleanYourTV (MIT): https://github.com/Gathin23/cleanyourtv
