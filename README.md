# Controller Reconnect Fix Mod

Fixes a long-standing controller reconnection issue in `desktop-1.0.jar` where controller input may not recover after disconnect/reconnect.

## What it does

- DirectInput path (`CInputHelper`):
  - Polls controller list.
  - Rebinds to an available controller when current binding is invalid.
  - Forces a controller manager refresh on prolonged disconnect (with cooldown) to recover hotplug edge cases.
- SteamInput path (`SteamInputHelper`):
  - Polls connected handles.
  - Re-initializes action handles when controller handle changes after reconnect.

## Prerequisites

- Java 8+ available on PATH.
- Slay the Spire installed under Steam.
- Dependencies present:
  - `desktop-1.0.jar`
  - `ModTheSpire.jar`
  - `BaseMod.jar`

By default this project reads dependencies from:

- `E:/SteamLibrary/steamapps/common/SlayTheSpire/desktop-1.0.jar`
- `E:/SteamLibrary/steamapps/workshop/content/646570/1605060445/ModTheSpire.jar`
- `E:/SteamLibrary/steamapps/workshop/content/646570/1605833019/BaseMod.jar`

You can override with env var:

- `STEAM_PATH` = Steam `steamapps` directory

## Build

PowerShell:

```powershell
cd D:\Desktop\StSControllerReconnectFixMod
gradle clean jar
```

Output:

- `build/libs/ControllerReconnectFix-1.0.0.jar`

## Install

Copy the jar into STS mod folder:

- `E:/SteamLibrary/steamapps/common/SlayTheSpire/mods`

Or run:

```powershell
gradle copyToMods
```

## Verify

1. Launch with ModTheSpire + BaseMod + this mod enabled.
2. Enter game using controller.
3. Unplug controller, wait 2-5 seconds, reconnect.
4. Confirm input recovers without restarting game.
