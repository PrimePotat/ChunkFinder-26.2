# ActivityScanner (Minecraft Java 26.2 / Fabric)

A beginner starter mod based on the supplied Fabric 26.2 template. It checks block states already available in the client world, processing one chunk per client tick in a 5-chunk radius and delaying its report for at least 15 seconds.

## What it currently does
- Checks loaded client-side block data for amethyst clusters/budding amethyst, kelp, cave vines, vines, bamboo, cocoa, bee hives, and bee nests.
- Reports chunk coordinates when a chunk contains at least 12 selected blocks.
- Reports visible spawner block states separately.
- Repeats scans about once per minute.

## Important limitations
- This starter build reports candidates in chat. It does **not** draw purple world-space boxes or columns yet.
- It only reads block data available in the client's loaded world. It does not load chunks, request hidden data, bypass server protections, or reveal blocks the server has not sent.
- Block patterns are not reliable proof of player activity. The spawner report does not establish that a spawner is a skeleton spawner unless the server has made that entity/type information available to the client.
- No mod can guarantee that a server will not ban it. Check DonutSMP's current rules and ask staff before using scanning/overlay mods on the server.

## Build on Windows
1. Install JDK 25 (the supplied template targets Java 25).
2. Extract this ZIP to a normal folder.
3. Open the folder in IntelliJ IDEA and allow Gradle to sync.
4. In Command Prompt / PowerShell, from the project folder, run `gradlew.bat build`.
5. The jar should appear in `build\libs\` after a successful build. Put that jar in your Minecraft `mods` folder along with Fabric API and Fabric Language Kotlin versions compatible with Minecraft 26.2.

If Gradle reports an API or mapping error, share the exact error text; the Minecraft 26.2 ecosystem is very new and may require an API adjustment.
