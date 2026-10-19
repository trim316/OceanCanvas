# Minecraft 26.3 operator integration validation

Development only. Parent core source is 46dd325c4d40 (resolve full SHA from Git).
This core runtime is separate from the published production v253.125.83 engine.
It must not replace the JAR in active production scale validation or inherit its
1k acceptance automatically. No release certification is granted by compilation.

Sequence:
1. Compile/core self-test on Minecraft26.3 (1283 checks passed).
2. Fresh disposable server bootstrap and command selection/control checks.
3. Focused authoring, protection, pause/resume/cancel and exact preimage restore;
   persisted selections/rules/queue across restart, permission denial, wildlife.
4. Fresh 1k acceptance of the exact integrated JAR with lighting, vegetation,
   structure/loot and restart checks. Confirm production feature parity first.
5. Copy verified 5k/20k checkpoints and explicitly record parent and upgraded
   source/JAR/world identities. Do not overwrite parent artifacts or reset clocks.
   This is upgrade correctness evidence, not fresh20k/eight-hour certification.

Existing scale runs remain pinned to their own production engines. Porting core
commands requires an explicit compatible integration; never install both mods
with the same oceancanvas ID or combine two terrain-authoring authorities.
