Goety Fix
Fixes memory leaks and bugs in Goety/GoetyAwaken mods for Minecraft 1.20.1 (Forge).

Fixes
ServerPlayer Memory Leak

Clears stale ServerPlayer references held by Summoned entities (commandPosEntity, priorityTarget) on player respawn/logout
Periodically cleans up leaked entries in static maps (SpecialServantEvents.lastMoneyAmounts, EnhancedEntityEvents.enhancedEntities)
Prevents ~10GB+ memory leaks on servers with frequent player deaths/dimension changes
Apostle Cross-Dimension Teleport

Prevents the Apostle boss from teleporting to another dimension when its target enters a portal
Requirements
Forge 47+
Goety 2.5.46.1+
GoetyAwaken (optional)
