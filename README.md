# WolfTeam 2012 LAN

Windows launcher and local LAN server for playing the September 2012 Softnyx client with friends over a home LAN or Radmin VPN.

## Contents

This is the patch source repository: launcher scripts, LAN discovery and server code, SQL migrations, and the Direct3D compatibility shim. It does **not** contain the commercial WolfTeam client, its game data, locally saved accounts, databases, logs, or the private inventory/visual tools.

You need a separately obtained copy of the 2012 client that you are entitled to use. This repository is source code, not a ready-to-play game installer.

## Getting the latest patch source

- If you cloned this repository, run `git pull` in the repository folder.
- Without Git, use **Code → Download ZIP** on GitHub to get the latest source snapshot.

Build and apply the patch source beside your own compatible client. The full game client remains on your computer and is not part of this repository.

## Playing with friends

1. Install the same 2012 client version on both computers.
2. Connect both computers to the same Radmin VPN network (or the same home LAN).
3. Apply the current patch source to the compatible client installation.
4. Start `WolfteamBaslat.bat`. Choose **1** on the host computer. Once its server is ready, friends choose **2** and select the host from discovery.
5. The host computer keeps the local account and inventory database in `.runtime/logan/data`.

The first launch may ask Windows for firewall permission so other players on the selected private network can connect.

## Building from source

Use Windows PowerShell and a 32-bit-compatible .NET Framework toolchain for the Direct3D shim. Java and the local PHP/MariaDB runtime are prepared by the scripts under `logan/`. Build outputs and downloaded runtimes are local and ignored by Git. Place the source/update files beside your own compatible client before launching it.

`logan/tools/` can generate the local item catalogs from the owner's own 2012 client data. Generated catalogs are intentionally excluded from this repository.

## Privacy

Player names, account data, inventory, local IP configuration, captures, and runtime logs stay on the host computer. Do not commit `.runtime`, `Profiles`, run logs, generated network policy, or game-client files.

## License

Project source is MIT-licensed. The imported Logan server code retains its upstream MIT notice in `logan/LICENSE`. WolfTeam is a Softnyx trademark; this project is an independent community LAN compatibility project and is not affiliated with Softnyx.
