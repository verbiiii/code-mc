# code-mc
Write python inside of minecraft.

## Super basic in-game usage

Open in-game chat and in the top left you should see a button saying "open xos".

Java side connects to:
- `ws://127.0.0.1:8050/socket` (binary action/observation stream)

Python server listens on:
- `0.0.0.0:8050`

---

## Requirements

### Core
- Java 17
- Git
- Python 3.10+ (3.11 works too)

### Mod versions used by this repo
- Minecraft `1.20.1`
- Forge `47.4.3`

---

## 1) Clone + submodules

This project includes TACZ as a git submodule under `mods/TACZ`. You need it initialized or the Gradle build will fail.

```bash
git clone <your-repo-url>
cd escape-from-minekov
git submodule update --init --recursive
```

If you already cloned earlier and TACZ is missing:

```bash
git submodule sync --recursive
git submodule update --init --recursive
```

---

## 2) Python backend setup (AI server)

From the repo root:

### Windows (PowerShell)
```powershell
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install fastapi uvicorn torch numpy
```

### macOS/Linux
```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
pip install fastapi uvicorn torch numpy
```

Start the server:

```bash
cd py
python main.py
```

You should see a startup log similar to:
- `Starting Minekov Vectorized RL Server`

Keep this running while Minecraft is open.

---

## 3) Forge mod setup

From the repo root:

### VSCode/Cursor (recommended)
You do **not** need to run `genIntellijRuns` or `genEclipseRuns` when using VSCode/Cursor (or any terminal-first workflow).

Just run `runClient` from the repo root:
- Windows: `.\gradlew.bat runClient`
- macOS/Linux: `./gradlew runClient`

`runClient` handles the required setup steps for this workflow.

> First install/build can take 60+ minutes because TACZ and other mod dependencies are large and need to be downloaded/mapped/processed.

### IDE-specific footnotes
- IntelliJ:
  - Import the project as a Gradle project
  - Optional run generation:
    - Windows: `.\gradlew.bat genIntellijRuns`
    - macOS/Linux: `./gradlew genIntellijRuns`
  - Then run the generated `runClient` configuration
- Eclipse:
  - Optional run generation:
    - Windows: `.\gradlew.bat genEclipseRuns`
    - macOS/Linux: `./gradlew genEclipseRuns`
  - Import as Existing Gradle Project

---

## 4) Run order (important)

1. Start Python server first (`cd py && python main.py`)
2. Start Minecraft mod client (`runClient`)
3. Join world/server and use Minekov commands

The mod reconnects to Python automatically every second if the server comes up later, but starting Python first is simplest.

---

## 5) Basic in-game usage

Command root:
- `/minekov ...`

Useful commands:
- Start training:
  - `/minekov train start <mode> <num_operators> <pos> [radius] [rounds]`
- Stop training:
  - `/minekov train stop`
- Spawn 1v1 play mode AI:
  - `/minekov play`

When training starts, the Java side sends a `session_start` message to Python with `num_agents`.

---

## 6) Troubleshooting

- Python not connected:
  - Confirm backend is running on port `8050`
  - Confirm no firewall is blocking localhost traffic
- Build fails about missing `mods/TACZ`:
  - Re-run `git submodule update --init --recursive`
- Dependency resolution issues:
  - `.\gradlew.bat --refresh-dependencies` (Windows)
  - `./gradlew --refresh-dependencies` (macOS/Linux)
- Full clean rebuild:
  - `.\gradlew.bat clean` (Windows)
  - `./gradlew clean` (macOS/Linux)

---

## Notes

- This README is focused on local dev setup (mod + Python AI backend).
- Python API endpoint currently used by the mod is hardcoded to localhost port `8050`.
