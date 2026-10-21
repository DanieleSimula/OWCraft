# OWCraft: Minecraft inside Outer Wilds

> **Fork by Daniele Simula** of [OWCraft by Yaekai](https://github.com/Yaekai/OWCraft). In this fork
> Outer Wilds stays the host game and you play as Steve by default: every Outer Wilds interaction
> (characters, campfires, elevators, statues, launch codes, ship tractor beam) works from Minecraft
> mode. Mod id: `DanieleSimula.OWCraft`. See [CHANGELOG.md](CHANGELOG.md).

Fly your ship to a planet, get out, press **F6**, and you're playing real Minecraft there. Break and
place blocks, open your inventory, light TNT, and build a house on Timber Hearth. Press **F6** again
to get back into your spacesuit. The house stays on the planet.

This is a *passthrough* mod, built on chasmlol's [SkyCraft](https://github.com/chasmlol/SkyCraft)
(Minecraft in Skyrim). Both games run at the same time and talk over shared memory:

- Minecraft simulates the player and the blocks against the planet's real surface.
- Outer Wilds draws the blocks on the planet with its own lighting, plus Minecraft's hand, HUD and
  screens on top.

See [docs/DESIGN.md](docs/DESIGN.md) for how it works.

**Status: experimental, but playable.** It has been tested on one Windows PC: building, TNT and
walking around planets work at 60 fps. Expect rough edges (see *Known limitations*).

## Install (players)
You need Windows, Outer Wilds (Steam) and Minecraft Java Edition.

1. **Outer Wilds:** install the [Outer Wilds Mod Manager](https://outerwildsmods.com/mod-manager/) and
   start Outer Wilds from it once.
2. **OWCraft (this fork):** download `DanieleSimula.OWCraft-<version>.zip` from
   [Releases](https://github.com/DanieleSimula/OWCraft/releases). Unzip it into
   `%AppData%\OuterWildsModManager\OWML\Mods\`, so you get a `Mods\DanieleSimula.OWCraft` folder.
   If you also have Yaekai's original (`Mods\Yaekai.OWCraft`), disable or remove it in the Mod
   Manager: the two can't be loaded together.
3. **Minecraft:** install [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5 or newer for
   Minecraft **26.3**. Put two files in your `.minecraft\mods` folder:
   - `skycraft-<version>+owcraft.<n>.ds<m>.jar` from this fork's
     [Releases](https://github.com/DanieleSimula/OWCraft/releases), or built from `guest/fabric`
     (see *Build from source*). Yaekai's jar still works with the *Outer Wilds physics* setting off;
   - [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3.
4. Start Minecraft with the Fabric profile in the normal launcher.
5. Start Outer Wilds from the Mod Manager.

## Play
1. Once Outer Wilds connects, Minecraft opens its own void world (or creates it the first time). The
   planet supplies the ground.
2. By default every loop starts as Steve (Minecraft mode). If you turned that off, land somewhere,
   stand on the ground and press **F6**.
3. **Outer Wilds interactions as Steve:** look at something you can interact with (a character, a
   campfire, an elevator, the model rocket, a sign, the launch codes, the ship) and a prompt shows
   the action with the right mouse button icon. **Right-click** to do it: the game switches to the
   Outer Wilds character for the interaction, then goes back to Steve on its own. Statues and the
   ship's tractor beam also work as Steve.
4. Minecraft controls apply:
   - **O** opens Minecraft's menu;
   - **F5** switches to third person (Minecraft's body is drawn on the planet), like in Minecraft;
   - **Esc** pauses Outer Wilds, or closes a Minecraft screen;
   - **F6** leaves Minecraft mode (and enters it again).

If Steve dies in Survival, the Outer Wilds player dies too and the loop ends.

Each patch of planet maps to its own fixed area of the Minecraft world, so whatever you build is
saved by Minecraft. It is still there after time loops and restarts.

Settings (Mod Manager):

| Setting | Default | What it does |
|---|---|---|
| Toggle key | F6 | Enter and leave Minecraft mode |
| Start as Steve | on | Start every loop in Minecraft mode |
| Outer Wilds physics | off | Experimental: Steve moves with the Outer Wilds body (its gravity and collisions: ship, elevators, islands); Minecraft follows. Needs the Minecraft jar built from this fork's patch |
| Minecraft menu key | O | Open Minecraft's pause menu |
| Minecraft mode FPS cap | 60 | Outer Wilds frame cap while in Minecraft mode |
| Minecraft walk speed | 6.0 | Walking speed in m/s (vanilla Minecraft is 4.3) |
| Verbose logging | off | Extra diagnostics in the OWML log |

## Build from source
You need the .NET SDK and a JDK 25.

**Outer Wilds side:**
```bash
dotnet build host/OWCraft.csproj -c Release
```
If Outer Wilds isn't in the default Steam folder, add `-p:GameDir="D:\Games\Outer Wilds"`.
When the Mod Manager is installed, the build copies `OWCraft.dll`, `manifest.json` and
`default-config.json` into `%AppData%\OuterWildsModManager\OWML\Mods\DanieleSimula.OWCraft`.

**Minecraft side:** `guest/fabric`, SkyCraft's Fabric mod (commit
`bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32`, MIT, see `guest/fabric/LICENSE-SkyCraft`) with
OWCraft's changes already applied. You need a JDK 25. On top of SkyCraft it:
- skips presenting Minecraft's own hidden window while linked (this took Minecraft from 25 to 60 fps);
- reads the overlay back at once, so inventory screens keep up with the mouse;
- sets OWCraft's defaults: the `Local\OWCraft_v1` link, no "Skyrim destruction" (Outer Wilds can't
  carve its planets), Minecraft keeps running when Outer Wilds closes, and no Discord status;
- keeps speed and elytra flight when Outer Wilds moves you to a new planet tile, and brings the
  mobs and items around you along;
- lets mobs path over the planet surface, and holds mobs and items still where the planet's ground
  hasn't streamed in yet (instead of letting them fall into the void);
- sends arrows, tridents and dropped items through the scene so Outer Wilds draws them;
- adds a frame-time log;
- (this fork) follows the Outer Wilds body's position when the *Outer Wilds physics* setting is on.

```bash
cd guest/fabric
./gradlew build       # the jar lands in build/libs (gradlew.bat on Windows)
./gradlew runClient   # or run it straight from source
```

## Known limitations
- Translucent blocks (glass, water, ice) are drawn opaque.
- Outer Wilds' own player stands on Minecraft's blocks and bumps into them, but can't open doors or trapdoors yet.
- Flying the ship as Steve isn't supported yet: piloting switches to the Outer Wilds character.
  Planned for 0.3.
- The Minecraft world doesn't follow Outer Wilds' day/night.
- Nether portals and other dimensions aren't supported.
- Mobs only find the planet's ground within about 40 blocks of you. Further away they wait in place.

## Repository layout
- `host/`: the OWML mod (C#). `Link/` holds the shared-memory protocol, `World/` the planet tiles,
  collision and rendering, `Player/` the movement, input and commands, `UI/` the overlay.
- `guest/fabric`: the Minecraft side, SkyCraft's Fabric mod with OWCraft's changes.
- `tests/VoxelTest`: a unit test for the collision voxelizer.
- `tools/check-logs.py`: reads both games' logs after a play test and lists known problems.
- `docs/DESIGN.md`: the architecture.
- `CHANGELOG.md`: what changed in each release.
- `MODLOG.md`: the full development log, test by test.

## Honesty note
This mod was written by an AI (Claude, by Anthropic), directed and play-tested by Yaekai. It ports
SkyCraft's Skyrim-side design to Outer Wilds. The changes in this fork (from 0.2.0 on) were also
written with Claude, directed and play-tested by Daniele Simula. [MODLOG.md](MODLOG.md) records what was done and
verified, and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) what was ported.

No game files, decompiled code or Minecraft assets are in this repository. Textures come from your
own Minecraft at runtime.

## License
MIT ([LICENSE](LICENSE)), copyright Yaekai and Daniele Simula. Parts ported from SkyCraft are MIT too; see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
