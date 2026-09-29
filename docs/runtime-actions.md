# Ocean Canvas GitHub runtime dashboard

GitHub Actions is the control plane for Ocean Canvas validation.

The actual Minecraft/Modrinth runtime campaign executes on the user's Windows PC through a GitHub Actions self-hosted Windows runner. The runner attaches to the already-installed autonomous supervisor at:

`%LOCALAPPDATA%\OceanCanvas\AutonomousSupervisor\workspace`

The Actions workflow does not rebuild Ocean Canvas. Runtime validation remains frozen to `26.2-core-v0.2.23`. The local supervisor owns Minecraft launch/restart cycles and the gate ledger; GitHub displays progress and retains the evidence.

Expected visible progression:

`G2 PASS -> G4 PASS -> G9 -> G16 -> PROVEN`

Every runtime chunk still requires COMPLETE, finalRestartVerified=true, and 7 verified restarts before the next chunk is admitted. Maximum active chunks remains 1.

If another autonomous supervisor instance is already running, the Actions bridge attaches to its durable state rather than starting a competing campaign.